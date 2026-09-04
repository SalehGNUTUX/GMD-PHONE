package com.gnutux.gmd.download

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.Build
import android.os.IBinder
import android.os.PowerManager
import androidx.core.app.NotificationCompat
import com.gnutux.gmd.MainActivity
import com.gnutux.gmd.R
import com.gnutux.gmd.data.LocalePrefs
import com.gnutux.gmd.download.Downloader.Kind
import com.gnutux.gmd.download.Downloader.Phase
import com.gnutux.gmd.history.HistoryEntry
import com.gnutux.gmd.history.HistoryStore
import com.gnutux.gmd.history.Outcome
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicBoolean

/** ما تعرضه الواجهة عن التنزيل الجاري. */
sealed interface Progress {
    data object Idle : Progress

    /**
     * [phase] ما يجري الآن: تنزيلٌ أم تحويلٌ أم تجميعٌ أم حفظ. والنسبةُ وحدَها لا
     * تكفي: تقفُ عند 100٪ حين ينتهي التنزيلُ ويبدأ ما بعدَه، فيظنُّ المستخدمُ أنّ
     * البرنامجَ تجمّد. و[item] موضعُ العنصرِ من قائمةِ التشغيل، فيُعرَفَ ما تمَّ منها.
     */
    data class Running(
        val percent: Float,
        val etaSeconds: Long,
        val line: String,
        val phase: Phase = Phase.DOWNLOADING,
        val item: Int = 0,
        val itemCount: Int = 0,
    ) : Progress

    /**
     * [uri] عنوانُ المدخلِ في معرضِ الوسائط — بدونِه لا فتحَ ولا مشاركةَ للناتج.
     * و[count] عددُ ما حُفِظ: أكثرُ من واحدٍ حين تكونُ المادّةُ قائمةَ تشغيل.
     */
    data class Done(
        val displayName: String,
        val relativePath: String,
        val uri: String,
        val count: Int = 1,
    ) : Progress
    data class Failed(val message: String) : Progress
}

/**
 * ما تحتاجُه الواجهةُ لتستعيدَ نفسَها بعدَ إغلاقِ التطبيقِ وإعادةِ فتحِه.
 *
 * الخدمةُ تبقى حيّةً بإشعارِها بينما تُهدَمُ الشاشةُ ونموذجُها، فيعودُ المستخدمُ إلى
 * حقلٍ فارغٍ بينما التنزيلُ يعمل. وهذه هي ذاكرةُ ما كان.
 */
data class JobInfo(
    val url: String,
    val choice: String,
    val playlistTitle: String?,
    val playlistItems: List<Int>,
    val sectionStart: Int?,
    val sectionEnd: Int?,
)

/**
 * خدمة مُقدِّمة تُنفّذ التنزيل.
 *
 * ليست تحسيناً بل شرط عمل: أندرويد يجمّد العمليّة حين تغادر الواجهة المقدّمة،
 * فتنزيل مقطعٍ طويل يموت في منتصفه لو جرى في نطاق شاشة. الإشعار الدائم هو الثمن
 * الذي يفرضه النظام مقابل بقاء العمل حيّاً، وقفل اليقظة يمنع نوم المعالج معه.
 *
 * وهي تحملُ **مهمّةً لكلِّ قسم**: قسمُ الفيديو وقسمُ الصوتِ يعملانِ معاً على
 * رابطَين مختلفَين. وكانت المهمّةُ واحدةً وحالتُها واحدة، فيرى المستخدمُ في قسمٍ
 * تقدُّمَ القسمِ الآخر، ويُلغي هذا ما يجري في ذاك.
 */
class DownloadService : Service() {

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private var wakeLock: PowerManager.WakeLock? = null

    /** بياناتُ المحاولةِ الجاريةِ، تُكتَبُ في السجلِّ مهما آلت إليه. */
    private data class Attempt(
        val url: String,
        val isAudio: Boolean,
        val choice: String,
        val title: String?,
        val uploader: String?,
        val duration: String?,
        val thumbnail: String?,
        val section: Section?,
        val playlist: PlaylistJob?,
        val playlistTitle: String?,
    )

