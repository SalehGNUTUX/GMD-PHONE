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
    data class Done(val displayName: String, val relativePath: String) : Progress
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

    override fun onBind(intent: Intent?): IBinder? = null

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

        startForegroundCompat(buildNotification(0, getString(R.string.notif_downloading), ongoing = true))
        acquireWakeLock()

        val processId = System.currentTimeMillis().toString()
        currentProcessId = processId

        val job: Job = if (isAudio) {
            Job.Audio(url, runCatching { AudioFormat.valueOf(choice) }.getOrDefault(AudioFormat.MP3))
        } else {
            Job.Video(url, runCatching { Quality.valueOf(choice) }.getOrDefault(Quality.P1080))
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
                            _progress.value = Progress.Done(saved.displayName, saved.relativePath)
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
        _progress.value = Progress.Failed(msg)
        notify(buildNotification(0, getString(R.string.notif_failed), ongoing = false, text = msg))
    }

    private fun stopWork() {
        currentProcessId?.let { Downloader.cancel(it) }
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

        private val _progress = MutableStateFlow<Progress>(Progress.Idle)
        val progress: StateFlow<Progress> = _progress

        fun reset() { _progress.value = Progress.Idle }

        fun start(context: Context, url: String, isAudio: Boolean, choice: String) {
            val intent = Intent(context, DownloadService::class.java)
                .setAction(ACTION_START)
                .putExtra(EXTRA_URL, url)
                .putExtra(EXTRA_IS_AUDIO, isAudio)
                .putExtra(EXTRA_CHOICE, choice)
            androidx.core.content.ContextCompat.startForegroundService(context, intent)
        }

        fun stop(context: Context) {
            context.startService(Intent(context, DownloadService::class.java).setAction(ACTION_STOP))
        }
    }
}
