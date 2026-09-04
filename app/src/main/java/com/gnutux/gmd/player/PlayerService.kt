package com.gnutux.gmd.player

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.media.AudioAttributes
import android.media.AudioFocusRequest
import android.media.AudioManager
import android.media.MediaPlayer
import android.net.Uri
import android.os.Build
import android.os.IBinder
import android.os.PowerManager
import androidx.core.app.NotificationCompat
import com.gnutux.gmd.MainActivity
import com.gnutux.gmd.R
import com.gnutux.gmd.data.LocalePrefs
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch

/** حالةُ المشغّلِ كما تراها الواجهة. */
data class PlayerState(
    val queue: List<Track> = emptyList(),
    val index: Int = 0,
    val playing: Boolean = false,
    val positionMs: Long = 0L,
    val durationMs: Long = 0L,
    /** صفٌّ مستعادٌ من جلسةٍ سابقةٍ لم يبدأ تشغيلُه بعد. */
    val resumed: Boolean = false,
) {
    val current: Track? get() = queue.getOrNull(index)
    val hasNext: Boolean get() = index + 1 < queue.size
    val hasPrevious: Boolean get() = index > 0
}

/**
 * مشغّلُ الصوتِ الداخليّ.
 *
 * خدمةٌ مقدِّمةٌ لا نطاقُ شاشة: الاستماعُ يُكمِلُ حينَ يغادرُ المستخدمُ الشاشةَ أو
 * التطبيقَ كلَّه، وهو أصلُ الاستماع. والمشغّلُ من إطارِ أندرويد نفسِه
 * (`MediaPlayer`) لا من مكتبةٍ خارجيّة: المادّةُ ملفّاتٌ محلّيّةٌ لا بثٌّ متكيّف،
 * وإدخالُ مكتبةِ تشغيلٍ كاملةٍ لأجلِ ذلك ثمنٌ في حجمِ الحزمةِ بلا مقابل.
 *
 * وقفلُ اليقظةِ يتولّاه `setWakeMode` فلا يُوقِفُ المعالجُ النائمُ الصوتَ في منتصفِه.
 */