    /**
     * مهمّةٌ جارية. و`recorded` يمنعُ تسجيلَ المحاولةِ مرّتَين: الإلغاءُ يُنهيها وقد
     * سُجِّلت، وقد يتلاقى المسلكانِ حينَ يُلغي المستخدمُ تنزيلاً في لحظةِ انتهائه.
     */
    private class Runner(
        val processId: String,
        val attempt: Attempt,
        val recorded: AtomicBoolean = AtomicBoolean(false),
    )

    private val runners = ConcurrentHashMap<Kind, Runner>()

    override fun onBind(intent: Intent?): IBinder? = null

    /** الإشعاراتُ تُكتَبُ بلغةِ الواجهةِ المختارة، لا بلغةِ النظامِ وحدَها. */
    override fun attachBaseContext(base: Context) {
        super.attachBaseContext(LocalePrefs.wrap(base))
    }

    override fun onCreate() {
        super.onCreate()
        createChannel()
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action) {
            ACTION_STOP -> {
                val kind = intent.getStringExtra(EXTRA_KIND)?.let { runCatching { Kind.valueOf(it) }.getOrNull() }
                if (kind != null) stopWork(kind) else Kind.entries.forEach { stopWork(it) }
                return START_NOT_STICKY
            }
            ACTION_START -> Unit
            else -> { settleForeground(); return START_NOT_STICKY }
        }

        val url = intent.getStringExtra(EXTRA_URL).orEmpty()
        val isAudio = intent.getBooleanExtra(EXTRA_IS_AUDIO, false)
        val kind = if (isAudio) Kind.AUDIO else Kind.VIDEO
        val choice = intent.getStringExtra(EXTRA_CHOICE).orEmpty()
        // مهمّةٌ جاريةٌ في القسمِ نفسِه لا تُستبدَلُ من تحتِها: الواجهةُ تُعطّلُ الزرَّ
        // ما دامت تعمل، وطلبٌ ثانٍ لا يأتي إلّا من إشعارٍ قديمٍ أو نقرةٍ مكرّرة
        if (url.isBlank() || runners.containsKey(kind)) { settleForeground(); return START_NOT_STICKY }

        // تُلتقَط الآن لأنّ `intent` لا يبقى متاحاً داخل المهمّة المتزامنة
        val attempt = Attempt(
            url = url,
            isAudio = isAudio,
            choice = choice,
            title = intent.getStringExtra(EXTRA_TITLE),
            uploader = intent.getStringExtra(EXTRA_UPLOADER),
            duration = intent.getStringExtra(EXTRA_DURATION),
            thumbnail = intent.getStringExtra(EXTRA_THUMB),
            section = Section(
                intent.getIntExtra(EXTRA_START, -1),
                intent.getIntExtra(EXTRA_END, -1),
            ).takeIf { it.valid },
            playlist = intent.getStringExtra(EXTRA_PL_FOLDER)?.let { folder ->
                intent.getIntArrayExtra(EXTRA_PL_ITEMS)?.takeIf { it.isNotEmpty() }
                    ?.let { PlaylistJob(folder, it.toList()) }
            },
            playlistTitle = intent.getStringExtra(EXTRA_PL_TITLE),
        )

        val processId = "${kind.name}-${System.currentTimeMillis()}"
        val runner = Runner(processId, attempt)
        runners[kind] = runner
        store(kind, JobInfo(
            url = url,
            choice = choice,
            playlistTitle = attempt.playlistTitle,
            playlistItems = attempt.playlist?.items.orEmpty(),
            sectionStart = attempt.section?.startSec,
            sectionEnd = attempt.section?.endSec,
        ))

        startForegroundCompat(buildOngoing())
        acquireWakeLock()

        val job: Job = if (isAudio) {
            Job.Audio(url,
                runCatching { AudioFormat.valueOf(choice) }.getOrDefault(AudioFormat.MP3),
                attempt.section, attempt.playlist)
        } else {
            Job.Video(url,
                runCatching { Quality.valueOf(choice) }.getOrDefault(Quality.P1080),
                attempt.section, attempt.playlist)
        }

