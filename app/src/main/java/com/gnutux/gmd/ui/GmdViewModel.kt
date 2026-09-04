package com.gnutux.gmd.ui

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.gnutux.gmd.data.Settings
import com.gnutux.gmd.download.AudioFormat
import com.gnutux.gmd.download.DownloadService
import com.gnutux.gmd.download.JobInfo
import com.gnutux.gmd.download.Downloader
import com.gnutux.gmd.download.MediaInfo
import com.gnutux.gmd.download.PlaylistInfo
import com.gnutux.gmd.download.Quality
import com.gnutux.gmd.download.Section
import com.gnutux.gmd.GmdApp
import com.gnutux.gmd.ToolsState
import com.gnutux.gmd.media.Trimmer
import com.gnutux.gmd.update.UpdateDownload
import com.gnutux.gmd.update.UpdateService
import com.gnutux.gmd.update.Updater
import kotlinx.coroutines.CoroutineScope
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

private fun formatClock(t: Int): String =
    if (t >= 3600) "%d:%02d:%02d".format(t / 3600, (t % 3600) / 60, t % 60)
    else "%d:%02d".format(t / 60, t % 60)

/**
 * حالةُ قسمٍ واحدٍ من قسمَي التنزيل: رابطُه ومعلوماتُه وقائمتُه وخياراتُه.
 *
 * كانت هذه الحقولُ في نموذجِ العرضِ مرّةً واحدةً يتقاسمُها القسمان، فيرى المستخدمُ
 * في قسمِ الفيديو رابطَ الصوتِ وقائمتَه وتقدُّمَه، ولا يستطيعُ أن يُنزّلَ في أحدِهما
 * وهو يعملُ في الآخر. والقسمانِ عملانِ مستقلّانِ فحالتاهما مستقلّتان.
 */
