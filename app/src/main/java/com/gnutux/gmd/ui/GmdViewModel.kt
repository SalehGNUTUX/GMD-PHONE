package com.gnutux.gmd.ui

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.gnutux.gmd.data.Settings
import com.gnutux.gmd.download.AudioFormat
import com.gnutux.gmd.download.Downloader
import com.gnutux.gmd.download.MediaInfo
import com.gnutux.gmd.download.Quality
import com.gnutux.gmd.download.Section
import com.gnutux.gmd.GmdApp
import com.gnutux.gmd.ToolsState
import com.gnutux.gmd.update.Updater
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import java.io.File

/** حالةُ تحديثِ ثنائيّ yt-dlp. */
sealed interface ToolPhase {
    data object Idle : ToolPhase
    data object Working : ToolPhase
    data object UpToDate : ToolPhase
    data class Updated(val version: String) : ToolPhase
    data class Failed(val message: String) : ToolPhase
}

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

    // ── الاقتصاص ──────────────────────────────────────────────────────────────
    // النصُّ يبقى كما يكتبه المستخدم ويُحلَّل عند الطلب، فتحويله رقماً مع كلِّ حرفٍ
    // يمنعه من كتابة «1:05» أصلاً — تُمسَح «:» قبل أن يبلغها.
    val clipEnabled = MutableStateFlow(false)
    val clipStart = MutableStateFlow("")
    val clipEnd = MutableStateFlow("")

    /** يقبل ثوانيَ مجرّدة، أو m:ss، أو h:mm:ss. ويُعيد null إن لم يصحّ. */
    fun parseClock(text: String): Int? {
        val t = text.trim()
        if (t.isEmpty()) return null
        val parts = t.split(":")
        if (parts.size > 3) return null
        var total = 0
        for (p in parts) {
            val n = p.trim().toIntOrNull() ?: return null
            if (n < 0) return null
            total = total * 60 + n
        }
        return total
    }

    /** المقطع المطلوب، أو null إن كان الاقتصاص مطفأً أو حدّاه غير صالحين. */
    fun section(): Section? {
        if (!clipEnabled.value) return null
        val s = parseClock(clipStart.value) ?: 0
        val e = parseClock(clipEnd.value) ?: return null
        return Section(s, e).takeIf { it.valid }
    }

    /** يملأ حقول الاقتصاص من محاولةٍ سابقة، لتُعاد بمثلها. */
    fun setSection(start: Int?, end: Int?) {
        if (start == null || end == null) {
            clipEnabled.value = false; clipStart.value = ""; clipEnd.value = ""
            return
        }
        clipEnabled.value = true
        clipStart.value = formatClock(start)
        clipEnd.value = formatClock(end)
    }

    private fun formatClock(t: Int): String =
        if (t >= 3600) "%d:%02d:%02d".format(t / 3600, (t % 3600) / 60, t % 60)
        else "%d:%02d".format(t / 60, t % 60)

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

    private var autoInfoJob: Job? = null

    /**
     * كلُّ تغييرٍ للرابطِ يُطلِقُ جلبَ المعلوماتِ من تلقائِه، بعدَ مهلةٍ قصيرة.
     *
     * المهلةُ ليست زينة: `setUrl` تُستدعى على كلِّ حرفٍ يُكتَب، وبلا تأخيرٍ يُشغَّلُ
     * yt-dlp عشراتِ المرّاتِ في ثانية. وكلُّ استدعاءٍ جديدٍ يُلغي سابقَه، فلا تصلُ
     * إلّا نتيجةُ الرابطِ الأخير.
     */
    fun setUrl(value: String) {
        url.value = value
        _info.value = null
        _infoError.value = null
        autoInfoJob?.cancel()
        _infoLoading.value = false

        val target = value.trim()
        if (!target.startsWith("http")) return

        autoInfoJob = viewModelScope.launch {
            delay(AUTO_INFO_DELAY_MS)
            // الأدواتُ قد تكونُ ما تزالُ تُهيَّأُ عندَ أوّلِ تشغيل
            if (GmdApp.instance.tools.value !is ToolsState.Ready) return@launch
            _infoLoading.value = true
            Downloader.fetchInfo(target).fold(
                onSuccess = { _info.value = it },
                onFailure = { _infoError.value = it.message ?: "failed" },
            )
            _infoLoading.value = false
        }
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

    private val _ytdlpPhase = MutableStateFlow<ToolPhase>(ToolPhase.Idle)
    val ytdlpPhase: StateFlow<ToolPhase> = _ytdlpPhase

    /**
     * تحديثُ ثنائيّ yt-dlp.
     *
     * كانت الحالةُ لا تُعرَض: يُنقَر الزرُّ فلا دوّارةَ ولا رسالة، ولا يُدرى أعملَ
     * أم لم يعمل. صارت المراحلُ مكشوفةً كما في تحديثِ البرنامج نفسِه.
     */
    fun updateYtDlp(onDone: (Boolean) -> Unit = {}) {
        if (_ytdlpPhase.value is ToolPhase.Working) return
        viewModelScope.launch {
            _ytdlpPhase.value = ToolPhase.Working
            val result = Downloader.updateYtDlp(getApplication())
            _ytdlpVersion.value = Downloader.version(getApplication())
            _ytdlpPhase.value = result.fold(
                onSuccess = { status ->
                    // المكتبة تُعيد UNCHANGED إن كان المثبَّت هو الأحدث أصلاً،
                    // وهي حالةٌ ناجحةٌ لا فاشلة، والفرق بينهما يهمّ المستخدم.
                    if (status.equals("UNCHANGED", ignoreCase = true)) ToolPhase.UpToDate
                    else ToolPhase.Updated(_ytdlpVersion.value.orEmpty())
                },
                onFailure = { ToolPhase.Failed(it.message ?: it::class.java.simpleName) },
            )
            onDone(result.isSuccess)
        }
    }

    fun dismissYtDlpPhase() { _ytdlpPhase.value = ToolPhase.Idle }

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

    private companion object {
        /** مهلةُ الهدوءِ قبلَ سؤالِ yt-dlp عن رابطٍ يُكتَبُ حرفاً حرفاً. */
        const val AUTO_INFO_DELAY_MS = 700L
    }
}
