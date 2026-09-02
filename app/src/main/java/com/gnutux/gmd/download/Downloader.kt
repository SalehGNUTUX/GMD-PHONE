package com.gnutux.gmd.download

import android.content.Context
import android.os.Environment
import com.yausername.youtubedl_android.YoutubeDL
import com.yausername.youtubedl_android.YoutubeDLRequest
import com.yausername.youtubedl_android.YoutubeDLResponse
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File

/** ما يريده المستخدم: فيديو بجودة، أو صوت بصيغة. */
sealed interface Job {
    val url: String

    data class Video(override val url: String, val quality: Quality) : Job
    data class Audio(override val url: String, val format: AudioFormat) : Job
}

/**
 * الجودةُ سقفٌ على **الضلعِ الأصغر**، لا على الارتفاع.
 *
 * كانت السلسلةُ ترشِّحُ بـ`height` وحدَه: `bv*[height<=720]+ba/best[height<=720]`.
 * وهذا صحيحٌ في الفيديو الأفقيِّ حيثُ الارتفاعُ هو الضلعُ الأصغر، وخاطئٌ في العموديِّ
 * حيثُ ينقلبُ الأمر: مقطعُ «720p» عموديّاً مقاسُه 720×1280، فارتفاعُه 1280 يتجاوزُ
 * كلَّ سقفٍ يطلبُه المستخدم — حتّى سقفَ 1080 — فلا تُطابِقُ الشرطَ صيغةٌ واحدةٌ
 * ويموتُ التنزيلُ بـ`Requested format is not available`. ومقاطعُ فيسبوك عموديّةٌ
 * في غالبِها، فظهرَ العطبُ هناك أوّلاً، لا لأنّ فيسبوك خاصٌّ بشيء.
 *
 * فتُرِكَ الترشيحُ إلى `-S res:N`، وحقلُ `res` عندَ yt-dlp هو الضلعُ الأصغرُ نفسُه،
 * فيَصحُّ في الاتّجاهَين. وهو ترتيبٌ لا شرطٌ قاطع: يُقدِّمُ الأقربَ عندَ السقفِ أو
 * دونَه، فإن لم يكن في المصدرِ إلّا ما فوقَه اختارَ أصغرَ ما فوقَه بدلَ أن يفشل.
 * و`[height>0]` يستبعدُ الصيغَ التي لا تُعلِنُ مقاسَها (‏`sd`/`hd` في فيسبوك) من
 * التفضيلِ الأوّل، لأنّ المجهولَ يتصدّرُ ترتيبَ `res` زوراً، ويُبقيها احتياطاً أخيراً.
 */
private const val CAPPED = "bv*[height>0]+ba/b[height>0]/bv*+ba/b"

enum class Quality(val selector: String, val sort: String?, val label: String) {
    BEST("bv*+ba/b", null, "qbest"),
    P1080(CAPPED, "res:1080", "q1080"),
    P720(CAPPED, "res:720", "q720"),
    P480(CAPPED, "res:480", "q480"),
}

enum class AudioFormat(val ext: String) {
    MP3("mp3"), M4A("m4a"), OPUS("opus"), FLAC("flac"), WAV("wav"), VORBIS("vorbis"),
}

data class MediaInfo(
    val title: String,
    val uploader: String,
    val duration: String,
    val resolution: String,
    val ext: String,
    val views: String,
    val thumbnail: String?,
)

/**
 * غلافٌ حول yt-dlp. لا نبني هنا نصَّ أمرٍ ولا نمرّر شيئاً عبر صدفة: كلُّ قيمةٍ من
 * المستخدم — الرابطُ قبلَ غيرِه — تدخل خانةً مستقلّةً في YoutubeDLRequest، فينتفي
 * حقنُ الأوامر من أصله لا بالهروب منه. وهو الدرس نفسه الذي فُرِض على نسخة سطح
 * المكتب في 26.9.0، وهنا تفرضه بنيةُ المكتبة مجّاناً.
 */
object Downloader {

