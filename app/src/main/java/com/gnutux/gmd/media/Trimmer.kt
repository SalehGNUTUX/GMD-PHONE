package com.gnutux.gmd.media

import android.content.Context
import android.media.MediaMetadataRetriever
import android.net.Uri
import android.provider.MediaStore
import android.provider.OpenableColumns
import com.gnutux.gmd.download.Downloader
import com.gnutux.gmd.download.MediaStoreSaver
import com.gnutux.gmd.download.Section
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File

/**
 * يقتصُّ مقطعاً من مادّةٍ موجودةٍ في الجهاز.
 *
 * وهو أيضاً المقتصُّ الذي يستعملُه [Downloader] حينَ يرفضُ الموقعُ الطلبَ الجزئيَّ
 * فتُنزَّلُ المادّةُ كاملةً ثمّ تُقتَصّ: استدعاءُ ffmpeg واحدٌ في الحالتَين، فلا
 * تتفرّقُ وُسَطاؤه ولا تُصلَحُ علّةٌ في أحدِهما وتبقى في الآخر.
 *
 * ثنائيُّ ffmpeg تشحنُه مكتبةُ youtubedl-android في `nativeLibraryDir`، وهو مسارٌ
 * عامٌّ مستقرٌّ في أندرويد لا تخمينَ فيه.
 */
object Trimmer {

    /** اسمُ ثنائيِّ ffmpeg كما تشحنُه مكتبةُ youtubedl-android في jniLibs. */
    private const val FFMPEG_BIN = "libffmpeg.so"

    /** ما يجري الآن، ليُعرَضَ للمستخدمِ ويُلغى. */
    @Volatile private var current: Process? = null

    /** مصدرُ الاقتصاص كما تعرفُه الواجهة. */
    data class Source(
        val uri: Uri,
        val displayName: String,
        val isAudio: Boolean,
        val durationMs: Long,
        val sizeBytes: Long,
    )

    /** مراحلُ العمل: النسخُ قد يطولُ في ملفٍّ كبير، والقصُّ نفسُه أسرعُ منه. */
    sealed interface Phase {
        data class Copying(val percent: Float) : Phase
        data class Cutting(val percent: Float) : Phase
        data object Saving : Phase
    }

    fun cancel() {
        runCatching { current?.destroy() }
        current = null
    }

    /**
     * يقرأُ ما يلزمُ عن ملفٍّ اختارَه المستخدمُ من نافذةِ النظام.
     *
     * ونافذةُ الوثائقِ لا تُعطي مساراً ولا نوعاً موثوقاً دائماً، فالاسمُ والحجمُ
     * من `OpenableColumns`، والمدّةُ من مُستخرِجِ البيانات — وهو يقبلُ `content://`
     * بخلافِ ffmpeg. والمدّةُ قد تتعذّرُ في ملفٍّ معطوبٍ فتبقى صفراً ولا يمنعُ ذلك
     * القصَّ: هي للعرضِ وللتحقّقِ من الحدَّين لا للعملِ نفسِه.
     */
    suspend fun describe(context: Context, uri: Uri): Source = withContext(Dispatchers.IO) {
        var name = uri.lastPathSegment?.substringAfterLast('/').orEmpty()
        var size = 0L
        runCatching {
            context.contentResolver.query(
                uri,
                arrayOf(OpenableColumns.DISPLAY_NAME, OpenableColumns.SIZE),
                null, null, null,
            )?.use { c ->
                if (c.moveToFirst()) {
                    if (!c.isNull(0)) name = c.getString(0)
                    if (!c.isNull(1)) size = c.getLong(1)
                }
            }
        }
        val mime = runCatching { context.contentResolver.getType(uri) }.getOrNull().orEmpty()
        // `use` لا تصلحُ هنا: المُستخرِجُ لم يصر AutoCloseable إلّا في أندرويد 10،
        // فاستعمالُها يُصرَّفُ ويسقطُ وقتَ التشغيلِ على أجهزةِ ما قبلَه.
        val duration = runCatching {
            val r = MediaMetadataRetriever()
            try {
                r.setDataSource(context, uri)
                r.extractMetadata(MediaMetadataRetriever.METADATA_KEY_DURATION)?.toLongOrNull() ?: 0L
            } finally {
                runCatching { r.release() }
            }
        }.getOrDefault(0L)

        Source(
            uri = uri,
            displayName = name.ifBlank { "clip" },
            // النوعُ من MIME، فإن غابَ فمن اللاحقة: ملفٌّ صوتيٌّ يُحفَظُ في
            // Music/GMD ومرئيٌّ في Movies/GMD، والخطأُ هنا يضعُه في غيرِ بابِه
            isAudio = mime.startsWith("audio") ||
                (mime.isBlank() && name.substringAfterLast('.', "").lowercase() in AUDIO_EXT),
            durationMs = duration,
            sizeBytes = size,
        )
    }