class PlayerService : Service() {

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main)
    private var player: MediaPlayer? = null
    private var ticker: Job? = null
    private var focusRequest: AudioFocusRequest? = null

    /** إيقافٌ سببُه فقدانُ التركيزِ الصوتيِّ مؤقّتاً؛ يُستأنَفُ عندَ عودتِه. */
    private var pausedByFocus = false

    override fun onBind(intent: Intent?): IBinder? = null

    override fun attachBaseContext(base: Context) {
        super.attachBaseContext(LocalePrefs.wrap(base))
    }

    override fun onCreate() {
        super.onCreate()
        createChannel()
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (intent?.action == null) { stopSelf(); return START_NOT_STICKY }
        // أندرويد يُسقِطُ التطبيقَ إن لم يُستدعَ startForeground بعدَ
        // startForegroundService، ولو كانَ الأمرُ لا يُشغِّلُ شيئاً — أمرٌ يصلُ إلى
        // خدمةٍ ماتت مثلاً. فيُستدعى أوّلاً ثمّ يُنفَّذُ الأمر.
        startForegroundCompat(buildNotification())
        when (intent.action) {
            ACTION_PLAY -> {
                val uris = intent.getStringArrayExtra(EXTRA_URIS).orEmpty()
                val titles = intent.getStringArrayExtra(EXTRA_TITLES).orEmpty()
                val durations = intent.getLongArrayExtra(EXTRA_DURATIONS) ?: LongArray(0)
                val queue = uris.mapIndexed { i, u ->
                    Track(u, titles.getOrElse(i) { "" }, durations.getOrElse(i) { 0L })
                }
                val index = intent.getIntExtra(EXTRA_INDEX, 0).coerceIn(0, maxOf(0, queue.size - 1))
                if (queue.isEmpty()) { stopSelf(); return START_NOT_STICKY }
                _state.value = PlayerState(queue, index)
                open(index, autoPlay = true)
            }
            ACTION_TOGGLE -> toggle()
            ACTION_NEXT -> skip(+1)
            ACTION_PREVIOUS -> skip(-1)
            ACTION_SEEK -> seekTo(intent.getLongExtra(EXTRA_POSITION, 0L))
            ACTION_JUMP -> {
                val to = intent.getIntExtra(EXTRA_INDEX, 0)
                if (to in _state.value.queue.indices) open(to, autoPlay = true)
            }
            ACTION_STOP -> { stopPlayback(remember = false); return START_NOT_STICKY }
            else -> { stopSelf(); return START_NOT_STICKY }
        }
        return START_NOT_STICKY
    }

    // ── التشغيل ───────────────────────────────────────────────────────────────

    /**
     * يفتحُ المقطعَ ويستأنفُه من موضعِه المحفوظ.
     *
     * والتحضيرُ غيرُ متزامنٍ (`prepareAsync`): ملفٌّ في المعرضِ قد يمرُّ بطبقةِ FUSE،
     * والتحضيرُ المتزامنُ يُجمِّدُ خيطَ الواجهةِ حتّى ينتهي.
     */
    private fun open(index: Int, autoPlay: Boolean, startAt: Long? = null) {
        val track = _state.value.queue.getOrNull(index) ?: return
        rememberPosition()
        release()

        _state.value = _state.value.copy(
            index = index, playing = false, positionMs = 0L,
            durationMs = track.durationMs, resumed = false,
        )

        val resumeAt = startAt ?: PlaybackStore.positionOf(this, track.uri, track.durationMs)
        player = MediaPlayer().apply {
            setAudioAttributes(
                AudioAttributes.Builder()
                    .setUsage(AudioAttributes.USAGE_MEDIA)
                    .setContentType(AudioAttributes.CONTENT_TYPE_MUSIC)
                    .build()
            )
            setWakeMode(applicationContext, PowerManager.PARTIAL_WAKE_LOCK)
            setOnPreparedListener { mp ->
                _state.value = _state.value.copy(durationMs = mp.duration.toLong())
                if (resumeAt in 1 until mp.duration.toLong()) mp.seekTo(resumeAt.toInt())
                if (autoPlay) startPlayback() else updateNotification()
            }
            // المقطعُ انتهى: يُمحى موضعُه فلا يُستأنَفُ من آخرِه، ثمّ يُنتقَلُ إلى
            // ما بعدَه — وهو ما يجعلُ قائمةً كاملةً تُسمَعُ بلا تدخُّل
            setOnCompletionListener {
                PlaybackStore.clearPosition(applicationContext, track.uri)
                if (_state.value.hasNext) open(_state.value.index + 1, autoPlay = true)
                else stopPlayback(remember = false)
            }
            setOnErrorListener { _, what, extra ->
                _state.value = _state.value.copy(playing = false)
                stopTicker()
                updateNotification()
                android.util.Log.e("GMD", "player error $what/$extra")
                true
            }
            runCatching {
                setDataSource(applicationContext, Uri.parse(track.uri))
                prepareAsync()
            }.onFailure {
                _state.value = _state.value.copy(playing = false)
                updateNotification()
            }
        }
        startForegroundCompat(buildNotification())
    }

    private fun startPlayback() {
        if (!requestFocus()) return
        player?.start()
        _state.value = _state.value.copy(playing = true, resumed = false)
        startTicker()
        startForegroundCompat(buildNotification())
    }

    private fun pausePlayback() {
        runCatching { player?.pause() }
        rememberPosition()
        _state.value = _state.value.copy(playing = false)
        stopTicker()
        updateNotification()
        // تُرفَعُ صفةُ المقدِّمةِ ويبقى الإشعار: التشغيلُ متوقّفٌ فلا يستحقُّ أولويّةَ
        // ما يعمل، والإشعارُ يبقى ليُستأنَفَ منه
        detachForeground()
    }

    private fun toggle() {
        val st = _state.value
        when {
            st.playing -> pausePlayback()
            // صفٌّ مستعادٌ من جلسةٍ سابقة: لم يُفتَح بعد
            player == null && st.current != null -> open(st.index, autoPlay = true)
            else -> startPlayback()
        }
    }

    private fun skip(delta: Int) {
        val st = _state.value
        // الرجوعُ في أوّلِ ثوانٍ يعودُ إلى ما قبلَه، وبعدَها يُعيدُ المقطعَ نفسَه —
        // عُرفٌ يعرفُه كلُّ مستمع. ويُفحَصُ قبلَ حدودِ الصفِّ وإلّا لم يفعل الزرُّ
        // شيئاً في أوّلِ مقطعٍ وقد مضى نصفُه.
        if (delta < 0 && (runCatching { player?.currentPosition }.getOrNull() ?: 0) > 5_000) {
            seekTo(0); return
        }
        val to = st.index + delta
        if (to !in st.queue.indices) return
        open(to, autoPlay = true)
    }

    private fun seekTo(ms: Long) {
        val mp = player ?: return
        runCatching { mp.seekTo(ms.coerceAtLeast(0L).toInt()) }
        _state.value = _state.value.copy(positionMs = ms)
    }

    private fun stopPlayback(remember: Boolean) {
        if (remember) rememberPosition() else _state.value.current?.let {
            PlaybackStore.clearPosition(this, it.uri)
        }
        release()
        stopTicker()
        abandonFocus()
        _state.value = PlayerState()
        PlaybackStore.clearQueue(this)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.N) stopForeground(STOP_FOREGROUND_REMOVE)
        else @Suppress("DEPRECATION") stopForeground(true)
        stopSelf()
    }

    private fun release() {
        runCatching { player?.release() }
        player = null
    }

    /** يكتبُ موضعَ المقطعِ الجاري وصفَّه، ليُستأنَفَ لاحقاً. */
    private fun rememberPosition() {
        val st = _state.value
        val track = st.current ?: return
        val pos = runCatching { player?.currentPosition?.toLong() }.getOrNull() ?: st.positionMs
        if (pos > 0) PlaybackStore.savePosition(this, track.uri, pos)
        PlaybackStore.saveQueue(this, st.queue, st.index, pos)
    }

    private fun startTicker() {
        stopTicker()
        ticker = scope.launch {
            while (true) {
                val mp = player ?: break
                val pos = runCatching { mp.currentPosition.toLong() }.getOrNull() ?: break
                _state.value = _state.value.copy(positionMs = pos)
                // كتابةُ الموضعِ كلَّ خمسِ ثوانٍ: قتلُ العمليّةِ فجأةً لا يُضيعُ أكثرَ
                // من ذلك من مكانِ الاستماع
                if (pos / 5000 != lastSaved / 5000) {
                    lastSaved = pos
                    _state.value.current?.let { PlaybackStore.savePosition(this@PlayerService, it.uri, pos) }
                }
                delay(500)
            }
        }
    }

    private var lastSaved = 0L

    private fun stopTicker() {
        ticker?.cancel()
        ticker = null
    }

    // ── التركيزُ الصوتيّ ──────────────────────────────────────────────────────
    private val focusListener = AudioManager.OnAudioFocusChangeListener { change ->
        when (change) {
            AudioManager.AUDIOFOCUS_LOSS -> pausePlayback()
            AudioManager.AUDIOFOCUS_LOSS_TRANSIENT -> {
                if (_state.value.playing) { pausedByFocus = true; pausePlayback() }
            }
            AudioManager.AUDIOFOCUS_LOSS_TRANSIENT_CAN_DUCK ->
                runCatching { player?.setVolume(0.3f, 0.3f) }
            AudioManager.AUDIOFOCUS_GAIN -> {
                runCatching { player?.setVolume(1f, 1f) }
                if (pausedByFocus) { pausedByFocus = false; startPlayback() }
            }
        }
    }

    /** بلا تركيزٍ لا يُشغَّلُ شيء: مكالمةٌ أو تطبيقٌ آخرُ أولى بالصوتِ من مقطعِنا. */
    private fun requestFocus(): Boolean {
        val am = getSystemService(Context.AUDIO_SERVICE) as AudioManager
        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val request = AudioFocusRequest.Builder(AudioManager.AUDIOFOCUS_GAIN)
                .setAudioAttributes(
                    AudioAttributes.Builder()
                        .setUsage(AudioAttributes.USAGE_MEDIA)
                        .setContentType(AudioAttributes.CONTENT_TYPE_MUSIC)
                        .build()
                )
                .setOnAudioFocusChangeListener(focusListener)
                .build()
            focusRequest = request
            am.requestAudioFocus(request) == AudioManager.AUDIOFOCUS_REQUEST_GRANTED
        } else {
            @Suppress("DEPRECATION")
            am.requestAudioFocus(
                focusListener, AudioManager.STREAM_MUSIC, AudioManager.AUDIOFOCUS_GAIN,
            ) == AudioManager.AUDIOFOCUS_REQUEST_GRANTED
        }
    }

    private fun abandonFocus() {
        val am = getSystemService(Context.AUDIO_SERVICE) as AudioManager
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            focusRequest?.let { am.abandonAudioFocusRequest(it) }
            focusRequest = null
        } else {
            @Suppress("DEPRECATION") am.abandonAudioFocus(focusListener)
        }
    }

    override fun onDestroy() {
        rememberPosition()
        release()
        stopTicker()
        abandonFocus()
        scope.cancel()
        super.onDestroy()
    }

    // ── الإشعار ───────────────────────────────────────────────────────────────
    private fun createChannel() {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O) return
        val channel = NotificationChannel(CHANNEL, getString(R.string.notif_channel_player),
            NotificationManager.IMPORTANCE_LOW).apply { setShowBadge(false) }
        getSystemService(NotificationManager::class.java).createNotificationChannel(channel)
    }

    private fun action(name: String, icon: Int, label: Int, code: Int) =
        NotificationCompat.Action(
            icon, getString(label),
            PendingIntent.getService(this, code,
                Intent(this, PlayerService::class.java).setAction(name),
                PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT),
        )

    private fun buildNotification(): Notification {
        val st = _state.value
        val open = PendingIntent.getActivity(this, 0, Intent(this, MainActivity::class.java),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT)

        return NotificationCompat.Builder(this, CHANNEL)
            .setContentTitle(st.current?.title?.ifBlank { getString(R.string.player_title) }
                ?: getString(R.string.player_title))
            .setContentText(
                if (st.queue.size > 1)
                    getString(R.string.phase_item, st.index + 1, st.queue.size)
                else null
            )
            .setSmallIcon(android.R.drawable.ic_media_play)
            .setContentIntent(open)
            .setOngoing(st.playing)
            .setOnlyAlertOnce(true)
            .setShowWhen(false)
            .apply {
                if (st.hasPrevious) addAction(action(ACTION_PREVIOUS,
                    android.R.drawable.ic_media_previous, R.string.player_previous, 10))
                addAction(action(ACTION_TOGGLE,
                    if (st.playing) android.R.drawable.ic_media_pause
                    else android.R.drawable.ic_media_play,
                    if (st.playing) R.string.player_pause else R.string.player_play, 11))
                if (st.hasNext) addAction(action(ACTION_NEXT,
                    android.R.drawable.ic_media_next, R.string.player_next, 12))
                addAction(action(ACTION_STOP,
                    android.R.drawable.ic_menu_close_clear_cancel, R.string.player_stop, 13))
            }
            .build()
    }

    private fun updateNotification() {
        runCatching {
            getSystemService(NotificationManager::class.java).notify(NOTIF_ID, buildNotification())
        }
    }

    private fun startForegroundCompat(n: Notification) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            startForeground(NOTIF_ID, n, ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PLAYBACK)
        } else {
            startForeground(NOTIF_ID, n)
        }
    }

    private fun detachForeground() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.N) stopForeground(STOP_FOREGROUND_DETACH)
        else @Suppress("DEPRECATION") stopForeground(false)
    }

    companion object {
        private const val CHANNEL = "gmd.player"
        private const val NOTIF_ID = 1005
        const val ACTION_PLAY = "com.gnutux.gmd.PLAY"
        const val ACTION_TOGGLE = "com.gnutux.gmd.TOGGLE"
        const val ACTION_NEXT = "com.gnutux.gmd.NEXT"
        const val ACTION_PREVIOUS = "com.gnutux.gmd.PREVIOUS"
        const val ACTION_SEEK = "com.gnutux.gmd.SEEK"
        const val ACTION_JUMP = "com.gnutux.gmd.JUMP"
        const val ACTION_STOP = "com.gnutux.gmd.PLAYER_STOP"
        private const val EXTRA_URIS = "uris"
        private const val EXTRA_TITLES = "titles"
        private const val EXTRA_DURATIONS = "durations"
        private const val EXTRA_INDEX = "index"
        private const val EXTRA_POSITION = "position"

        private val _state = MutableStateFlow(PlayerState())
        val state: StateFlow<PlayerState> = _state

        /**
         * يستعيدُ آخرَ صفٍّ استمعَ إليه صاحبُه، موقوفاً عندَ موضعِه.
         *
         * فيجدُ المشغّلَ حيثُ تركَه بعدَ إغلاقِ التطبيقِ لا فارغاً، ونقرةٌ واحدةٌ
         * تُكمِلُ ما بدأ. ولا يُفتَحُ الملفُّ ولا تُستدعى الخدمةُ حتّى يُطلَبَ التشغيل.
         */
        fun restore(context: Context) {
            if (_state.value.queue.isNotEmpty()) return
            val (queue, index, position) = PlaybackStore.loadQueue(context)
            if (queue.isEmpty()) return
            _state.value = PlayerState(
                queue = queue, index = index, playing = false,
                positionMs = position, durationMs = queue.getOrNull(index)?.durationMs ?: 0L,
                resumed = true,
            )
        }

        fun play(context: Context, queue: List<Track>, index: Int) {
            if (queue.isEmpty()) return
            val intent = Intent(context, PlayerService::class.java)
                .setAction(ACTION_PLAY)
                .putExtra(EXTRA_URIS, queue.map { it.uri }.toTypedArray())
                .putExtra(EXTRA_TITLES, queue.map { it.title }.toTypedArray())
                .putExtra(EXTRA_DURATIONS, queue.map { it.durationMs }.toLongArray())
                .putExtra(EXTRA_INDEX, index)
            androidx.core.content.ContextCompat.startForegroundService(context, intent)
        }

        private fun command(context: Context, action: String, extras: Intent.() -> Unit = {}) {
            val intent = Intent(context, PlayerService::class.java).setAction(action).apply(extras)
            androidx.core.content.ContextCompat.startForegroundService(context, intent)
        }

        fun toggle(context: Context) = command(context, ACTION_TOGGLE)
        fun next(context: Context) = command(context, ACTION_NEXT)
        fun previous(context: Context) = command(context, ACTION_PREVIOUS)
        fun stop(context: Context) = command(context, ACTION_STOP)
        fun seek(context: Context, ms: Long) =
            command(context, ACTION_SEEK) { putExtra(EXTRA_POSITION, ms) }
        fun jump(context: Context, index: Int) =
            command(context, ACTION_JUMP) { putExtra(EXTRA_INDEX, index) }
    }
}
