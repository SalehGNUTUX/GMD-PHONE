package com.gnutux.gmd.download

import android.content.Context
import android.os.Environment
import com.yausername.youtubedl_android.YoutubeDL
import com.yausername.youtubedl_android.YoutubeDLRequest
import com.yausername.youtubedl_android.YoutubeDLResponse
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File

/**
 * مقطعٌ زمنيٌّ يُقتَصُّ من المادّة.
 *
 * القطعُ يقعُ عند أقربِ إطارٍ مفتاحيّ، فقد يزيدُ المقطعُ ثانيةً أو ينقصُها. وإجبارُ
 * الإطاراتِ (‏`--force-keyframes-at-cuts`) يُدقّقُ الحدَّ لكنّه يُعيدُ الترميزَ كاملاً،
 * وذلك على الهاتفِ دقائقُ من المعالجةِ ونصيبٌ من البطّاريّة لأجلِ جزءٍ من الثانية.
 */
data class Section(val startSec: Int, val endSec: Int) {
    val valid: Boolean get() = startSec >= 0 && endSec > startSec
    fun toArg(): String = "*$startSec-$endSec"
    fun startClock(): String = clock(startSec)
    fun endClock(): String = clock(endSec)

    private fun clock(t: Int): String =
        "%02d:%02d:%02d".format(t / 3600, (t % 3600) / 60, t % 60)
}

/** ما يريده المستخدم: فيديو بجودة، أو صوت بصيغة، وقد يريد جزءاً منه. */
sealed interface Job {
    val url: String
    val section: Section?

    data class Video(
        override val url: String,
        val quality: Quality,
        override val section: Section? = null,
    ) : Job

    data class Audio(
        override val url: String,
        val format: AudioFormat,
        override val section: Section? = null,
    ) : Job
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

            val section = job.section?.takeIf { it.valid }

            // المسلك الأوّل: نطلب من الخادم الجزءَ وحدَه فلا يُنزَّل ما لا يُراد،
            // وهو على الهاتف توفيرٌ حقيقيّ في البيانات والوقت معاً.
            val first = attempt(context, job, section, processId, out, onProgress)
            if (first != null) return@runCatching first

            // ويرفض بعضُ المواقع — يوتيوب منها — أن يجلب ffmpeg نطاقاً من روابطها
            // فيردّ 403. فإن كان القصُّ مطلوباً وسقط، نزّلنا المادّة كاملةً ثمّ
            // اقتصصناها هنا. أبطأُ وأكثرُ بيانات، لكنّه ينجح حيث يفشل الأوّل.
            if (section == null) error(lastError ?: "no output file was produced")

            out.listFiles()?.forEach { it.delete() }
            val whole = attempt(context, job, null, processId, out, onProgress)
                ?: error(lastError ?: "no output file was produced")
            trim(context, whole, section)
        }
    }

    /** آخرُ خطأٍ من محاولةٍ داخليّة، ليُبلَّغ عنه بدل رسالةٍ عامّة. */
    @Volatile private var lastError: String? = null

    /** محاولةُ تنزيلٍ واحدة؛ تُعيد الملفَّ الناتج أو `null` إن فشلت. */
    private fun attempt(
        context: Context,
        job: Job,
        section: Section?,
        processId: String,
        out: File,
        onProgress: (Float, Long, String) -> Unit,
    ): File? {
        val request = YoutubeDLRequest(sanitize(job.url)).apply {
            addOption("-o", "${out.absolutePath}/%(title).80s.%(ext)s")
            addOption("--no-mtime")
            addOption("--no-playlist")
            section?.let { addOption("--download-sections", it.toArg()) }
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

        return try {
            val response: YoutubeDLResponse =
                YoutubeDL.getInstance().execute(request, processId) { p, eta, line ->
                    onProgress(p, eta, line)
                }
            // النجاح يُقاس برمز الخروج ووجود ملفٍّ فعليّ — لا بالبحث عن كلمة "error"
            // في الخرج، فـ yt-dlp يطبعها على محاولةٍ فاشلةٍ ثمّ ينجح بالتالية.
            if (response.exitCode != 0) {
                lastError = "yt-dlp exited with ${response.exitCode}"
                return null
            }
            out.listFiles()?.firstOrNull { it.isFile && it.length() > 0 }
                ?: run { lastError = "no output file was produced"; null }
        } catch (e: Throwable) {
            lastError = e.message ?: e::class.java.name
            null
        }
    }

    /**
     * يقتصُّ المقطعَ من ملفٍّ منزَّلٍ بنسخِ التيّاراتِ بلا إعادةِ ترميز.
     *
     * ثنائيُّ ffmpeg تشحنُه المكتبةُ في `nativeLibraryDir` — وهو مسارٌ عامٌّ مستقرٌّ في
     * أندرويد لا تخمينَ فيه — ويُتحقَّقُ من وجودِه قبلَ استعماله. ويُمرَّرُ الأمرُ
     * مصفوفةَ وُسَطاءَ بلا صدفة، كسائرِ ما في البرنامج.
     */
    private fun trim(context: Context, input: File, section: Section): File {
        val ffmpeg = File(context.applicationInfo.nativeLibraryDir, FFMPEG_BIN)
        if (!ffmpeg.canExecute()) {
            error("this site refuses partial downloads and ffmpeg was not found to trim locally")
        }
        val output = File(input.parentFile, "clip-${input.name}")
        val process = ProcessBuilder(
            listOf(
                ffmpeg.absolutePath, "-y",
                "-ss", section.startClock(),
                "-to", section.endClock(),
                "-i", input.absolutePath,
                "-c", "copy",
                output.absolutePath,
            )
        ).redirectErrorStream(true).start()

        // الخرجُ يُستهلَكُ وإلّا امتلأت ذاكرةُ الأنبوبِ فتجمّدت العمليّة
        val log = process.inputStream.bufferedReader().use { it.readText() }
        val code = process.waitFor()
        if (code != 0 || !output.isFile || output.length() == 0L) {
            output.delete()
            error("ffmpeg trim failed (exit $code)\n${log.takeLast(600)}")
        }
        input.delete()
        return output
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

    /** اسمُ ثنائيِّ ffmpeg كما تشحنُه مكتبةُ youtubedl-android في jniLibs. */
    private const val FFMPEG_BIN = "libffmpeg.so"

    /** يمنع رابطاً يبدأ بشَرطة من أن يُقرأ خياراً، ويقصّ الفراغ المحيط. */
    private fun sanitize(url: String): String = url.trim().removePrefix("-")

    private fun formatDuration(seconds: Int?): String {
        val s = seconds ?: return "—"
        val h = s / 3600; val m = (s % 3600) / 60; val sec = s % 60
        return if (h > 0) "%02d:%02d:%02d".format(h, m, sec) else "%02d:%02d".format(m, sec)
    }
}
