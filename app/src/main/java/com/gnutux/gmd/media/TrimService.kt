package com.gnutux.gmd.media

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.net.Uri
import android.os.Build
import android.os.IBinder
import android.os.PowerManager
import androidx.core.app.NotificationCompat
import com.gnutux.gmd.MainActivity
import com.gnutux.gmd.R
import com.gnutux.gmd.data.LocalePrefs
import com.gnutux.gmd.download.Section
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch

/** ما تعرضُه الواجهةُ عن الاقتصاصِ الجاري. */
sealed interface TrimProgress {
    data object Idle : TrimProgress
    /** نسخُ الملفِّ إلى مجلَّدِ العملِ حينَ لا مسارَ مباشرَ له. */
    data class Copying(val percent: Float) : TrimProgress
    data class Cutting(val percent: Float) : TrimProgress
    data object Saving : TrimProgress
    data class Done(val displayName: String, val relativePath: String, val uri: String) : TrimProgress
    data class Failed(val message: String) : TrimProgress
}

/**
 * خدمةٌ مقدِّمةٌ تُنفّذُ اقتصاصَ ملفٍّ من الجهاز.
 *
 * القصُّ بنسخِ التيّاراتِ سريعٌ في الغالب، لكنّ ملفّاً كبيراً بلا مسارٍ مباشرٍ
 * يُنسَخُ أوّلاً — وذلك قد يطولُ دقائق. وأندرويد يجمّدُ العمليّةَ حينَ تغادرُ
 * الواجهةُ المقدّمة، فما يجري في نطاقِ شاشةٍ يموتُ بمجرَّدِ أن ينظرَ المستخدمُ في
 * تطبيقٍ آخر: الإشعارُ الدائمُ هو ثمنُ بقاءِ العملِ حيّاً، وقفلُ اليقظةِ معه.
 */
class TrimService : Service() {

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private var wakeLock: PowerManager.WakeLock? = null

    override fun onBind(intent: Intent?): IBinder? = null

    /** الإشعاراتُ بلغةِ الواجهةِ المختارة، لا بلغةِ النظامِ وحدَها. */
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

        val uri = intent.getStringExtra(EXTRA_URI)?.let(Uri::parse)
        val name = intent.getStringExtra(EXTRA_NAME).orEmpty()
        val section = Section(
            intent.getIntExtra(EXTRA_START, -1),
            intent.getIntExtra(EXTRA_END, -1),
        )
        if (uri == null || !section.valid) { stopSelf(); return START_NOT_STICKY }

        val source = Trimmer.Source(
            uri = uri,
            displayName = name.ifBlank { "clip" },
            isAudio = intent.getBooleanExtra(EXTRA_IS_AUDIO, false),
            durationMs = intent.getLongExtra(EXTRA_DURATION, 0L),
            sizeBytes = intent.getLongExtra(EXTRA_SIZE, 0L),
        )

        startForegroundCompat(buildNotification(0, getString(R.string.notif_trimming), ongoing = true))
        acquireWakeLock()

