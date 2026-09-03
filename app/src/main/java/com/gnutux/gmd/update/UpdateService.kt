package com.gnutux.gmd.update

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
import androidx.core.app.NotificationCompat
import com.gnutux.gmd.MainActivity
import com.gnutux.gmd.R
import com.gnutux.gmd.data.LocalePrefs
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch
import java.io.File

/** حالةُ تنزيلِ حزمةِ التحديث. */
sealed interface UpdateDownload {
    data object Idle : UpdateDownload
    data class Running(val received: Long, val total: Long) : UpdateDownload
    data class Done(val file: File) : UpdateDownload
    data class Failed(val message: String) : UpdateDownload
}

/**
 * تنزيلُ حزمةِ التحديثِ في خدمةٍ مقدِّمة.
 *
 * كان التنزيلُ يجري في نطاقِ الـViewModel، وأندرويد يُجمّدُ العمليّةَ حين تغادر
 * الواجهةُ المقدّمةَ ثمّ يقتلُها عند الحاجة — فحزمةٌ تقاربُ 40 م.ب على شبكةٍ
 * متوسّطةٍ تموتُ في منتصفِها كلّما نظرَ المستخدمُ في تطبيقٍ آخر. والإشعارُ الدائمُ
 * هو الثمنُ الذي يفرضُه النظامُ مقابلَ بقاءِ العملِ حيّاً.
 *
 * والاستئنافُ من موضعِ الانقطاعِ يتكفّلُ به [Updater.download]: يحفظُ ما نزلَ في
 * ملفِّ `.part` ويطلبُ ما بعدَه بترويسةِ `Range`. فإن قُتِلت الخدمةُ أو انقطعت
 * الشبكةُ لم يُعَد التنزيلُ من أوّلِه، بل من حيثُ وقف — ولو بعدَ إغلاقِ التطبيق.
 */
class UpdateService : Service() {

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    @Volatile private var cancelRequested = false

    override fun onBind(intent: Intent?): IBinder? = null

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
                cancelRequested = true
                _state.value = UpdateDownload.Idle
                finish()
                return START_NOT_STICKY
            }
            ACTION_START -> Unit
            else -> { stopSelf(); return START_NOT_STICKY }
        }

        val name = intent.getStringExtra(EXTRA_NAME).orEmpty()
        val url = intent.getStringExtra(EXTRA_URL).orEmpty()
        val size = intent.getLongExtra(EXTRA_SIZE, 0L)
        if (name.isBlank() || url.isBlank()) { stopSelf(); return START_NOT_STICKY }

        cancelRequested = false
        startForegroundCompat(buildNotification(0, ongoing = true))

        scope.launch {
            _state.value = UpdateDownload.Running(0, size)
            var lastNotified = 0L
            Updater.download(
                context = applicationContext,
                asset = Updater.Asset(name, size, url),
                isCancelled = { cancelRequested },
                onProgress = { received, total ->
                    _state.value = UpdateDownload.Running(received, total)
                    // الخرجُ يأتي كلَّ 64 ك.ب، وتحديثُ الإشعارِ بهذا التواتر يُثقل
                    // النظامَ بلا فائدة: العينُ لا تُميّز أكثرَ من مرّةٍ في الثانية.
                    val now = System.currentTimeMillis()
                    if (now - lastNotified >= 1000) {
                        lastNotified = now
                        val pct = if (total > 0) (received * 100 / total).toInt() else 0
                        notify(buildNotification(pct, ongoing = true))
                    }
                },
            ).fold(
                onSuccess = { file ->
                    _state.value = UpdateDownload.Done(file)
                    notify(buildNotification(100, ongoing = false, done = true))
                },
                onFailure = { t ->
                    if (cancelRequested) {
                        _state.value = UpdateDownload.Idle
                    } else {
                        val msg = t.message ?: t::class.java.simpleName
                        _state.value = UpdateDownload.Failed(msg)
                        notify(buildNotification(0, ongoing = false, error = msg))
                    }
                },
            )
            finish()
        }
        return START_NOT_STICKY
    }

    private fun finish() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.N) stopForeground(STOP_FOREGROUND_DETACH)
        else @Suppress("DEPRECATION") stopForeground(false)
        stopSelf()
    }

    override fun onDestroy() {
        scope.cancel()
        super.onDestroy()
    }

    private fun createChannel() {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O) return
        val channel = NotificationChannel(CHANNEL, getString(R.string.notif_channel_update),
            NotificationManager.IMPORTANCE_LOW).apply { setShowBadge(false) }
        getSystemService(NotificationManager::class.java).createNotificationChannel(channel)
    }

    private fun buildNotification(
        percent: Int,
        ongoing: Boolean,
        done: Boolean = false,
        error: String? = null,
    ): Notification {
        val open = PendingIntent.getActivity(this, 0, Intent(this, MainActivity::class.java),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT)

        val title = when {
            error != null -> getString(R.string.update_failed)
            done -> getString(R.string.update_ready_to_install)
            else -> getString(R.string.update_downloading)
        }

        return NotificationCompat.Builder(this, CHANNEL)
            .setContentTitle(title)
            .setContentText(error)
            .setSmallIcon(android.R.drawable.stat_sys_download)
            .setContentIntent(open)
            .setOngoing(ongoing)
            .setOnlyAlertOnce(true)
            .apply {
                if (ongoing) {
                    setProgress(100, percent.coerceIn(0, 100), percent <= 0)
                    addAction(0, getString(R.string.cancel),
                        PendingIntent.getService(this@UpdateService, 1,
                            Intent(this@UpdateService, UpdateService::class.java).setAction(ACTION_STOP),
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

    companion object {
        private const val CHANNEL = "gmd.update"
        private const val NOTIF_ID = 1002
        const val ACTION_START = "com.gnutux.gmd.UPDATE_START"
        const val ACTION_STOP = "com.gnutux.gmd.UPDATE_STOP"
        private const val EXTRA_NAME = "name"
        private const val EXTRA_URL = "url"
        private const val EXTRA_SIZE = "size"

        private val _state = MutableStateFlow<UpdateDownload>(UpdateDownload.Idle)
        val state: StateFlow<UpdateDownload> = _state

        fun reset() { _state.value = UpdateDownload.Idle }

        fun start(context: Context, asset: Updater.Asset) {
            val intent = Intent(context, UpdateService::class.java)
                .setAction(ACTION_START)
                .putExtra(EXTRA_NAME, asset.name)
                .putExtra(EXTRA_URL, asset.url)
                .putExtra(EXTRA_SIZE, asset.size)
            androidx.core.content.ContextCompat.startForegroundService(context, intent)
        }

        fun stop(context: Context) {
            context.startService(
                Intent(context, UpdateService::class.java).setAction(ACTION_STOP)
            )
        }
    }
}
