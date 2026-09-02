package com.gnutux.gmd.ui

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.gnutux.gmd.data.Settings
import com.gnutux.gmd.download.AudioFormat
import com.gnutux.gmd.download.Downloader
import com.gnutux.gmd.download.MediaInfo
import com.gnutux.gmd.download.Quality
import com.gnutux.gmd.update.Updater
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import java.io.File

sealed interface UpdatePhase {
    data object Idle : UpdatePhase
    data object Checking : UpdatePhase
    data object UpToDate : UpdatePhase
    data class Available(val info: Updater.Check) : UpdatePhase
    data class Downloading(val received: Long, val total: Long) : UpdatePhase
    data class Downloaded(val file: File) : UpdatePhase
    data class Error(val message: String) : UpdatePhase
}

class GmdViewModel(app: Application) : AndroidViewModel(app) {

    private val settings = Settings(app)

    var url = MutableStateFlow("")
        private set
    val quality = MutableStateFlow(Quality.P1080)
    val audioFormat = MutableStateFlow(AudioFormat.MP3)

    private val _info = MutableStateFlow<MediaInfo?>(null)
    val info: StateFlow<MediaInfo?> = _info

    private val _infoLoading = MutableStateFlow(false)
    val infoLoading: StateFlow<Boolean> = _infoLoading

    private val _infoError = MutableStateFlow<String?>(null)
    val infoError: StateFlow<String?> = _infoError

    private val _ytdlpVersion = MutableStateFlow<String?>(null)
    val ytdlpVersion: StateFlow<String?> = _ytdlpVersion

    private val _update = MutableStateFlow<UpdatePhase>(UpdatePhase.Idle)
    val update: StateFlow<UpdatePhase> = _update

    val autoCheckUpdates = settings.autoCheckUpdates
    val allowPrerelease = settings.allowPrerelease

    @Volatile private var cancelDownload = false

    fun setUrl(value: String) {
        url.value = value
        _info.value = null
        _infoError.value = null
    }

    fun loadInfo() {
        val target = url.value.trim()
        if (target.isEmpty()) return
        viewModelScope.launch {
            _infoLoading.value = true
            _infoError.value = null
            Downloader.fetchInfo(target).fold(
                onSuccess = { _info.value = it },
                onFailure = { _infoError.value = it.message ?: "failed" },
            )
            _infoLoading.value = false
        }
    }

    fun loadYtDlpVersion() {
        viewModelScope.launch { _ytdlpVersion.value = Downloader.version(getApplication()) }
    }

    fun updateYtDlp(onDone: (Boolean) -> Unit) {
        viewModelScope.launch {
            val ok = Downloader.updateYtDlp(getApplication()).isSuccess
            _ytdlpVersion.value = Downloader.version(getApplication())
            onDone(ok)
        }
    }

    // ── التحديث الذاتيّ ───────────────────────────────────────────────────────
    fun setAutoCheck(value: Boolean) = viewModelScope.launch { settings.setAutoCheckUpdates(value) }
    fun setAllowPrerelease(value: Boolean) = viewModelScope.launch { settings.setAllowPrerelease(value) }

    /** فحصٌ صامتٌ عند الإقلاع، لا يزعج المستخدم إن لم يكن هناك جديد. */
    fun checkForUpdatesOnLaunch() {
        viewModelScope.launch {
            if (!settings.autoCheckUpdates.first()) return@launch
            val last = settings.lastUpdateCheck.first()
            if (System.currentTimeMillis() - last < Settings.CHECK_INTERVAL_MS) return@launch
            val result = Updater.check(getApplication(), settings.allowPrerelease.first())
            settings.markUpdateChecked()
            if (result.ok && result.updateAvailable) _update.value = UpdatePhase.Available(result)
        }
    }

    fun checkForUpdates() {
        viewModelScope.launch {
            _update.value = UpdatePhase.Checking
            val result = Updater.check(getApplication(), settings.allowPrerelease.first())
            settings.markUpdateChecked()
            _update.value = when {
                !result.ok -> UpdatePhase.Error(result.error ?: "failed")
                result.updateAvailable -> UpdatePhase.Available(result)
                else -> UpdatePhase.UpToDate
            }
        }
    }

    fun downloadUpdate(asset: Updater.Asset) {
        cancelDownload = false
        viewModelScope.launch {
            _update.value = UpdatePhase.Downloading(0, asset.size)
            Updater.download(getApplication(), asset, { cancelDownload }) { received, total ->
                _update.value = UpdatePhase.Downloading(received, total)
            }.fold(
                onSuccess = { _update.value = UpdatePhase.Downloaded(it) },
                onFailure = {
                    _update.value = if (cancelDownload) UpdatePhase.Idle
                    else UpdatePhase.Error(it.message ?: "failed")
                },
            )
        }
    }

    fun cancelUpdateDownload() { cancelDownload = true }
    fun dismissUpdate() { _update.value = UpdatePhase.Idle }
}
