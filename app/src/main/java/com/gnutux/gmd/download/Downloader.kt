package com.gnutux.gmd.download

import android.content.Context
import android.os.Environment
import com.yausername.youtubedl_android.YoutubeDL
import com.yausername.youtubedl_android.YoutubeDLRequest
import com.yausername.youtubedl_android.YoutubeDLResponse
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONObject
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

/** عنصرٌ واحدٌ في قائمةِ تشغيل، كما يراه المستخدمُ قبلَ أن يختار. */
data class PlaylistEntry(val index: Int, val title: String, val duration: String)

/** قائمةُ تشغيلٍ مكتشَفةٌ خلفَ الرابط. */
data class PlaylistInfo(
    val title: String,
    val entries: List<PlaylistEntry>,
) {
    val count: Int get() = entries.size

    /**
     * اسمُ مجلَّدٍ صالحٌ لنظامِ الملفّاتِ ولـMediaStore.
     *
     * `RELATIVE_PATH` يرفضُ المحارفَ التي يرفضُها نظامُ الملفّات، وعنوانُ القائمةِ
     * يأتي من الشبكةِ فقد يحملُ أيّاً منها — وقد يكونُ فارغاً أصلاً.
     */
    fun folderName(): String {
        val cleaned = title.replace(Regex("""[/\\:*?"<>|\r\n]"""), " ")
            .replace(Regex("""\s+"""), " ").trim().take(60)
        return cleaned.ifBlank { "playlist" }
    }
}

/** ما يُطلَبُ تنزيلُه من قائمةِ تشغيل: مؤشّراتُ العناصرِ ومجلَّدُها. */
data class PlaylistJob(val folder: String, val items: List<Int>) {
    /** صيغةُ `--playlist-items`: أرقامٌ مفصولةٌ بفواصل. */
    fun toArg(): String = items.joinToString(",")
}

/** ما يريده المستخدم: فيديو بجودة، أو صوت بصيغة، وقد يريد جزءاً منه. */
sealed interface Job {
    val url: String
    val section: Section?
    val playlist: PlaylistJob?

    data class Video(
        override val url: String,
        val quality: Quality,
        override val section: Section? = null,
        override val playlist: PlaylistJob? = null,
    ) : Job

