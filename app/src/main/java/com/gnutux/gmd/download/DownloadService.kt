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

/** ما تعرضه الواجهة عن التنزيل الجاري. */
sealed interface Progress {
    data object Idle : Progress
    data class Running(val percent: Float, val etaSeconds: Long, val line: String) : Progress
    /** [uri] عنوانُ المدخلِ في معرضِ الوسائط — بدونِه لا فتحَ ولا مشاركةَ للناتج. */
    data class Done(val displayName: String, val relativePath: String, val uri: String) : Progress
    data class Failed(val message: String) : Progress
}

/**
 * خدمة مُقدِّمة تُنفّذ التنزيل.
 *
 * ليست تحسيناً بل شرط عمل: أندرويد يجمّد العمليّة حين تغادر الواجهة المقدّمة،
 * فتنزيل مقطعٍ طويل يموت في منتصفه لو جرى في نطاق شاشة. الإشعار الدائم هو الثمن
 * الذي يفرضه النظام مقابل بقاء العمل حيّاً، وقفل اليقظة يمنع نوم المعالج معه.
 */
class DownloadService : Service() {

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private var wakeLock: PowerManager.WakeLock? = null
    private var currentProcessId: String? = null

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
    )

    private var attempt: Attempt? = null

    /** يمنعُ تسجيلَ المحاولةِ مرّتَين: الإلغاءُ يُنهي المهمّةَ وقد سُجِّلت. */
    private val recorded = java.util.concurrent.atomic.AtomicBoolean(false)

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
            ACTION_STOP -> { stopWork(); return START_NOT_STICKY }
            ACTION_START -> Unit
            else -> { stopSelf(); return START_NOT_STICKY }
        }

        val url = intent.getStringExtra(EXTRA_URL).orEmpty()
        val isAudio = intent.getBooleanExtra(EXTRA_IS_AUDIO, false)
        val choice = intent.getStringExtra(EXTRA_CHOICE).orEmpty()
        if (url.isBlank()) { stopSelf(); return START_NOT_STICKY }

        // تُلتقَط الآن لأنّ `intent` لا يبقى متاحاً داخل المهمّة المتزامنة
        attempt = Attempt(
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
        )

        startForegroundCompat(buildNotification(0, getString(R.string.notif_downloading), ongoing = true))
        acquireWakeLock()

        val processId = System.currentTimeMillis().toString()
        currentProcessId = processId
        recorded.set(false)

        val section = attempt?.section
        val job: Job = if (isAudio) {
            Job.Audio(url,
                runCatching { AudioFormat.valueOf(choice) }.getOrDefault(AudioFormat.MP3), section)
        } else {
            Job.Video(url,
                runCatching { Quality.valueOf(choice) }.getOrDefault(Quality.P1080), section)
        }

        scope.launch {
            _progress.value = Progress.Running(0f, -1, "")
            val result = Downloader.run(applicationContext, job, processId) { percent, eta, line ->
                _progress.value = Progress.Running(percent, eta, line)
                notify(buildNotification(percent.toInt(), getString(R.string.notif_downloading), ongoing = true))
            }

            result.fold(
                onSuccess = { file ->
                    MediaStoreSaver.save(applicationContext, file, isAudio).fold(
                        onSuccess = { saved ->
                            record(Outcome.SUCCESS, null, saved.uri.toString(),
                                saved.displayName, saved.relativePath)
                            _progress.value =
                                Progress.Done(saved.displayName, saved.relativePath, saved.uri.toString())
                            notify(buildNotification(100, getString(R.string.notif_done), ongoing = false,
                                text = saved.displayName))
                        },
                        onFailure = { fail(it) },
                    )
                },
                onFailure = { fail(it) },
            )
            finish()
        }
        return START_NOT_STICKY
    }

    private fun fail(t: Throwable) {
        val msg = t.message ?: t::class.java.simpleName
        record(Outcome.FAILED, msg, null, null, null)
        _progress.value = Progress.Failed(msg)
        notify(buildNotification(0, getString(R.string.notif_failed), ongoing = false, text = msg))
    }

    /**
     * يكتبُ المحاولةَ في السجلِّ مرّةً واحدة. يُستدعى من ثلاثةِ مسالكَ — نجاحٌ وفشلٌ
     * وإلغاء — وقد يتلاقى مسلكانِ حينَ يُلغي المستخدمُ تنزيلاً في لحظةِ انتهائه،
     * فالحارسُ الذرّيُّ يمنعُ مدخلَين لمحاولةٍ واحدة.
     */
    private fun record(
        outcome: Outcome,
        error: String?,
        uri: String?,
        name: String?,
        path: String?,
    ) {
        val a = attempt ?: return
        if (!recorded.compareAndSet(false, true)) return
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
                ),
            )
        }
    }

    private fun stopWork() {
        currentProcessId?.let { Downloader.cancel(it) }
        record(Outcome.CANCELLED, null, null, null, null)
        _progress.value = Progress.Idle
        finish()
    }

    private fun finish() {
        releaseWakeLock()
        // الإشعار النهائيّ يبقى معروضاً بعد رفع صفة "المقدِّمة" عن الخدمة
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.N) stopForeground(STOP_FOREGROUND_DETACH)
        else @Suppress("DEPRECATION") stopForeground(false)
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

    private fun buildNotification(percent: Int, title: String, ongoing: Boolean, text: String? = null): Notification {
        val open = PendingIntent.getActivity(this, 0, Intent(this, MainActivity::class.java),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT)

        return NotificationCompat.Builder(this, CHANNEL)
            .setContentTitle(title)
            .setContentText(text)
            .setSmallIcon(android.R.drawable.stat_sys_download)
            .setContentIntent(open)
            .setOngoing(ongoing)
            .setOnlyAlertOnce(true)
            .apply {
                if (ongoing) {
                    setProgress(100, percent.coerceIn(0, 100), percent <= 0)
                    addAction(0, getString(R.string.notif_stop),
                        PendingIntent.getService(this@DownloadService, 1,
                            Intent(this@DownloadService, DownloadService::class.java).setAction(ACTION_STOP),
                            PendingIntent.FLAG_IMMUTABLE))
                }
            }
            .build()
    }

    private fun notify(n: Notification) {
        runCatching { getSystemService(NotificationManager::class.java).notify(NOTIF_ID, n) }
    }

    private fun startForegroundCompat(n: Notification) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            startForeground(NOTIF_ID, n, ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC)
        } else {
            startForeground(NOTIF_ID, n)
        }
    }

    private fun acquireWakeLock() {
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
        private const val NOTIF_ID = 1001
        const val ACTION_START = "com.gnutux.gmd.START"
        const val ACTION_STOP = "com.gnutux.gmd.STOP"
        private const val EXTRA_URL = "url"
        private const val EXTRA_IS_AUDIO = "isAudio"
        private const val EXTRA_CHOICE = "choice"
        private const val EXTRA_TITLE = "title"
        private const val EXTRA_UPLOADER = "uploader"
        private const val EXTRA_DURATION = "duration"
        private const val EXTRA_THUMB = "thumb"
        private const val EXTRA_START = "sectionStart"
        private const val EXTRA_END = "sectionEnd"

        private val _progress = MutableStateFlow<Progress>(Progress.Idle)
        val progress: StateFlow<Progress> = _progress

        fun reset() { _progress.value = Progress.Idle }

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
            androidx.core.content.ContextCompat.startForegroundService(context, intent)
        }

        fun stop(context: Context) {
            context.startService(Intent(context, DownloadService::class.java).setAction(ACTION_STOP))
        }
    }
}