        scope.launch {
            setProgress(kind, Progress.Running(0f, -1, ""))
            var phase = Phase.DOWNLOADING
            var item = 0
            var itemCount = attempt.playlist?.items?.size ?: 0

            val result = Downloader.run(applicationContext, job, processId) { percent, eta, line ->
                Downloader.Watch.phaseOf(line)?.let { phase = it }
                Downloader.Watch.itemOf(line)?.let { (i, n) -> item = i; itemCount = n }
                setProgress(kind, Progress.Running(percent, eta, line, phase, item, itemCount))
                notifyOngoing()
            }

            result.fold(
                onSuccess = { files ->
                    // النقلُ إلى المعرضِ مرحلةٌ يراها المستخدم: ملفّاتُ قائمةٍ كاملةٍ
                    // قد تكونُ مئاتِ الميغابايت، والصمتُ عندها يُقرَأُ تجمُّداً
                    setProgress(kind, Progress.Running(100f, -1, "", Phase.SAVING, item, itemCount))
                    notifyOngoing(force = true)
                    // كلُّ ملفٍّ يُنقَل على حدة، وقائمةُ التشغيل تُودَع مجلَّداً باسمِها.
                    // وفشلُ ملفٍّ لا يُهدِر ما نجح قبلَه: ما حُفِظ يبقى محفوظاً.
                    val folder = attempt.playlist?.folder
                    var saved: MediaStoreSaver.Saved? = null
                    var count = 0
                    var lastError: Throwable? = null
                    files.forEach { f ->
                        MediaStoreSaver.save(applicationContext, f, isAudio, folder).fold(
                            onSuccess = { saved = it; count++ },
                            onFailure = { lastError = it },
                        )
                    }
                    val s = saved
                    if (s == null) {
                        fail(kind, runner, lastError ?: IllegalStateException("nothing could be saved"))
                    } else {
                        val name = if (count > 1) (attempt.playlistTitle ?: s.relativePath)
                                   else s.displayName
                        record(runner, Outcome.SUCCESS, null, s.uri.toString(), name, s.relativePath, count)
                        setProgress(kind, Progress.Done(name, s.relativePath, s.uri.toString(), count))
                        notifyTerminal(kind, getString(R.string.notif_done), name)
                    }
                },
                onFailure = { fail(kind, runner, it) },
            )
            runners.remove(kind)
            stopIfDone()
        }
        return START_NOT_STICKY
    }

    private fun fail(kind: Kind, runner: Runner, t: Throwable) {
        val msg = t.message ?: t::class.java.simpleName
        record(runner, Outcome.FAILED, msg, null, null, null)
        setProgress(kind, Progress.Failed(msg))
        notifyTerminal(kind, getString(R.string.notif_failed), msg)
    }

    /** يكتبُ المحاولةَ في السجلِّ مرّةً واحدة. */
    private fun record(
        runner: Runner,
        outcome: Outcome,
        error: String?,
        uri: String?,
        name: String?,
        path: String?,
        savedCount: Int = 1,
    ) {
        if (!runner.recorded.compareAndSet(false, true)) return
        val a = runner.attempt
        val now = System.currentTimeMillis()
        // نطاقٌ مستقلٌّ عن `scope`: هذا الأخير يُلغى في onDestroy وقد تُقتَل الخدمةُ
        // فور التسجيل، فتضيع الكتابة.
        CoroutineScope(SupervisorJob() + Dispatchers.IO).launch {
            HistoryStore.add(
                applicationContext,
                HistoryEntry(
                    id = now, url = a.url, title = a.title, uploader = a.uploader,
                    duration = a.duration, thumbnail = a.thumbnail, isAudio = a.isAudio,
                    choice = a.choice, outcome = outcome, error = error,
                    savedUri = uri, savedName = name, savedPath = path, timestamp = now,
                    sectionStart = a.section?.startSec, sectionEnd = a.section?.endSec,
                    playlistTitle = a.playlistTitle,
                    playlistRequested = a.playlist?.items?.size,
                    playlistSaved = if (outcome == Outcome.SUCCESS) savedCount else null,
                ),
            )
        }
    }

    private fun stopWork(kind: Kind) {
        val runner = runners.remove(kind) ?: return
        Downloader.cancel(runner.processId)
        record(runner, Outcome.CANCELLED, null, null, null, null)
        setProgress(kind, Progress.Idle)
        forget(kind)
        cancelTerminal(kind)
        stopIfDone()
    }

    /**
     * طلبُ بدءٍ لا عملَ بعدَه — رابطٌ فارغٌ أو قسمٌ مشغولٌ أو نيّةٌ مجهولة.
     *
     * وأندرويد يُسقِطُ التطبيقَ إن لم يُستدعَ `startForeground` بعدَ
     * `startForegroundService`، فيُستدعى ثمّ يُنظَرُ أثمّةَ عملٌ يستحقُّ البقاء.
     */
    private fun settleForeground() {
        startForegroundCompat(buildOngoing())
        stopIfDone()
    }

    /** لا تُرفَعُ صفةُ المقدِّمةِ ما دامت مهمّةٌ أخرى تعمل. */
    private fun stopIfDone() {
        if (runners.isNotEmpty()) { notifyOngoing(); return }
        releaseWakeLock()
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.N) stopForeground(STOP_FOREGROUND_REMOVE)
        else @Suppress("DEPRECATION") stopForeground(true)
        stopSelf()
    }

    override fun onDestroy() {
        releaseWakeLock()
        scope.cancel()
        super.onDestroy()
    }

    // ── الإشعار ───────────────────────────────────────────────────────────────
    private fun createChannel() {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O) return
        val channel = NotificationChannel(CHANNEL, getString(R.string.notif_channel_dl),
            NotificationManager.IMPORTANCE_LOW).apply {
            description = getString(R.string.notif_channel_dl_desc)
            setShowBadge(false)
        }
        getSystemService(NotificationManager::class.java).createNotificationChannel(channel)
    }

    /** اسمُ القسمِ كما يراه المستخدم. */
    private fun label(kind: Kind): String =
        getString(if (kind == Kind.AUDIO) R.string.menu_audio else R.string.menu_video)

    private fun phaseLabel(phase: Phase): String = getString(
        when (phase) {
            Phase.DOWNLOADING -> R.string.phase_downloading
            Phase.CONVERTING -> R.string.phase_converting
            Phase.MERGING -> R.string.phase_merging
            Phase.SAVING -> R.string.phase_saving
        }
    )

    /**
     * إشعارُ العملِ الجاري: مهمّةٌ واحدةٌ باسمِها ومرحلتِها، أو مهمّتانِ في سطرٍ واحد.
     *
     * والنظامُ لا يقبلُ إلّا إشعارَ مقدِّمةٍ واحداً لكلِّ خدمة، فيُجمَعُ فيه ما يجري
     * بدلَ أن يُطمَسَ أحدُهما بالآخر.
     */
    private fun buildOngoing(): Notification {
        val open = PendingIntent.getActivity(this, 0, Intent(this, MainActivity::class.java),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT)

        val running = Kind.entries.mapNotNull { k ->
            (progressOf(k) as? Progress.Running)?.let { k to it }
        }
        val percent = running.map { it.second.percent }.takeIf { it.isNotEmpty() }
            ?.average()?.toInt() ?: 0
        val single = running.singleOrNull()

        val title = when {
            single != null -> "${getString(R.string.notif_downloading)} — ${label(single.first)}"
            running.size > 1 -> getString(R.string.notif_downloading_n, running.size)
            else -> getString(R.string.notif_downloading)
        }
        val text = when {
            single != null -> buildString {
                append(phaseLabel(single.second.phase))
                if (single.second.itemCount > 1 && single.second.item > 0) {
                    append("  ·  ")
                    append(getString(R.string.phase_item, single.second.item, single.second.itemCount))
                }
            }
            else -> running.joinToString("  ·  ") {
                "${label(it.first)} ${it.second.percent.toInt()}%"
            }
        }

        return NotificationCompat.Builder(this, CHANNEL)
            .setContentTitle(title)
            .setContentText(text)
            .setSmallIcon(android.R.drawable.stat_sys_download)
            .setContentIntent(open)
            .setOngoing(true)
            .setOnlyAlertOnce(true)
            .setProgress(100, percent.coerceIn(0, 100), percent <= 0)
            .apply {
                // زرُّ الإيقافِ يوقفُ المهمّةَ المعنيّةَ حين تكونُ واحدة، وكلَّ ما
                // يجري حين تكونانِ اثنتَين — ولا يُترَكُ زرٌّ يوقفُ ما لا يقصدُه
                val stop = Intent(this@DownloadService, DownloadService::class.java)
                    .setAction(ACTION_STOP)
                    .apply { single?.let { putExtra(EXTRA_KIND, it.first.name) } }
                addAction(0,
                    getString(if (single != null) R.string.notif_stop else R.string.notif_stop_all),
                    PendingIntent.getService(this@DownloadService, 2,
                        stop, PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT))
            }
            .build()
    }

    /**
     * يُحدَّثُ الإشعارُ نصفَ ثانيةٍ على الأكثر.
     *
     * فـyt-dlp يطبعُ سطرَ تقدُّمٍ عشراتِ المرّاتِ في الثانية، ومهمّتانِ تُضاعِفانِه،
     * وأندرويد يخنقُ من يُكثِرُ فيُهمِلُ تحديثاتِه — فيقفُ الشريطُ من كثرةِ ما تحرَّك.
     */
    @Volatile private var lastNotify = 0L

    private fun notifyOngoing(force: Boolean = false) {
        if (runners.isEmpty()) return
        val now = System.currentTimeMillis()
        if (!force && now - lastNotify < NOTIFY_INTERVAL_MS) return
        lastNotify = now
        runCatching {
            getSystemService(NotificationManager::class.java).notify(NOTIF_ONGOING, buildOngoing())
        }
    }

    /** إشعارُ النهايةِ لكلِّ قسمٍ على حدة، فلا يمحو انتهاءُ أحدِهما خبرَ الآخر. */
    private fun notifyTerminal(kind: Kind, title: String, text: String?) {
        val open = PendingIntent.getActivity(this, 0, Intent(this, MainActivity::class.java),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT)
        val n = NotificationCompat.Builder(this, CHANNEL)
            .setContentTitle("$title — ${label(kind)}")
            .setContentText(text)
            .setSmallIcon(android.R.drawable.stat_sys_download_done)
            .setContentIntent(open)
            .setAutoCancel(true)
            .setOnlyAlertOnce(true)
            .build()
        runCatching {
            getSystemService(NotificationManager::class.java).notify(NOTIF_TERMINAL + kind.ordinal, n)
        }
    }

    private fun cancelTerminal(kind: Kind) {
        runCatching {
            getSystemService(NotificationManager::class.java).cancel(NOTIF_TERMINAL + kind.ordinal)
        }
    }

    private fun startForegroundCompat(n: Notification) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            startForeground(NOTIF_ONGOING, n, ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC)
        } else {
            startForeground(NOTIF_ONGOING, n)
        }
    }

    private fun acquireWakeLock() {
        if (wakeLock?.isHeld == true) return
        val pm = getSystemService(Context.POWER_SERVICE) as PowerManager
        wakeLock = pm.newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "gmd:download").apply {
            setReferenceCounted(false)
            acquire(2 * 60 * 60 * 1000L)
        }
    }

    private fun releaseWakeLock() {
        runCatching { wakeLock?.takeIf { it.isHeld }?.release() }
        wakeLock = null
    }

    companion object {
        private const val CHANNEL = "gmd.downloads"
        private const val NOTIF_ONGOING = 1001
        private const val NOTIFY_INTERVAL_MS = 500L
        /** يُزادُ عليه ترتيبُ القسم، فلكلِّ قسمٍ إشعارُ نهايةٍ مستقلّ. */
        private const val NOTIF_TERMINAL = 1010
        const val ACTION_START = "com.gnutux.gmd.START"
        const val ACTION_STOP = "com.gnutux.gmd.STOP"
        private const val EXTRA_URL = "url"
        private const val EXTRA_IS_AUDIO = "isAudio"
        private const val EXTRA_KIND = "kind"
        private const val EXTRA_CHOICE = "choice"
        private const val EXTRA_TITLE = "title"
        private const val EXTRA_UPLOADER = "uploader"
        private const val EXTRA_DURATION = "duration"
        private const val EXTRA_THUMB = "thumb"
        private const val EXTRA_START = "sectionStart"
        private const val EXTRA_END = "sectionEnd"
        private const val EXTRA_PL_FOLDER = "playlistFolder"
        private const val EXTRA_PL_ITEMS = "playlistItems"
        private const val EXTRA_PL_TITLE = "playlistTitle"

        /** حالةُ كلِّ قسمٍ على حدة. */
        private val _progress = MutableStateFlow<Map<Kind, Progress>>(emptyMap())
        val progress: StateFlow<Map<Kind, Progress>> = _progress

        fun progressOf(kind: Kind): Progress = _progress.value[kind] ?: Progress.Idle

        private fun setProgress(kind: Kind, value: Progress) {
            _progress.value = _progress.value + (kind to value)
        }

        private val _jobs = MutableStateFlow<Map<Kind, JobInfo>>(emptyMap())
        val jobs: StateFlow<Map<Kind, JobInfo>> = _jobs

        fun reset(kind: Kind) {
            _progress.value = _progress.value - kind
            _jobs.value = _jobs.value - kind
        }

        internal fun forget(kind: Kind) { _jobs.value = _jobs.value - kind }

        internal fun store(kind: Kind, info: JobInfo) {
            _jobs.value = _jobs.value + (kind to info)
        }

        /**
         * [title] وما بعدَه بياناتُ المقطعِ كما جُلِبت في الواجهةِ قبلَ التنزيل.
         * تُمرَّرُ لتُحفَظَ في السجلّ: الخدمةُ لا تعرفُها ولا تُعيدُ جلبَها، فسؤالُ
         * yt-dlp مرّةً ثانيةً عمّا في اليدِ إهدارُ ثوانٍ وشبكة.
         */
        fun start(
            context: Context,
            url: String,
            isAudio: Boolean,
            choice: String,
            title: String? = null,
            uploader: String? = null,
            duration: String? = null,
            thumbnail: String? = null,
            sectionStart: Int = -1,
            sectionEnd: Int = -1,
            playlistFolder: String? = null,
            playlistItems: IntArray? = null,
            playlistTitle: String? = null,
        ) {
            val intent = Intent(context, DownloadService::class.java)
                .setAction(ACTION_START)
                .putExtra(EXTRA_URL, url)
                .putExtra(EXTRA_IS_AUDIO, isAudio)
                .putExtra(EXTRA_CHOICE, choice)
                .putExtra(EXTRA_TITLE, title)
                .putExtra(EXTRA_UPLOADER, uploader)
                .putExtra(EXTRA_DURATION, duration)
                .putExtra(EXTRA_THUMB, thumbnail)
                .putExtra(EXTRA_START, sectionStart)
                .putExtra(EXTRA_END, sectionEnd)
                .putExtra(EXTRA_PL_FOLDER, playlistFolder)
                .putExtra(EXTRA_PL_ITEMS, playlistItems)
                .putExtra(EXTRA_PL_TITLE, playlistTitle)
            androidx.core.content.ContextCompat.startForegroundService(context, intent)
        }

        fun stop(context: Context, kind: Kind) {
            context.startService(
                Intent(context, DownloadService::class.java)
                    .setAction(ACTION_STOP)
                    .putExtra(EXTRA_KIND, kind.name)
            )
        }
    }
}
