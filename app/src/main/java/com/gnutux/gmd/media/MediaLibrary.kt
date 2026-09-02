package com.gnutux.gmd.media

import android.content.ContentUris
import android.content.Context
import android.content.Intent
import android.content.IntentSender
import android.content.pm.PackageManager
import android.graphics.Bitmap
import android.net.Uri
import android.os.Build
import android.os.Environment
import android.provider.MediaStore
import android.util.Size
import androidx.core.content.ContextCompat
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/** مقطعٌ واحدٌ ممّا نزّله GMD. */
data class MediaEntry(
    val uri: Uri,
    val id: Long,
    val name: String,
    val mime: String,
    val sizeBytes: Long,
    val durationMs: Long,
    val addedSeconds: Long,
    val isAudio: Boolean,
)

/** نتيجةُ محاولةِ الحذف: قد يطلبُ النظامُ إذنَ المستخدمِ بنفسِه. */
sealed interface DeleteOutcome {
    data class Done(val count: Int) : DeleteOutcome
    /** أندرويد 11 فما فوق يطلبُ موافقةً صريحةً على ملفّاتٍ لم يَعُد التطبيقُ مالكَها. */
    data class NeedsConsent(val sender: IntentSender) : DeleteOutcome
    data class Failed(val message: String) : DeleteOutcome
}

/**
 * كلُّ ما ينزّله GMD يُودَعُ في `Movies/GMD` و`Music/GMD` عبرَ MediaStore، وهذا
 * المكوِّنُ يقرأُ ذلك المخزنَ ويتصرّفُ فيه.
 *
 * لا يُفتَحُ ملفٌّ بمسارِه ولا يُشارَكُ بـ`file://`: أندرويد يرفضُ ذلك منذ السابعة،
 * والمقبولُ عنوانُ `content://` نفسُه الذي أعادَه MediaStore عندَ الحفظ.
 */
object MediaLibrary {

    private const val FOLDER = "GMD"