    private val AUDIO_EXT = setOf("mp3", "m4a", "opus", "flac", "wav", "ogg", "aac", "oga")

    /**
     * يقتصُّ [section] من [source] ويحفظُ الناتجَ في معرضِ الوسائط.
     *
     * ولا يُمَسُّ الأصلُ البتّة: الاقتصاصُ يُنشئُ ملفّاً جديداً بجانبِه في المعرض،
     * فمن أخطأَ في الحدَّين أعادَ الكرّةَ ولم يخسر ما عنده.
     */
    suspend fun trim(
        context: Context,
        source: Source,
        section: Section,
        onProgress: (Phase) -> Unit,
    ): Result<MediaStoreSaver.Saved> = withContext(Dispatchers.IO) {
        runCatching {
            require(section.valid) { "invalid section" }

            // مجلَّدٌ خاصٌّ بالقصّ: تنزيلٌ جارٍ في القسمِ الآخرِ لا يُنافسُه على
            // المجلَّدِ ولا يُمحى أحدُهما بمَسحِ الآخر
            val staging = Downloader.stagingDir(context, "trim")
            // بقايا محاولةٍ سابقة: القصُّ لا يُنزِّلُ شيئاً فلا يُنافسُ تنزيلاً جارياً
            // على المجلَّد، لكنّ ملفّاً معلَّقاً من مرّةٍ فاشلةٍ يشغلُ مساحةً بلا فائدة.
            staging.listFiles()?.filter { it.name.startsWith(TEMP_PREFIX) }?.forEach { it.delete() }

            // المسلكُ الأوّل: مسارٌ حقيقيٌّ نقرؤه مباشرةً فلا نُضاعِفُ الملفَّ على
            // القرص. وأكثرُ ما يُقتَصُّ ملفّاتٌ نزّلها البرنامجُ نفسُه، ولها مسارٌ.
            val direct = localPath(context, source.uri)
            val input = direct ?: copyToStaging(context, source) { onProgress(Phase.Copying(it)) }

            val output = File(staging, outputName(source, section))
            try {
                cut(context, input, section, output) { onProgress(Phase.Cutting(it)) }
                onProgress(Phase.Saving)
                // `save` ينقلُ الملفَّ ويحذفُ مصدرَه بعدَ النجاح
                MediaStoreSaver.save(context, output, source.isAudio).getOrThrow()
            } finally {
                // النسخةُ المؤقّتةُ تُمحى نجحَ القصُّ أم فشل؛ والمسارُ المباشرُ ملفُّ
                // المستخدمِ نفسُه فلا يُمَسّ.
                if (direct == null) input.delete()
            }
        }
    }

    /**
     * يقتصُّ بنسخِ التيّاراتِ بلا إعادةِ ترميز.
     *
     * `-ss` و`-to` قبلَ `-i` وسيطا **دخلٍ**: الأوّلُ يقفزُ إلى موضعِ البداية والثاني
     * يقفُ عندَ نهايةٍ مقيسةٍ على زمنِ المصدرِ نفسِه. ولو وُضِعا بعدَ `-i` لصارا على
     * زمنِ الخرجِ فاختلفَ معنى الثاني وطالَ المقطع.
     *
     * والقطعُ يقعُ عندَ أقربِ إطارٍ مفتاحيّ. وإجبارُ الإطاراتِ يُدقّقُ الحدَّ لكنّه
     * يُعيدُ الترميزَ كاملاً — على الهاتفِ دقائقُ من المعالجةِ ونصيبٌ من البطّاريّة
     * لأجلِ جزءٍ من الثانية.
     */
    fun cut(
        context: Context,
        input: File,
        section: Section,
        output: File,
        onProgress: ((Float) -> Unit)? = null,
    ) {
        val ffmpeg = File(context.applicationInfo.nativeLibraryDir, FFMPEG_BIN)
        if (!ffmpeg.canExecute()) error("ffmpeg was not found to trim locally")

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
        current = process

        val span = (section.endSec - section.startSec).coerceAtLeast(1)
        val tail = ArrayDeque<String>()
        try {
            // الخرجُ يُستهلَكُ سطراً سطراً وإلّا امتلأت ذاكرةُ الأنبوبِ فتجمّدت
            // العمليّة. ومنه يُستخرَجُ التقدُّم: ffmpeg يطبعُ `time=` مع كلِّ دفعة.
            process.inputStream.bufferedReader().forEachLine { line ->
                if (tail.size >= 40) tail.removeFirst()
                tail.addLast(line)
                onProgress?.let { report ->
                    progressSeconds(line)?.let { done ->
                        report((done / span * 100f).coerceIn(0f, 100f))
                    }
                }
            }
            val code = process.waitFor()
            if (code != 0 || !output.isFile || output.length() == 0L) {
                output.delete()
                error("ffmpeg trim failed (exit $code)\n${tail.joinToString("\n").takeLast(600)}")
            }
        } finally {
            current = null
        }
    }