class SectionState(
    private val application: Application,
    private val scope: CoroutineScope,
    val isAudio: Boolean,
) {
    val url = MutableStateFlow("")
    val quality = MutableStateFlow(Quality.P1080)
    val audioFormat = MutableStateFlow(AudioFormat.MP3)

    /** ما يُمرَّرُ للخدمةِ ويُحفَظُ في السجلّ: صيغةُ الصوتِ أو جودةُ الفيديو. */
    fun choice(): String = if (isAudio) audioFormat.value.name else quality.value.name

    // ── الاقتصاص ──────────────────────────────────────────────────────────────
    // النصُّ يبقى كما يكتبه المستخدم ويُحلَّل عند الطلب، فتحويله رقماً مع كلِّ حرفٍ
    // يمنعه من كتابة «1:05» أصلاً — تُمسَح «:» قبل أن يبلغها.
    val clipEnabled = MutableStateFlow(false)
    val clipStart = MutableStateFlow("")
    val clipEnd = MutableStateFlow("")

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

    private val _info = MutableStateFlow<MediaInfo?>(null)
    val info: StateFlow<MediaInfo?> = _info

    private val _infoLoading = MutableStateFlow(false)
    val infoLoading: StateFlow<Boolean> = _infoLoading

    private val _infoError = MutableStateFlow<String?>(null)
    val infoError: StateFlow<String?> = _infoError

    private val _playlist = MutableStateFlow<PlaylistInfo?>(null)
    val playlist: StateFlow<PlaylistInfo?> = _playlist

    /** مؤشّرات العناصر المختارة؛ الكلُّ مختارٌ عند الكشف كما في نسخة الحاسوب. */
    val playlistSelection = MutableStateFlow<Set<Int>>(emptySet())

    fun togglePlaylistItem(index: Int) {
        val s = playlistSelection.value
        playlistSelection.value = if (index in s) s - index else s + index
    }

    fun togglePlaylistAll() {
        val all = _playlist.value?.entries?.map { it.index }?.toSet().orEmpty()
        playlistSelection.value = if (playlistSelection.value.size == all.size) emptySet() else all
    }

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
        _playlist.value = null
        playlistSelection.value = emptySet()
        autoInfoJob?.cancel()
        _infoLoading.value = false

        val target = value.trim()
        if (!target.startsWith("http")) return

        autoInfoJob = scope.launch {
            delay(AUTO_INFO_DELAY_MS)
            // الأدواتُ قد تكونُ ما تزالُ تُهيَّأُ عندَ أوّلِ تشغيل
            if (GmdApp.instance.tools.value !is ToolsState.Ready) return@launch
            _infoLoading.value = true
            // الكشفُ عن القائمة أوّلاً: `--flat-playlist` طلبٌ واحدٌ مهما طالت،
            // ولو سألنا عن المقطعِ أوّلاً لجلبنا بياناتِ أوّلِ عنصرٍ لا القائمة.
            val pl = Downloader.fetchPlaylist(target)
            if (pl != null) {
                _playlist.value = pl
                playlistSelection.value = pl.entries.map { it.index }.toSet()
            } else {
                Downloader.fetchInfo(target).fold(
                    onSuccess = { _info.value = it },
                    onFailure = { _infoError.value = it.message ?: "failed" },
                )
            }
            _infoLoading.value = false
        }
    }

    /**
     * يستعيدُ قسماً تركَه المستخدمُ يعملُ ثمّ أغلقَ التطبيق.
     *
     * ولا يُطلَقُ الجلبُ التلقائيُّ هنا: التنزيلُ جارٍ فعلاً، وسؤالُ yt-dlp عن رابطٍ
     * يُنزَّلُ الآنَ إنفاقٌ للشبكةِ على ما لا يُنتظَر. لكنّ القائمةَ تُجلَبُ إن كانت
     * المهمّةُ قائمةَ تشغيل، فبلا عناصرِها لا يُرى ما تمَّ منها.
     */
    fun restore(info: JobInfo) {
        if (url.value.isNotBlank()) return
        url.value = info.url
        runCatching {
            if (isAudio) audioFormat.value = AudioFormat.valueOf(info.choice)
            else quality.value = Quality.valueOf(info.choice)
        }
        setSection(info.sectionStart, info.sectionEnd)
        if (info.playlistItems.isNotEmpty()) {
            playlistSelection.value = info.playlistItems.toSet()
            autoInfoJob?.cancel()
            autoInfoJob = scope.launch {
                if (GmdApp.instance.tools.value !is ToolsState.Ready) return@launch
                _playlist.value = Downloader.fetchPlaylist(info.url)
            }
        }
    }

    fun loadInfo() {
        val target = url.value.trim()
        if (target.isEmpty()) return
        scope.launch {
            _infoLoading.value = true
            _infoError.value = null
            Downloader.fetchInfo(target).fold(
                onSuccess = { _info.value = it },
                onFailure = { _infoError.value = it.message ?: "failed" },
            )
            _infoLoading.value = false
        }
    }

    private companion object {
        /** مهلةُ الهدوءِ قبلَ سؤالِ yt-dlp عن رابطٍ يُكتَبُ حرفاً حرفاً. */
        const val AUTO_INFO_DELAY_MS = 700L
    }
}

class GmdViewModel(app: Application) : AndroidViewModel(app) {

    private val settings = Settings(app)

    /** قسمانِ مستقلّانِ يعملانِ معاً: رابطٌ هنا وآخرُ هناك، وتنزيلانِ متوازيان. */
    val video = SectionState(app, viewModelScope, isAudio = false)
    val audio = SectionState(app, viewModelScope, isAudio = true)

    /** شاشةُ «معلومات الوسائط» ترابطُها الخاصّ: سؤالٌ لا يُبدِّلُ تنزيلاً جارياً. */
    val probe = SectionState(app, viewModelScope, isAudio = false)

    fun section(isAudio: Boolean): SectionState = if (isAudio) audio else video