        scope.launch {
            _progress.value = TrimProgress.Cutting(0f)
            val result = Trimmer.trim(applicationContext, source, section) { phase ->
                _progress.value = when (phase) {
                    is Trimmer.Phase.Copying -> TrimProgress.Copying(phase.percent)
                    is Trimmer.Phase.Cutting -> TrimProgress.Cutting(phase.percent)
                    Trimmer.Phase.Saving -> TrimProgress.Saving
                }
                val percent = when (phase) {
                    // النسخُ نصفُ الطريقِ والقصُّ نصفُها، فالشريطُ لا يرتدُّ إلى
                    // الصفرِ حينَ تنتقلُ المرحلة
                    is Trimmer.Phase.Copying -> phase.percent / 2f
                    is Trimmer.Phase.Cutting -> 50f + phase.percent / 2f
                    Trimmer.Phase.Saving -> 100f
                }
                notify(buildNotification(percent.toInt(), getString(R.string.notif_trimming), ongoing = true))
            }

            result.fold(
                onSuccess = { saved ->
                    _progress.value = TrimProgress.Done(
                        saved.displayName, saved.relativePath, saved.uri.toString(),
                    )
                    notify(buildNotification(100, getString(R.string.notif_trim_done),
                        ongoing = false, text = saved.displayName))
                },
                onFailure = { t ->
                    val msg = t.message ?: t::class.java.simpleName
                    _progress.value = TrimProgress.Failed(msg)
                    notify(buildNotification(0, getString(R.string.notif_trim_failed),
                        ongoing = false, text = msg))
                },
            )
            finish()
        }
        return START_NOT_STICKY
    }

    private fun stopWork() {
        Trimmer.cancel()
        _progress.value = TrimProgress.Idle
        finish()
    }

    private fun finish() {
        releaseWakeLock()
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.N) stopForeground(STOP_FOREGROUND_DETACH)
        else @Suppress("DEPRECATION") stopForeground(false)
        stopSelf()
    }

    override fun onDestroy() {
        releaseWakeLock()
        scope.cancel()
        super.onDestroy()
    }

    private fun createChannel() {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O) return
        val channel = NotificationChannel(CHANNEL, getString(R.string.notif_channel_trim),
            NotificationManager.IMPORTANCE_LOW).apply { setShowBadge(false) }
        getSystemService(NotificationManager::class.java).createNotificationChannel(channel)
    }

    private fun buildNotification(
        percent: Int,
        title: String,
        ongoing: Boolean,
        text: String? = null,
    ): Notification {
        val open = PendingIntent.getActivity(this, 0, Intent(this, MainActivity::class.java),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT)

        return NotificationCompat.Builder(this, CHANNEL)
            .setContentTitle(title)
            .setContentText(text)
            .setSmallIcon(android.R.drawable.ic_menu_crop)
            .setContentIntent(open)
            .setOngoing(ongoing)
            .setOnlyAlertOnce(true)
            .apply {
                if (ongoing) {
                    setProgress(100, percent.coerceIn(0, 100), percent <= 0)
                    addAction(0, getString(R.string.notif_stop),
                        PendingIntent.getService(this@TrimService, 1,
                            Intent(this@TrimService, TrimService::class.java).setAction(ACTION_STOP),
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
        wakeLock = pm.newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "gmd:trim").apply {
            setReferenceCounted(false)
            acquire(60 * 60 * 1000L)
        }
    }

    private fun releaseWakeLock() {
        runCatching { wakeLock?.takeIf { it.isHeld }?.release() }
        wakeLock = null
    }

    companion object {
        private const val CHANNEL = "gmd.trim"
        /** يخالفُ معرِّفَ إشعارِ التنزيلِ كي لا يطمسَ أحدُهما الآخر. */
        private const val NOTIF_ID = 1003
        const val ACTION_START = "com.gnutux.gmd.TRIM_START"
        const val ACTION_STOP = "com.gnutux.gmd.TRIM_STOP"
        private const val EXTRA_URI = "uri"
        private const val EXTRA_NAME = "name"
        private const val EXTRA_IS_AUDIO = "isAudio"
        private const val EXTRA_DURATION = "durationMs"
        private const val EXTRA_SIZE = "sizeBytes"
        private const val EXTRA_START = "start"
        private const val EXTRA_END = "end"

        private val _progress = MutableStateFlow<TrimProgress>(TrimProgress.Idle)
        val progress: StateFlow<TrimProgress> = _progress

        fun reset() { _progress.value = TrimProgress.Idle }

        fun start(context: Context, source: Trimmer.Source, section: Section) {
            val intent = Intent(context, TrimService::class.java)
                .setAction(ACTION_START)
                .putExtra(EXTRA_URI, source.uri.toString())
                .putExtra(EXTRA_NAME, source.displayName)
                .putExtra(EXTRA_IS_AUDIO, source.isAudio)
                .putExtra(EXTRA_DURATION, source.durationMs)
                .putExtra(EXTRA_SIZE, source.sizeBytes)
                .putExtra(EXTRA_START, section.startSec)
                .putExtra(EXTRA_END, section.endSec)
            androidx.core.content.ContextCompat.startForegroundService(context, intent)
        }

        fun stop(context: Context) {
            context.startService(Intent(context, TrimService::class.java).setAction(ACTION_STOP))
        }
    }
}