    /** مجلَّد عملٍ خاصّ بالتطبيق؛ النقل إلى معرض الوسائط يجري بعد الاكتمال. */
    fun stagingDir(context: Context): File =
        File(context.getExternalFilesDir(Environment.DIRECTORY_DOWNLOADS), "staging").apply { mkdirs() }

    suspend fun fetchInfo(url: String): Result<MediaInfo> = withContext(Dispatchers.IO) {
        runCatching {
            val info = YoutubeDL.getInstance().getInfo(sanitize(url))
            MediaInfo(
                title = info.title ?: "—",
                uploader = info.uploader ?: "—",
                duration = formatDuration(info.duration),
                resolution = info.resolution ?: "—",
                ext = info.ext ?: "—",
                views = info.viewCount?.toString() ?: "—",
                thumbnail = info.thumbnail,
            )
        }
    }

    /**
     * ينفّذ المهمّة ويستدعي [onProgress] بنسبةٍ مئويّةٍ ومدّةٍ متبقّية.
     * [processId] يسمح بإلغائها لاحقاً عبر [cancel].
     */
    suspend fun run(
        context: Context,
        job: Job,
        processId: String,
        onProgress: (percent: Float, etaSeconds: Long, line: String) -> Unit,
    ): Result<File> = withContext(Dispatchers.IO) {
        runCatching {
            val out = stagingDir(context)
            out.listFiles()?.forEach { it.delete() }   // بقايا محاولةٍ سابقة

            val request = YoutubeDLRequest(sanitize(job.url)).apply {
                addOption("-o", "${out.absolutePath}/%(title).80s.%(ext)s")
                addOption("--no-mtime")
                addOption("--no-playlist")
                when (job) {
                    is Job.Video -> {
                        addOption("-f", job.quality.selector)
                        job.quality.sort?.let { addOption("-S", it) }
                        addOption("--merge-output-format", "mp4")
                    }
                    is Job.Audio -> {
                        addOption("--extract-audio")
                        addOption("--audio-format", job.format.ext)
                        if (job.format == AudioFormat.MP3) addOption("--audio-quality", "0")
                    }
                }
            }

            val response: YoutubeDLResponse = YoutubeDL.getInstance().execute(request, processId) { p, eta, line ->
                onProgress(p, eta, line)
            }

            // النجاح يُقاس برمز الخروج ووجود ملفٍّ فعليّ — لا بالبحث عن كلمة "error"
            // في الخرج، فـ yt-dlp يطبعها على محاولةٍ فاشلةٍ ثمّ ينجح بالتالية.
            if (response.exitCode != 0) error("yt-dlp exited with ${response.exitCode}")
            out.listFiles()?.firstOrNull { it.isFile && it.length() > 0 }
                ?: error("no output file was produced")
        }
    }

    fun cancel(processId: String) {
        runCatching { YoutubeDL.getInstance().destroyProcessById(processId) }
    }

    /** يُحدِّث ثنائيّ yt-dlp نفسه — الجزء الذي يحتاج تحديثاً أسبوعيّاً. */
    suspend fun updateYtDlp(context: Context): Result<String> = withContext(Dispatchers.IO) {
        runCatching {
            YoutubeDL.getInstance()
                .updateYoutubeDL(context, YoutubeDL.UpdateChannel.STABLE)
                ?.name ?: "UNCHANGED"
        }
    }

    suspend fun version(context: Context): String? = withContext(Dispatchers.IO) {
        runCatching { YoutubeDL.getInstance().version(context) }.getOrNull()
    }

    /** يمنع رابطاً يبدأ بشَرطة من أن يُقرأ خياراً، ويقصّ الفراغ المحيط. */
    private fun sanitize(url: String): String = url.trim().removePrefix("-")

    private fun formatDuration(seconds: Int?): String {
        val s = seconds ?: return "—"
        val h = s / 3600; val m = (s % 3600) / 60; val sec = s % 60
        return if (h > 0) "%02d:%02d:%02d".format(h, m, sec) else "%02d:%02d".format(m, sec)
    }
}