    init {
        // حالةُ التنزيل تُملَى من الخدمة لا من هنا، فهي التي تبقى حيّةً بعد
        // مغادرة الواجهة، وإليها يعود المستخدمُ فيجد التقدُّم كما تركه.
        viewModelScope.launch {
            UpdateService.state.collect { s ->
                when (s) {
                    is UpdateDownload.Running -> _update.value =
                        UpdatePhase.Downloading(s.received, s.total)
                    is UpdateDownload.Done -> _update.value = UpdatePhase.Downloaded(s.file)
                    is UpdateDownload.Failed -> _update.value = UpdatePhase.Error(s.message)
                    UpdateDownload.Idle -> Unit
                }
            }
        }
    }

    /**
     * يُعيدُ إلى الشاشةِ ما كانَ يعملُ حينَ أُغلِقَ التطبيق.
     *
     * الخدمةُ تبقى حيّةً بإشعارِها بينما تُهدَمُ الشاشةُ ونموذجُها معها، فكانَ
     * المستخدمُ يعودُ إلى حقلٍ فارغٍ وشريطٍ ساكنٍ والتنزيلُ يجري في الخلفيّة.
     */
    fun restoreRunningJobs() {
        DownloadService.jobs.value.forEach { (kind, info) ->
            section(kind == Downloader.Kind.AUDIO).restore(info)
        }
    }

    fun parseClock(text: String): Int? = com.gnutux.gmd.ui.parseClock(text)

    // ── اقتصاصُ ملفٍّ من الجهاز ────────────────────────────────────────────────
    // حالةٌ مستقلّةٌ عن حقولِ الاقتصاصِ عندَ التنزيل: الشاشتانِ تعملانِ على مادّتَين
    // مختلفتَين، وخلطُهما يجعلُ اختيارَ ملفٍّ يُبدِّلُ حدَّي رابطٍ قيدَ التنزيل.
    private val _trimSource = MutableStateFlow<Trimmer.Source?>(null)
    val trimSource: StateFlow<Trimmer.Source?> = _trimSource
    val trimStart = MutableStateFlow("")
    val trimEnd = MutableStateFlow("")

    /** يُبدِّل مادّةَ الاقتصاص، ويُصفّر الحدَّين فلا يبقى حدُّ ملفٍّ على ملفٍّ آخر. */
    fun setTrimSource(source: Trimmer.Source?) {
        _trimSource.value = source
        trimStart.value = ""
        trimEnd.value = ""
    }

    private val _ytdlpVersion = MutableStateFlow<String?>(null)
    val ytdlpVersion: StateFlow<String?> = _ytdlpVersion

    private val _update = MutableStateFlow<UpdatePhase>(UpdatePhase.Idle)
    val update: StateFlow<UpdatePhase> = _update

    val autoCheckUpdates = settings.autoCheckUpdates
    val allowPrerelease = settings.allowPrerelease

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

    // ── التحديثُ الذاتيّ ───────────────────────────────────────────────────────
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

    /**
     * يُسلّم التنزيلَ إلى خدمةٍ مقدِّمة.
     *
     * كان يجري في نطاق هذا الـViewModel، وأندرويد يُجمّد العمليّة حين تغادر
     * الواجهةُ المقدّمةَ — فحزمةٌ تقارب 40 م.ب تموت في منتصفها كلّما نظر
     * المستخدمُ في تطبيقٍ آخر. والاستئناف من موضع الانقطاع يتكفّل به
     * [Updater.download] بملفّ `.part` وترويسة `Range`.
     */
    fun downloadUpdate(asset: Updater.Asset) {
        UpdateService.reset()
        UpdateService.start(getApplication(), asset)
    }

    fun cancelUpdateDownload() {
        UpdateService.stop(getApplication())
        _update.value = UpdatePhase.Idle
    }
    fun dismissUpdate() { _update.value = UpdatePhase.Idle }
}
