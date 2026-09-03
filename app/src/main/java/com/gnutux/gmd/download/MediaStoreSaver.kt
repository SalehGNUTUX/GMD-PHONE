package com.gnutux.gmd.download

import android.content.ContentValues
import android.content.Context
import android.net.Uri
import android.os.Build
import android.os.Environment
import android.provider.MediaStore
import android.webkit.MimeTypeMap
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File

/**
 * ينقل الملفّ المُنتَج إلى معرض الوسائط.
 *
 * المسارات الحرّة التي تعتمدها نسخة سطح المكتب لم تعد متاحة منذ أندرويد 10:
 * الكتابة خارج مساحة التطبيق تمرّ عبر MediaStore، وهو أيضاً ما يجعل الملفّ يظهر
 * في تطبيق المعرض ومشغّل الصوت بدل أن يبقى حبيس مجلَّد التطبيق.
 */
object MediaStoreSaver {

    data class Saved(val uri: Uri, val displayName: String, val relativePath: String)

    /**
     * [subFolder] مجلَّدٌ فرعيٌّ داخلَ GMD لقائمةِ تشغيل، فتُحفَظ كلُّ قائمةٍ في
     * مجلَّدٍ باسمِها بدلَ أن تختلطَ مقاطعُها بغيرِها في مجلَّدٍ واحد.
     */
    suspend fun save(
        context: Context,
        source: File,
        isAudio: Boolean,
        subFolder: String? = null,
    ): Result<Saved> =
        withContext(Dispatchers.IO) {
            runCatching {
                val name = source.name
                val mime = mimeOf(name, isAudio)
                val folder = if (isAudio) Environment.DIRECTORY_MUSIC else Environment.DIRECTORY_MOVIES
                val relative = if (subFolder.isNullOrBlank()) "$folder/GMD"
                               else "$folder/GMD/$subFolder"

                val collection = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                    if (isAudio) MediaStore.Audio.Media.getContentUri(MediaStore.VOLUME_EXTERNAL_PRIMARY)
                    else MediaStore.Video.Media.getContentUri(MediaStore.VOLUME_EXTERNAL_PRIMARY)
                } else {
                    if (isAudio) MediaStore.Audio.Media.EXTERNAL_CONTENT_URI
                    else MediaStore.Video.Media.EXTERNAL_CONTENT_URI
                }

                val values = ContentValues().apply {
                    put(MediaStore.MediaColumns.DISPLAY_NAME, name)
                    put(MediaStore.MediaColumns.MIME_TYPE, mime)
                    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                        put(MediaStore.MediaColumns.RELATIVE_PATH, relative)
                        // IS_PENDING يخفي الملفّ عن التطبيقات الأخرى حتى يكتمل النسخ،
                        // فلا يظهر في المعرض مقطعاً نصفَ مكتوب
                        put(MediaStore.MediaColumns.IS_PENDING, 1)
                    }
                }

                val resolver = context.contentResolver
                val uri = resolver.insert(collection, values)
                    ?: error("MediaStore refused to create an entry")

                try {
                    resolver.openOutputStream(uri)?.use { out ->
                        source.inputStream().use { it.copyTo(out, DEFAULT_BUFFER_SIZE) }
                    } ?: error("could not open the destination for writing")
                } catch (e: Throwable) {
                    resolver.delete(uri, null, null)   // لا نترك مدخلاً فارغاً في المعرض
                    throw e
                }

                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                    values.clear()
                    values.put(MediaStore.MediaColumns.IS_PENDING, 0)
                    resolver.update(uri, values, null, null)
                }

                source.delete()
                Saved(uri, name, relative)
            }
        }

    private fun mimeOf(name: String, isAudio: Boolean): String {
        val ext = name.substringAfterLast('.', "").lowercase()
        MimeTypeMap.getSingleton().getMimeTypeFromExtension(ext)?.let { return it }
        return if (isAudio) "audio/*" else "video/*"
    }
}
