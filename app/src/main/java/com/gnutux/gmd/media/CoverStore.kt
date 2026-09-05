package com.gnutux.gmd.media

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.net.Uri
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File
import java.security.MessageDigest

/**
 * أغلفةُ المقاطعِ الصوتيّة.
 *
 * ملفُّ الصوتِ الذي يُخرِجُه yt-dlp لا يحملُ غلافاً في جوفِه، فإطارُ المشغّلِ كانَ
 * يبقى تدرُّجاً لونيّاً في كلِّ ما نُزِّل. والغلافُ يُنزَّلُ مع المقطعِ صورةً إلى
 * جانبِه (‏`--write-thumbnail`) ثمّ يُودَعُ هنا مربوطاً بعنوانِ المقطعِ في المعرض.
 *
 * ولا يُدمَجُ الغلافُ في الملفِّ نفسِه: دمجُه عملٌ بعدَ المعالجةِ قد يفشلُ على
 * صيغةٍ أو رابطٍ فيَعُدُّ yt-dlp التنزيلَ كلَّه فاشلاً وقد كُتِبَ الملفُّ فعلاً —
 * وخسارةُ تنزيلٍ أثقلُ من خلوِّ إطارٍ من صورة. وثمنُ ذلك أنّ الغلافَ لا يُرى
 * خارجَ GMD ويذهبُ بمسحِ بياناتِ التطبيق.
 *
 * والمخزنُ محدودٌ بعدَدٍ يُقتَطَعُ عندَه أقدمُ ما فيه: الملفُّ يُحذَفُ من المعرضِ ولا
 * يُخبِرُنا أحدٌ بحذفِه، فلولا القصُّ لبقيت أغلفةُ ما لم يَعُد موجوداً إلى الأبد.
 */
object CoverStore {

    private const val DIR = "covers"

    /** أكبرُ ضلعٍ للصورةِ المحفوظة: الإطارُ لا يزيدُ عن هذا في أعرضِ الشاشات. */
    private const val EDGE = 512

    /** أكثرُ ما يُحفَظُ من الأغلفة، ويُقَصُّ أقدمُها عندَ التجاوز. */
    private const val MAX_FILES = 400

    private val IMAGE_EXTS = listOf("webp", "jpg", "jpeg", "png")

    private fun dir(context: Context): File =
        File(context.filesDir, DIR).apply { mkdirs() }

    /** اسمُ ملفِّ الغلاف: بصمةُ العنوانِ لا العنوانُ نفسُه، فهو ليس اسمَ ملفٍّ صالحاً. */
    private fun keyOf(uri: String): String =
        MessageDigest.getInstance("SHA-1").digest(uri.toByteArray())
            .joinToString("") { "%02x".format(it) }

    fun fileFor(context: Context, uri: String): File = File(dir(context), keyOf(uri) + ".jpg")

    fun has(context: Context, uri: String): Boolean = fileFor(context, uri).isFile

    /** الغلافُ المحفوظُ لمقطعٍ، أو `null` إن لم يكن له غلاف. */
    fun get(context: Context, uri: String): Bitmap? {
        val f = fileFor(context, uri)
        if (!f.isFile) return null
        return runCatching { BitmapFactory.decodeFile(f.absolutePath) }.getOrNull()
    }

    /**
     * يتبنّى الصورةَ التي نزلت إلى جانبِ [media] غلافاً للمقطعِ المحفوظِ في [uri].
     *
     * والصورةُ تُصغَّرُ ثمّ تُحذَفُ من مجلَّدِ العمل: هي بحجمِ صفحةِ الرفعِ الأصليِّ
     * أحياناً، ولا تُرى إلّا في إطارٍ صغير.
     */
    suspend fun adopt(context: Context, media: File, uri: Uri): Boolean =
        withContext(Dispatchers.IO) {
            val parent = media.parentFile ?: return@withContext false
            val base = media.name.substringBeforeLast('.')
            val image = IMAGE_EXTS.asSequence()
                .map { File(parent, "$base.$it") }
                .firstOrNull { it.isFile && it.length() > 0 }
                ?: return@withContext false

            val ok = runCatching {
                // القياسُ أوّلاً بلا فكِّ ترميز: صورةُ الغلافِ قد تكونُ 1280×720،
                // وفكُّها كاملةً في الذاكرةِ ثمنٌ بلا فائدةٍ لصورةٍ ستُصغَّر
                val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
                BitmapFactory.decodeFile(image.absolutePath, bounds)
                var sample = 1
                while (bounds.outWidth / sample > EDGE * 2 && bounds.outHeight / sample > EDGE * 2) {
                    sample *= 2
                }
                val decoded = BitmapFactory.decodeFile(
                    image.absolutePath, BitmapFactory.Options().apply { inSampleSize = sample },
                ) ?: return@runCatching false

                val longest = maxOf(decoded.width, decoded.height)
                val scaled = if (longest > EDGE) {
                    val ratio = EDGE.toFloat() / longest
                    Bitmap.createScaledBitmap(
                        decoded, (decoded.width * ratio).toInt().coerceAtLeast(1),
                        (decoded.height * ratio).toInt().coerceAtLeast(1), true,
                    )
                } else decoded

                fileFor(context, uri.toString()).outputStream().use {
                    scaled.compress(Bitmap.CompressFormat.JPEG, 85, it)
                }
                if (scaled !== decoded) scaled.recycle()
                decoded.recycle()
                true
            }.getOrDefault(false)

            image.delete()
            if (ok) prune(context)
            ok
        }

    /** يمحو أقدمَ الأغلفةِ إن تجاوزَ عددُها الحدَّ. */
    private fun prune(context: Context) {
        val files = dir(context).listFiles()?.filter { it.isFile } ?: return
        if (files.size <= MAX_FILES) return
        files.sortedBy { it.lastModified() }
            .take(files.size - MAX_FILES)
            .forEach { it.delete() }
    }
}
