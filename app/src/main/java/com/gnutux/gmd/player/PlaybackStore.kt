package com.gnutux.gmd.player

import android.content.Context
import org.json.JSONArray
import org.json.JSONObject

/** مقطعٌ في صفِّ التشغيل. */
data class Track(
    val uri: String,
    val title: String,
    val durationMs: Long,
) {
    fun toJson(): JSONObject = JSONObject().apply {
        put("uri", uri); put("title", title); put("durationMs", durationMs)
    }

    companion object {
        fun fromJson(o: JSONObject): Track? = runCatching {
            Track(o.getString("uri"), o.optString("title"), o.optLong("durationMs"))
        }.getOrNull()
    }
}

/**
 * ما يُذكَرُ من الاستماعِ بينَ الجلسات.
 *
 * موضعُ كلِّ مقطعٍ محفوظٌ باسمِ عنوانِه، فمن تركَ كتاباً صوتيّاً في دقيقتِه الأربعين
 * يعودُ إليها لا إلى أوّلِه — وهي حاجةٌ في الموادِّ الطويلةِ لا زينة. ويُحفَظُ معها
 * صفُّ التشغيلِ الأخيرُ وموضعُه، فيجدُ المستخدمُ مشغّلَه كما تركَه بعدَ إغلاقِ
 * التطبيقِ لا فارغاً.
 *
 * وSharedPreferences لا DataStore: الكتابةُ تقعُ مع كلِّ إيقافٍ وكلِّ انتقالِ مقطعٍ
 * وعندَ هدمِ الخدمة، وهي لحظاتٌ لا تحتملُ قراءةً مُعلَّقة.
 */
object PlaybackStore {

    private const val PREFS = "gmd_playback"
    private const val KEY_QUEUE = "queue"
    private const val KEY_INDEX = "index"
    private const val KEY_POSITION = "position"
    private const val POS_PREFIX = "pos:"

    /** ما دونَ هذا لا يُعَدُّ موضعاً يُستأنَفُ منه: بدايةٌ عمليّاً. */
    private const val MIN_RESUME_MS = 10_000L

    /** وقربَ النهايةِ يُعادُ المقطعُ من أوّلِه بدلَ أن ينتهيَ فورَ تشغيلِه. */
    private const val END_MARGIN_MS = 15_000L

    private fun prefs(context: Context) =
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)

    /** موضعُ مقطعٍ بعينِه، أو صفرٌ إن لم يكن له موضعٌ يستحقُّ الاستئناف. */
    fun positionOf(context: Context, uri: String, durationMs: Long): Long {
        val saved = prefs(context).getLong(POS_PREFIX + uri, 0L)
        if (saved < MIN_RESUME_MS) return 0L
        if (durationMs > 0 && saved > durationMs - END_MARGIN_MS) return 0L
        return saved
    }

    fun savePosition(context: Context, uri: String, positionMs: Long) {
        prefs(context).edit().putLong(POS_PREFIX + uri, positionMs).apply()
    }

    fun clearPosition(context: Context, uri: String) {
        prefs(context).edit().remove(POS_PREFIX + uri).apply()
    }

    /** يحفظُ الصفَّ الجاريَ ليعودَ المشغّلُ كما تركَه صاحبُه. */
    fun saveQueue(context: Context, queue: List<Track>, index: Int, positionMs: Long) {
        val arr = JSONArray()
        queue.forEach { arr.put(it.toJson()) }
        prefs(context).edit()
            .putString(KEY_QUEUE, arr.toString())
            .putInt(KEY_INDEX, index)
            .putLong(KEY_POSITION, positionMs)
            .apply()
    }

    fun loadQueue(context: Context): Triple<List<Track>, Int, Long> {
        val raw = prefs(context).getString(KEY_QUEUE, null) ?: return Triple(emptyList(), 0, 0L)
        val list = runCatching {
            val arr = JSONArray(raw)
            (0 until arr.length()).mapNotNull { Track.fromJson(arr.getJSONObject(it)) }
        }.getOrDefault(emptyList())
        val index = prefs(context).getInt(KEY_INDEX, 0).coerceIn(0, maxOf(0, list.size - 1))
        return Triple(list, index, prefs(context).getLong(KEY_POSITION, 0L))
    }

    fun clearQueue(context: Context) {
        prefs(context).edit().remove(KEY_QUEUE).remove(KEY_INDEX).remove(KEY_POSITION).apply()
    }
}