    data class Audio(
        override val url: String,
        val format: AudioFormat,
        override val section: Section? = null,
        override val playlist: PlaylistJob? = null,
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
     * يكشفُ ما إذا كان الرابطُ قائمةَ تشغيل، ويُعيدُ عناصرَها بلا تنزيلِ شيء.
     *
     * `--flat-playlist` يمنعُ yt-dlp من زيارةِ كلِّ عنصرٍ على حدة، فقائمةٌ فيها مئةُ
     * مقطعٍ تُكشَفُ بطلبٍ واحدٍ لا بمئة. ويُعادُ `null` إن كان الرابطُ مقطعاً مفرداً.
     */
    suspend fun fetchPlaylist(url: String): PlaylistInfo? = withContext(Dispatchers.IO) {
        runCatching {
            val request = YoutubeDLRequest(sanitize(url)).apply {
                addOption("--flat-playlist")
                addOption("--dump-single-json")
                addOption("--no-warnings")
            }
            val out = YoutubeDL.getInstance().execute(request).out
            val root = JSONObject(out)
            val entriesJson = root.optJSONArray("entries")
            val isPlaylist = root.optString("_type") == "playlist" ||
                (entriesJson != null && entriesJson.length() > 1)
            if (!isPlaylist || entriesJson == null) return@runCatching null

            val entries = buildList {
                for (i in 0 until entriesJson.length()) {
                    val e = entriesJson.optJSONObject(i) ?: continue
                    add(
                        PlaylistEntry(
                            index = i + 1,
                            title = e.optString("title").ifBlank {
                                e.optString("id").ifBlank { "#${i + 1}" }
                            },
                            duration = e.optString("duration_string"),
                        )
                    )
                }
            }
            if (entries.size < 2) null
            else PlaylistInfo(root.optString("title"), entries)
        }.getOrNull()
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
    ): Result<List<File>> = withContext(Dispatchers.IO) {
        runCatching {
            val out = stagingDir(context)
            out.listFiles()?.forEach { it.delete() }   // بقايا محاولةٍ سابقة

            // الاقتصاصُ لا معنى له في قائمةِ تشغيل: حدٌّ زمنيٌّ واحدٌ لا يصلح
            // لمقاطعَ مختلفةِ الطول
            val section = job.section?.takeIf { it.valid && job.playlist == null }

            // المسلك الأوّل: نطلب من الخادم الجزءَ وحدَه فلا يُنزَّل ما لا يُراد،
            // وهو على الهاتف توفيرٌ حقيقيّ في البيانات والوقت معاً.
            val first = attempt(context, job, section, processId, out, onProgress)
            if (first.isNotEmpty()) {
                return@runCatching if (section != null) listOf(trim(context, first.first(), section))
                else first
            }

            // ويرفض بعضُ المواقع — يوتيوب منها — أن يجلب ffmpeg نطاقاً من روابطها
            // فيردّ 403. فإن كان القصُّ مطلوباً وسقط، نزّلنا المادّة كاملةً ثمّ
            // اقتصصناها هنا. أبطأُ وأكثرُ بيانات، لكنّه ينجح حيث يفشل الأوّل.
            if (section == null) error(lastError ?: "no output file was produced")

            out.listFiles()?.forEach { it.delete() }
            val whole = attempt(context, job, null, processId, out, onProgress)
                .firstOrNull() ?: error(lastError ?: "no output file was produced")
            listOf(trim(context, whole, section))
        }
    }

    /** آخرُ خطأٍ من محاولةٍ داخليّة، ليُبلَّغ عنه بدل رسالةٍ عامّة. */
    @Volatile private var lastError: String? = null

    /** محاولةُ تنزيلٍ واحدة؛ تُعيد الملفّاتِ الناتجةَ أو قائمةً فارغةً إن فشلت. */
    private fun attempt(
        context: Context,
        job: Job,
        section: Section?,
        processId: String,
        out: File,
        onProgress: (Float, Long, String) -> Unit,
    ): List<File> {
        val request = YoutubeDLRequest(sanitize(job.url)).apply {
            addOption("--no-mtime")
            if (job.playlist != null) {
                // ترتيبُ العناصرِ يُكتَبُ في الاسم، فيبقى ترتيبُ القائمةِ ظاهراً في
                // المجلَّد مهما رتّبَه عارضُ الوسائطِ أبجديّاً
                addOption("-o", "${out.absolutePath}/%(playlist_index)02d - %(title).60s.%(ext)s")
                addOption("--yes-playlist")
                addOption("--playlist-items", job.playlist!!.toArg())
                // فشلُ عنصرٍ لا يُسقِطُ القائمةَ كلَّها
                addOption("--ignore-errors")
            } else {
                addOption("-o", "${out.absolutePath}/%(title).80s.%(ext)s")
                addOption("--no-playlist")
            }
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
            val produced = out.listFiles()
                ?.filter { it.isFile && it.length() > 0 && !it.name.endsWith(".part") }
                ?.sortedBy { it.name }
                .orEmpty()

            // النجاح يُقاس برمز الخروج ووجود ملفٍّ فعليّ — لا بالبحث عن كلمة "error"
            // في الخرج، فـ yt-dlp يطبعها على محاولةٍ فاشلةٍ ثمّ ينجح بالتالية.
            // وفي قائمةِ التشغيل يُمرَّر `--ignore-errors` فيخرج برمزٍ غيرِ صفرٍ وقد
            // نجح بعضُها، فما نزل يُحفَظ ولا يُهدَر لأجل ما سقط.
            if (response.exitCode != 0 && produced.isEmpty()) {
                lastError = "yt-dlp exited with ${response.exitCode}"
                return emptyList()
            }
            if (produced.isEmpty()) lastError = "no output file was produced"
            produced
        } catch (e: Throwable) {
            lastError = e.message ?: e::class.java.name
            emptyList()
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