    /**
     * أذونُ قراءةِ الوسائطِ اللازمةُ لرؤيةِ ملفّاتٍ لم يَعُد التطبيقُ مالكَها.
     *
     * أندرويد يُري التطبيقَ ما أنشأه بلا إذن، لكنّه يُسقِطُ المِلكيّةَ إن أُزيلَ
     * التطبيقُ ثمّ أُعيدَ تثبيتُه — لا عندَ التحديثِ فوقَه — فتختفي مقاطعُ المستخدمِ
     * من المعرضِ وهي باقيةٌ في `Movies/GMD`. وهذه الأذونُ تُعيدُها إلى الرؤية.
     */
    fun readPermissions(): Array<String> =
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            arrayOf(
                android.Manifest.permission.READ_MEDIA_VIDEO,
                android.Manifest.permission.READ_MEDIA_AUDIO,
            )
        } else {
            @Suppress("DEPRECATION")
            arrayOf(android.Manifest.permission.READ_EXTERNAL_STORAGE)
        }

    fun hasReadPermission(context: Context): Boolean =
        readPermissions().any {
            ContextCompat.checkSelfPermission(context, it) == PackageManager.PERMISSION_GRANTED
        }

    suspend fun list(context: Context): List<MediaEntry> = withContext(Dispatchers.IO) {
        (query(context, isAudio = false) + query(context, isAudio = true))
            .sortedByDescending { it.addedSeconds }
    }

    private fun query(context: Context, isAudio: Boolean): List<MediaEntry> {
        val collection = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            if (isAudio) MediaStore.Audio.Media.getContentUri(MediaStore.VOLUME_EXTERNAL)
            else MediaStore.Video.Media.getContentUri(MediaStore.VOLUME_EXTERNAL)
        } else {
            if (isAudio) MediaStore.Audio.Media.EXTERNAL_CONTENT_URI
            else MediaStore.Video.Media.EXTERNAL_CONTENT_URI
        }

        val columns = arrayOf(
            MediaStore.MediaColumns._ID,
            MediaStore.MediaColumns.DISPLAY_NAME,
            MediaStore.MediaColumns.MIME_TYPE,
            MediaStore.MediaColumns.SIZE,
            MediaStore.MediaColumns.DURATION,
            MediaStore.MediaColumns.DATE_ADDED,
        )

        // RELATIVE_PATH لم يوجد قبلَ أندرويد 10، فيُرشَّحُ هناك بالمسارِ الخام.
        val (where, args) = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            val root = if (isAudio) Environment.DIRECTORY_MUSIC else Environment.DIRECTORY_MOVIES
            "${MediaStore.MediaColumns.RELATIVE_PATH} LIKE ?" to arrayOf("$root/$FOLDER%")
        } else {
            val root = if (isAudio) Environment.DIRECTORY_MUSIC else Environment.DIRECTORY_MOVIES
            @Suppress("DEPRECATION")
            "${MediaStore.MediaColumns.DATA} LIKE ?" to arrayOf("%/$root/$FOLDER/%")
        }

        val out = mutableListOf<MediaEntry>()
        context.contentResolver.query(
            collection, columns, where, args,
            "${MediaStore.MediaColumns.DATE_ADDED} DESC",
        )?.use { c ->
            val iId = c.getColumnIndexOrThrow(MediaStore.MediaColumns._ID)
            val iName = c.getColumnIndexOrThrow(MediaStore.MediaColumns.DISPLAY_NAME)
            val iMime = c.getColumnIndexOrThrow(MediaStore.MediaColumns.MIME_TYPE)
            val iSize = c.getColumnIndexOrThrow(MediaStore.MediaColumns.SIZE)
            val iDur = c.getColumnIndexOrThrow(MediaStore.MediaColumns.DURATION)
            val iDate = c.getColumnIndexOrThrow(MediaStore.MediaColumns.DATE_ADDED)
            while (c.moveToNext()) {
                val id = c.getLong(iId)
                out += MediaEntry(
                    uri = ContentUris.withAppendedId(collection, id),
                    id = id,
                    name = c.getString(iName) ?: "—",
                    mime = c.getString(iMime) ?: if (isAudio) "audio/*" else "video/*",
                    sizeBytes = c.getLong(iSize),
                    durationMs = if (c.isNull(iDur)) 0L else c.getLong(iDur),
                    addedSeconds = c.getLong(iDate),
                    isAudio = isAudio,
                )
            }
        }
        return out
    }

    /** صورةٌ مصغَّرةٌ من الملفِّ نفسِه — لا مِن الشبكة، فالمقطعُ صارَ محليّاً. */
    suspend fun thumbnail(context: Context, entry: MediaEntry): Bitmap? =
        withContext(Dispatchers.IO) {
            runCatching {
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                    context.contentResolver.loadThumbnail(entry.uri, Size(512, 512), null)
                } else {
                    @Suppress("DEPRECATION")
                    if (entry.isAudio) null
                    else MediaStore.Video.Thumbnails.getThumbnail(
                        context.contentResolver, entry.id,
                        MediaStore.Video.Thumbnails.MINI_KIND, null,
                    )
                }
            }.getOrNull()
        }

    /** يفتحُ المقطعَ في مشغّلِ النظام. */
    fun viewIntent(entry: MediaEntry): Intent =
        Intent(Intent.ACTION_VIEW)
            .setDataAndType(entry.uri, entry.mime)
            .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_ACTIVITY_NEW_TASK)

    /** مشاركةُ مقطعٍ أو عدّةِ مقاطعَ في ورقةِ مشاركةٍ واحدة. */
    fun shareIntent(entries: List<MediaEntry>): Intent {
        val type = when {
            entries.all { it.isAudio } -> "audio/*"
            entries.none { it.isAudio } -> "video/*"
            else -> "*/*"
        }
        val base = if (entries.size == 1) {
            Intent(Intent.ACTION_SEND).putExtra(Intent.EXTRA_STREAM, entries.first().uri)
        } else {
            Intent(Intent.ACTION_SEND_MULTIPLE)
                .putParcelableArrayListExtra(Intent.EXTRA_STREAM, ArrayList(entries.map { it.uri }))
        }
        return Intent.createChooser(
            base.setType(type).addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION), null,
        ).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
    }

    /**
     * الحذفُ المباشرُ يكفي لملفّاتٍ ما زالَ التطبيقُ مالكَها. وإن أعادَ المستخدمُ
     * تثبيتَ التطبيقِ فقدَ مِلكيّتَها، فيَرمي النظامُ استثناءَ أمنٍ ويصيرُ الطريقُ
     * طلبَ موافقةٍ صريحةً منه — وهي شاشةُ النظامِ لا شاشتُنا.
     */
    suspend fun delete(context: Context, entries: List<MediaEntry>): DeleteOutcome =
        withContext(Dispatchers.IO) {
            val uris = entries.map { it.uri }
            if (uris.isEmpty()) return@withContext DeleteOutcome.Done(0)
            try {
                var n = 0
                uris.forEach { n += context.contentResolver.delete(it, null, null) }
                DeleteOutcome.Done(n)
            } catch (e: SecurityException) {
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
                    DeleteOutcome.NeedsConsent(
                        MediaStore.createDeleteRequest(context.contentResolver, uris).intentSender
                    )
                } else {
                    DeleteOutcome.Failed(e.message ?: e::class.java.name)
                }
            } catch (e: Throwable) {
                DeleteOutcome.Failed(e.message ?: e::class.java.name)
            }
        }

    fun formatSize(bytes: Long): String = when {
        bytes >= 1_073_741_824 -> "%.1f GB".format(bytes / 1_073_741_824.0)
        bytes >= 1_048_576 -> "%.1f MB".format(bytes / 1_048_576.0)
        bytes >= 1024 -> "%.0f KB".format(bytes / 1024.0)
        else -> "$bytes B"
    }

    fun formatDuration(ms: Long): String? {
        if (ms <= 0) return null
        val s = ms / 1000
        val h = s / 3600; val m = (s % 3600) / 60; val sec = s % 60
        return if (h > 0) "%d:%02d:%02d".format(h, m, sec) else "%d:%02d".format(m, sec)
    }
}
