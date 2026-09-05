package com.gnutux.gmd.player

import android.content.Context
import org.json.JSONArray
import org.json.JSONObject

/** قائمةُ تشغيلٍ صنعَها المستخدمُ من مقاطعَ مفردة. */
data class UserPlaylist(
    val id: String,
    val name: String,
    /** عناوينُ `content://` بترتيبِ صاحبِها لا بترتيبِ التنزيل. */
    val uris: List<String>,
    /** نوعُ القائمة: الصوتُ يُسمَعُ في المشغّلِ الداخليّ والمرئيُّ لا يُسمَع. */
    val isAudio: Boolean,
    val createdAt: Long,
) {
    fun toJson(): JSONObject = JSONObject().apply {
        put("id", id); put("name", name); put("createdAt", createdAt)
        put("isAudio", isAudio)
        put("uris", JSONArray().also { a -> uris.forEach { a.put(it) } })
    }

    companion object {
        fun fromJson(o: JSONObject): UserPlaylist? = runCatching {
            val arr = o.getJSONArray("uris")
            UserPlaylist(
                id = o.getString("id"),
                name = o.optString("name"),
                uris = (0 until arr.length()).map { arr.getString(it) },
                isAudio = o.optBoolean("isAudio", true),
                createdAt = o.optLong("createdAt"),
            )
        }.getOrNull()
    }
}

/**
 * قوائمُ التشغيلِ التي يصنعُها المستخدم.
 *
 * ومجلَّداتُ `Movies/GMD/<اسم>` و`Music/GMD/<اسم>` قوائمُ أيضاً، لكنّها ما نزّله
 * المستخدمُ قائمةً واحدةً من المصدر؛ وهذه غيرُها: تجمعُ مقاطعَ مفردةً نُزِّلَت
 * فرادى في ترتيبٍ يختارُه صاحبُها. فلا تُنقَلُ ملفّاتٌ ولا تُنسَخُ — القائمةُ
 * عناوينُ لا مادّة، فحذفُها لا يمسُّ مقطعاً، وحذفُ مقطعٍ من المعرضِ يُسقِطُه من
 * كلِّ قائمةٍ تذكرُه عندَ العرض.
 *
 * وSharedPreferences لا DataStore: كتابةٌ نادرةٌ صغيرةٌ لا تستحقُّ طبقةً غيرَ
 * متزامنة، ومثلُها في [PlaybackStore].
 */
object PlaylistStore {

    private const val PREFS = "gmd_playlists"
    private const val KEY = "playlists"

    private fun prefs(context: Context) =
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)

    fun load(context: Context): List<UserPlaylist> {
        val raw = prefs(context).getString(KEY, null) ?: return emptyList()
        return runCatching {
            val arr = JSONArray(raw)
            (0 until arr.length()).mapNotNull { UserPlaylist.fromJson(arr.getJSONObject(it)) }
        }.getOrDefault(emptyList())
    }

    private fun save(context: Context, all: List<UserPlaylist>) {
        val arr = JSONArray()
        all.forEach { arr.put(it.toJson()) }
        prefs(context).edit().putString(KEY, arr.toString()).apply()
    }

    /** يُنشئُ قائمةً باسمِها ومقاطعِها الأولى، ويُعيدُها. */
    fun create(context: Context, name: String, uris: List<String>, isAudio: Boolean): UserPlaylist {
        val playlist = UserPlaylist(
            id = "pl-" + System.currentTimeMillis().toString(36),
            name = name.trim(),
            uris = uris.distinct(),
            isAudio = isAudio,
            createdAt = System.currentTimeMillis() / 1000,
        )
        save(context, load(context) + playlist)
        return playlist
    }

    /** يُضيفُ إلى قائمةٍ قائمةً؛ والمكرَّرُ لا يُضاف مرّتين. */
    fun addTo(context: Context, id: String, uris: List<String>) {
        save(context, load(context).map {
            if (it.id == id) it.copy(uris = (it.uris + uris).distinct()) else it
        })
    }

    fun removeFrom(context: Context, id: String, uri: String) {
        save(context, load(context).map {
            if (it.id == id) it.copy(uris = it.uris - uri) else it
        })
    }

    /** ترتيبُ القائمةِ من صنعِ صاحبِها: يُنقَلُ عنصرٌ خطوةً واحدةً في اتّجاه. */
    fun move(context: Context, id: String, from: Int, to: Int) {
        save(context, load(context).map { pl ->
            if (pl.id != id) return@map pl
            if (from !in pl.uris.indices || to !in pl.uris.indices) return@map pl
            val list = pl.uris.toMutableList()
            list.add(to, list.removeAt(from))
            pl.copy(uris = list)
        })
    }

    fun rename(context: Context, id: String, name: String) {
        save(context, load(context).map {
            if (it.id == id) it.copy(name = name.trim()) else it
        })
    }

    fun delete(context: Context, id: String) {
        save(context, load(context).filterNot { it.id == id })
    }
}
