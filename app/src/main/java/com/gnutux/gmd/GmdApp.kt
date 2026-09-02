package com.gnutux.gmd

import android.app.Application
import android.util.Log
import com.yausername.aria2c.Aria2c
import com.yausername.ffmpeg.FFmpeg
import com.yausername.youtubedl_android.YoutubeDL
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch

/**
 * حالة الأدوات الأصليّة. أوّل تشغيل يستخرج بايثون و yt-dlp و ffmpeg و aria2c من
 * الحزمة إلى مساحة التطبيق، وهي عمليّة تستغرق ثوانيَ ولا يصحّ أن تجري على الخيط
 * الرئيس، ولا أن تُستدعى عمليّةُ تنزيلٍ قبل انتهائها.
 */
sealed interface ToolsState {
    data object Preparing : ToolsState
    data object Ready : ToolsState
    data class Failed(val message: String) : ToolsState
}

class GmdApp : Application() {

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    private val _tools = MutableStateFlow<ToolsState>(ToolsState.Preparing)
    val tools: StateFlow<ToolsState> = _tools

    override fun onCreate() {
        super.onCreate()
        instance = this
        scope.launch { initTools() }
    }

    private fun initTools() {
        try {
            YoutubeDL.getInstance().init(this)
            FFmpeg.getInstance().init(this)
            Aria2c.getInstance().init(this)
            _tools.value = ToolsState.Ready
        } catch (e: Throwable) {
            Log.e(TAG, "tool init failed", e)
            _tools.value = ToolsState.Failed(describe(e))
        }
    }

    /**
     * وصفٌ صالحٌ للإبلاغ: اسمُ الصنفِ الكاملُ ورسالتُه، ثمّ سلسلةُ الأسباب.
     * الرسالةُ وحدَها كثيراً ما تكون فارغةً فلا تدلّ على شيء، واسمُ الصنفِ
     * المختصرُ وحدَه لا يكفي — وإن كان البناء مصغَّراً خرج مشوَّشاً بلا معنى.
     */
    private fun describe(e: Throwable): String = buildString {
        var t: Throwable? = e
        var depth = 0
        while (t != null && depth < 4) {
            if (depth > 0) append("\n← ")
            append(t!!::class.java.name)
            t!!.message?.takeIf { it.isNotBlank() }?.let { append(": ").append(it) }
            t = t!!.cause
            depth++
        }
    }

    /** يُعيد المحاولة بعد فشلٍ عابر — مساحة قرصٍ ممتلئة مثلاً. */
    fun retryTools() {
        if (_tools.value is ToolsState.Failed) {
            _tools.value = ToolsState.Preparing
            scope.launch { initTools() }
        }
    }

    companion object {
        private const val TAG = "GmdApp"
        lateinit var instance: GmdApp
            private set
    }
}
