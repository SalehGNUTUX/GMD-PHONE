package com.gnutux.gmd.history

import android.content.Context
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import org.json.JSONArray
import org.json.JSONObject
import java.io.File

/** ما آلت إليه محاولةُ تنزيل. */
enum class Outcome { SUCCESS, FAILED, CANCELLED }

/**
 * محاولةُ تنزيلٍ واحدة — لا ملفٌّ واحد.
 *
 * الفرقُ جوهريّ: المحاولةُ الفاشلةُ لا ملفَّ لها، والملفُّ الذي حذفَه المستخدمُ تبقى
 * محاولتُه. ولذلك كان السجلُّ شاشةً مستقلّةً عن المعرضِ لا امتداداً له.
 */
data class HistoryEntry(
    val id: Long,
    val url: String,
    val title: String?,
    val uploader: String?,
    val duration: String?,
    val thumbnail: String?,
    val isAudio: Boolean,
    /** اسمُ ثابتِ الجودةِ أو الصيغةِ كما اختارَه المستخدم، لإعادةِ المحاولةِ بمثلِه. */
    val choice: String,
    val outcome: Outcome,
    val error: String?,
    val savedUri: String?,
    val savedName: String?,
    val savedPath: String?,
    val timestamp: Long,
    /** حدّا المقطع بالثواني إن طُلب اقتصاص، لتُعاد المحاولة بمثله. */
    val sectionStart: Int? = null,
    val sectionEnd: Int? = null,
    /**
     * قائمةُ التشغيل تُسجَّل مدخلاً واحداً بمعلوماتها كاملةً لا مدخلاً لكلِّ ملفّ:
     * المستخدمُ طلبَ قائمةً فيُعرَض له ما طلب، لا ثلاثون سطراً متشابهاً.
     */
    val playlistTitle: String? = null,
    val playlistRequested: Int? = null,
    val playlistSaved: Int? = null,
) {
    val isPlaylist: Boolean get() = playlistRequested != null
    fun toJson(): JSONObject = JSONObject().apply {
        put("id", id)
        put("url", url)
        putOpt("title", title)
        putOpt("uploader", uploader)
        putOpt("duration", duration)
        putOpt("thumbnail", thumbnail)
        put("isAudio", isAudio)
        put("choice", choice)
        put("outcome", outcome.name)
        putOpt("error", error)
        putOpt("savedUri", savedUri)
        putOpt("savedName", savedName)
        putOpt("savedPath", savedPath)
        put("timestamp", timestamp)
        sectionStart?.let { put("sectionStart", it) }
        sectionEnd?.let { put("sectionEnd", it) }
        playlistTitle?.let { put("playlistTitle", it) }
        playlistRequested?.let { put("playlistRequested", it) }
        playlistSaved?.let { put("playlistSaved", it) }
    }

    companion object {
        fun fromJson(o: JSONObject): HistoryEntry? = runCatching {
            HistoryEntry(
                id = o.getLong("id"),
                url = o.getString("url"),
                title = o.optStringOrNull("title"),
                uploader = o.optStringOrNull("uploader"),
                duration = o.optStringOrNull("duration"),
                thumbnail = o.optStringOrNull("thumbnail"),
                isAudio = o.optBoolean("isAudio"),
                choice = o.optString("choice"),
                outcome = runCatching { Outcome.valueOf(o.optString("outcome")) }
                    .getOrDefault(Outcome.FAILED),
                error = o.optStringOrNull("error"),
                savedUri = o.optStringOrNull("savedUri"),
                savedName = o.optStringOrNull("savedName"),
                savedPath = o.optStringOrNull("savedPath"),
                timestamp = o.optLong("timestamp"),
                sectionStart = if (o.has("sectionStart")) o.optInt("sectionStart") else null,
                sectionEnd = if (o.has("sectionEnd")) o.optInt("sectionEnd") else null,
                playlistTitle = o.optStringOrNull("playlistTitle"),
                playlistRequested =
                    if (o.has("playlistRequested")) o.optInt("playlistRequested") else null,
                playlistSaved = if (o.has("playlistSaved")) o.optInt("playlistSaved") else null,
            )
        }.getOrNull()
    }
}

private fun JSONObject.optStringOrNull(key: String): String? =
    if (isNull(key)) null else optString(key).takeIf { it.isNotBlank() }

/**
 * سجلُّ المحاولاتِ، مخزَّناً ملفَّ JSON واحداً في `filesDir`.
 *
 * لا Room ولا `kotlinx.serialization`: المشروعُ لا يحملُ أيّاً منهما، وإدخالُ محرِّكِ
 * قاعدةِ بياناتٍ لأجلِ قائمةٍ مسطَّحةٍ سقفُها بضعُ مئاتٍ ثمنٌ بلا مقابل. و`org.json`
 * مضمَّنٌ في أندرويد نفسِه.
 *
 * والملفُّ في مساحةِ التطبيقِ الخاصّةِ ومستثنى من النسخِ الاحتياطيّ: الرابطُ قد يحملُ
 * رمزَ جلسةٍ أو معرّفاً خاصّاً بصاحبِه، فلا يخرجُ من الجهاز.
 */
object HistoryStore {

    private const val FILE = "history.json"

    /** سقفٌ يمنعُ النموَّ بلا حدّ؛ الأقدمُ يسقطُ أوّلاً. */
    const val MAX_ENTRIES = 500

    private val lock = Mutex()

    private fun file(context: Context) = File(context.filesDir, FILE)

    suspend fun all(context: Context): List<HistoryEntry> = withContext(Dispatchers.IO) {
        lock.withLock { read(context) }
    }

    /** يُضيفُ محاولةً إلى رأسِ السجلّ ويُعيدُ ما بقيَ بعدَ تطبيقِ السقف. */
    suspend fun add(context: Context, entry: HistoryEntry): Unit = withContext(Dispatchers.IO) {
        lock.withLock {
            val list = read(context).toMutableList()
            list.add(0, entry)
            write(context, list.take(MAX_ENTRIES))
        }
    }

    suspend fun remove(context: Context, ids: Set<Long>): Unit = withContext(Dispatchers.IO) {
        if (ids.isEmpty()) return@withContext
        lock.withLock {
            write(context, read(context).filterNot { it.id in ids })
        }
    }

    suspend fun clear(context: Context): Unit = withContext(Dispatchers.IO) {
        lock.withLock { file(context).delete() }
    }

    // ── القراءة والكتابة ─────────────────────────────────────────────────────
    // السجلُّ راحةٌ لا بياناتٌ لا تُعوَّض، فملفٌّ تالفٌ يُهمَل ولا يُسقِط التطبيق.

    private fun read(context: Context): List<HistoryEntry> {
        val f = file(context)
        if (!f.exists()) return emptyList()
        return runCatching {
            val arr = JSONArray(f.readText())
            (0 until arr.length()).mapNotNull { HistoryEntry.fromJson(arr.getJSONObject(it)) }
        }.getOrDefault(emptyList())
    }

    private fun write(context: Context, list: List<HistoryEntry>) {
        runCatching {
            val arr = JSONArray()
            list.forEach { arr.put(it.toJson()) }
            // كتابةٌ إلى ملفٍّ مؤقّتٍ ثمّ استبدال: قتلُ العمليّةِ في منتصفِ الكتابةِ
            // كان يتركُ سجلّاً مبتوراً يُهمَل كلُّه عند القراءة.
            val tmp = File(context.filesDir, "$FILE.tmp")
            tmp.writeText(arr.toString())
            if (!tmp.renameTo(file(context))) {
                file(context).writeText(arr.toString())
                tmp.delete()
            }
        }
    }
}