    /** `time=00:01:07.42` من سطرِ حالةِ ffmpeg، بالثواني. */
    private fun progressSeconds(line: String): Float? {
        val m = TIME_RE.find(line) ?: return null
        val (h, min, s) = m.destructured
        return h.toFloat() * 3600 + min.toFloat() * 60 + s.toFloat()
    }

    private val TIME_RE = Regex("""time=(\d+):(\d\d):(\d\d\.?\d*)""")

    /**
     * مسارُ الملفِّ في نظامِ الملفّاتِ إن كان مقروءاً.
     *
     * ffmpeg عمليّةٌ أصليّةٌ لا تفهمُ `content://`، فإمّا مسارٌ حقيقيٌّ وإمّا نسخةٌ
     * إلى مجلَّدِ العمل. وعمودُ `DATA` مهجورٌ منذ أندرويد 10 لكنّه ما زالَ يُملأُ
     * لملفّاتِ الوسائط، والقراءةُ منه تمرُّ بطبقةِ FUSE بأذونِ التطبيقِ نفسِها —
     * فالفحصُ الحاسمُ هو `canRead` لا رقمُ الإصدار.
     */
    private fun localPath(context: Context, uri: Uri): File? {
        if (uri.scheme == "file") return uri.path?.let(::File)?.takeIf { it.canRead() }
        return runCatching {
            context.contentResolver.query(
                uri, arrayOf(@Suppress("DEPRECATION") MediaStore.MediaColumns.DATA),
                null, null, null,
            )?.use { c ->
                if (c.moveToFirst() && !c.isNull(0)) File(c.getString(0)).takeIf { it.canRead() }
                else null
            }
        }.getOrNull()
    }

    /** نسخةٌ في مجلَّدِ العملِ لما لا مسارَ له — ملفٌّ اختيرَ من نافذةِ النظامِ مثلاً. */
    private fun copyToStaging(
        context: Context,
        source: Source,
        onProgress: (Float) -> Unit,
    ): File {
        val temp = File(Downloader.stagingDir(context, "trim"), TEMP_PREFIX + safeName(source.displayName))
        val total = source.sizeBytes.takeIf { it > 0 }
        context.contentResolver.openInputStream(source.uri)?.use { input ->
            temp.outputStream().use { out ->
                val buffer = ByteArray(DEFAULT_BUFFER_SIZE)
                var copied = 0L
                var last = 0f
                while (true) {
                    val n = input.read(buffer)
                    if (n < 0) break
                    out.write(buffer, 0, n)
                    copied += n
                    if (total != null) {
                        val percent = copied * 100f / total
                        // إخطارٌ كلَّ واحدٍ في المئة: أكثرُ من ذلك يُغرِقُ الواجهةَ
                        // بتحديثاتٍ لا تُرى
                        if (percent - last >= 1f) { last = percent; onProgress(percent) }
                    }
                }
            }
        } ?: run {
            temp.delete()
            error("could not read the chosen file")
        }
        if (temp.length() == 0L) { temp.delete(); error("the chosen file is empty") }
        return temp
    }

    /**
     * اسمُ الناتج: اسمُ الأصلِ ومعه حدّا المقطع.
     *
     * والنقطتانِ الرأسيّتانِ ممنوعتانِ في أسماءِ الملفّاتِ فتصيرانِ نقطة، ويُقصَرُ
     * الأصلُ كي لا يتجاوزَ الاسمُ حدَّ نظامِ الملفّات.
     */
    private fun outputName(source: Source, section: Section): String {
        val dot = source.displayName.lastIndexOf('.')
        val base = (if (dot > 0) source.displayName.take(dot) else source.displayName).take(80)
        val ext = if (dot > 0) source.displayName.substring(dot) else ""
        val from = section.startClock().trimStart('0', ':').ifBlank { "0" }.replace(':', '.')
        val to = section.endClock().trimStart('0', ':').ifBlank { "0" }.replace(':', '.')
        return safeName("$base [$from-$to]$ext")
    }

    private fun safeName(name: String): String =
        name.replace(Regex("""[/\\:*?"<>|\r\n]"""), " ").replace(Regex("""\s+"""), " ").trim()
            .ifBlank { "clip" }

    private const val TEMP_PREFIX = "trim-src-"
}
