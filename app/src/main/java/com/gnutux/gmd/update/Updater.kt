package com.gnutux.gmd.update

import android.content.Context
import android.content.Intent
import android.os.Build
import androidx.core.content.FileProvider
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.net.HttpURLConnection
import java.net.URL

/**
 * التحديث الذاتيّ لنسخة الهاتف.
 *
 * لا متجر يتولّى هذا: سياسة Google Play تمنع تطبيقات تنزيل يوتيوب صراحةً،
 * والتوزيع عبر F-Droid و APK على GitHub. فالتطبيق يفحص إصدارات GitHub بنفسه،
 * ويُنزّل حزمة معماريّة الجهاز، ثمّ يسلّمها لمُثبِّت النظام عبر FileProvider.
 *
 * التنزيل قابل للاستئناف بترويسة Range، ومُتحقَّق من حجمه قبل قبوله — نفس منطق
 * نسخة سطح المكتب، ولسببٍ أقوى هنا: اتّصال الهاتف ينقطع أكثر.
 */
object Updater {

    private const val REPO = "SalehGNUTUX/GMD-PHONE"
    private const val API = "https://api.github.com/repos/$REPO/releases?per_page=20"

    data class Asset(val name: String, val size: Long, val url: String)

    data class Check(
        val ok: Boolean,
        val error: String? = null,
        val updateAvailable: Boolean = false,
        val current: String = "",
        val version: String = "",
        val tag: String = "",
        val prerelease: Boolean = false,
        val notes: String = "",
        val releaseUrl: String = "",
        val asset: Asset? = null,
    )

    /**
     * رقم الإصدار مع لاحقة ما قبل الإصدار.
     *
     * إهمالُ اللاحقة كان يجعل `26.9.0-alpha.1` و`26.9.0-beta.1` سواءً في
     * المقارنة، فيقول التطبيقُ «أنت على أحدث إصدار» وهو على أقدمِهما — ولا
     * ينتقل بين إصدارَين تجريبيَّين يشتركان في الرقم أبداً.
     */
    private data class Version(
        val major: Int,
        val minor: Int,
        val patch: Int,
        /** معرّفات ما قبل الإصدار مفصولةً بنقطة؛ فارغةٌ في الإصدار المستقرّ. */
        val pre: List<String> = emptyList(),
    ) : Comparable<Version> {

        /** نصٌّ يعرضه للمستخدم كما هو في الوسم لا كما فُهم. */
        fun display(): String =
            "$major.$minor.$patch" + if (pre.isEmpty()) "" else "-" + pre.joinToString(".")

        override fun compareTo(other: Version): Int {
            major.compareTo(other.major).let { if (it != 0) return it }
            minor.compareTo(other.minor).let { if (it != 0) return it }
            patch.compareTo(other.patch).let { if (it != 0) return it }

            // المستقرُّ يسبق ما قبله عند تساوي الرقم: 26.9.0 أحدث من 26.9.0-beta.1
            if (pre.isEmpty() && other.pre.isEmpty()) return 0
            if (pre.isEmpty()) return 1
            if (other.pre.isEmpty()) return -1

            // ثمّ تُقارَن المعرّفات واحداً واحداً كما في semver: الرقميُّ يسبق
            // النصّيَّ، والرقميُّ يُقارَن عدداً لا حرفاً (‏10 بعد 9 لا قبلها).
            for (i in 0 until maxOf(pre.size, other.pre.size)) {
                val x = pre.getOrNull(i) ?: return -1
                val y = other.pre.getOrNull(i) ?: return 1
                val xn = x.toIntOrNull()
                val yn = y.toIntOrNull()
                val c = when {
                    xn != null && yn != null -> xn.compareTo(yn)
                    xn != null -> -1
                    yn != null -> 1
                    else -> x.compareTo(y)
                }
                if (c != 0) return c
            }
            return 0
        }
    }

    /** أوّل رقم منقَّط في الوسم مع لاحقته (v26.9.0-beta.1، GMD-26.09، 1.92…). */
    private val VERSION_RE =
        Regex("""(\d+)\.(\d+)(?:\.(\d+))?(?:-([0-9A-Za-z.-]+))?""")

    private fun parse(text: String?): Version? {
        val m = VERSION_RE.find(text.orEmpty()) ?: return null
        val pre = m.groupValues[4].takeIf { it.isNotBlank() }
            ?.split(".")?.filter { it.isNotBlank() }.orEmpty()
        return Version(
            m.groupValues[1].toInt(),
            m.groupValues[2].toInt(),
            m.groupValues[3].ifEmpty { "0" }.toInt(),
            pre,
        )
    }

    private fun isNewer(candidate: String?, current: String): Boolean {
        val a = parse(candidate) ?: return false
        val b = parse(current) ?: return false
        return a > b
    }

    /** معماريّة الجهاز أوّلاً، ثمّ الحزمة الموحّدة إن لم توجد. */
    private fun pickAsset(assets: List<Asset>): Asset? {
        if (assets.isEmpty()) return null
        val abis = Build.SUPPORTED_ABIS.toList()
        for (abi in abis) {
            assets.firstOrNull { it.name.contains(abi, ignoreCase = true) }?.let { return it }
        }
        return assets.firstOrNull { it.name.contains("universal", ignoreCase = true) }
    }

    suspend fun check(context: Context, allowPrerelease: Boolean): Check = withContext(Dispatchers.IO) {
        val current = currentVersion(context)
        val body = runCatching { httpGet(API) }.getOrElse {
            return@withContext Check(ok = false, error = it.message, current = current)
        }

        runCatching {
            val releases = JSONArray(body)
            var best: JSONObject? = null
            var bestV: Version? = null

            for (i in 0 until releases.length()) {
                val r = releases.getJSONObject(i)
                if (r.optBoolean("draft")) continue
                if (!allowPrerelease && r.optBoolean("prerelease")) continue
                val v = parse(r.optString("tag_name")) ?: continue
                if (bestV == null || v > bestV!!) { best = r; bestV = v }
            }

            val release = best ?: return@withContext Check(ok = true, current = current)
            val assets = buildList {
                val arr = release.optJSONArray("assets") ?: JSONArray()
                for (i in 0 until arr.length()) {
                    val a = arr.getJSONObject(i)
                    val name = a.optString("name")
                    if (name.endsWith(".apk", ignoreCase = true)) {
                        add(Asset(name, a.optLong("size"), a.optString("browser_download_url")))
                    }
                }
            }

            Check(
                ok = true,
                updateAvailable = isNewer(release.optString("tag_name"), current),
                current = current,
                version = bestV!!.display(),
                tag = release.optString("tag_name"),
                prerelease = release.optBoolean("prerelease"),
                notes = release.optString("body").take(4000),
                releaseUrl = release.optString("html_url"),
                asset = pickAsset(assets),
            )
        }.getOrElse { Check(ok = false, error = it.message, current = current) }
    }

    fun cacheDir(context: Context): File =
        File(context.cacheDir, "updates").apply { mkdirs() }

    /**
     * يُنزّل الحزمة، مستأنفاً ما نُزِّل سابقاً، ويرفض ملفّاً حجمه لا يطابق المُعلَن.
     * @param isCancelled يُستشار بين القطع، فالإلغاء لا يحتاج قتل الخيط.
     */
    suspend fun download(
        context: Context,
        asset: Asset,
        isCancelled: () -> Boolean,
        onProgress: (received: Long, total: Long) -> Unit,
    ): Result<File> = withContext(Dispatchers.IO) {
        runCatching {
            val dest = File(cacheDir(context), asset.name)
            if (dest.exists() && dest.length() == asset.size) return@runCatching dest

            val part = File(cacheDir(context), asset.name + ".part")
            var from = if (part.exists() && part.length() < asset.size) part.length() else 0L
            if (part.exists() && part.length() >= asset.size) { part.delete(); from = 0L }

            var url = URL(asset.url)
            var redirects = 0
            var connection: HttpURLConnection

            while (true) {
                connection = (url.openConnection() as HttpURLConnection).apply {
                    instanceFollowRedirects = false
                    connectTimeout = 20_000
                    readTimeout = 60_000
                    setRequestProperty("User-Agent", "GMD-PHONE/${currentVersion(context)}")
                    if (from > 0) setRequestProperty("Range", "bytes=$from-")
                }
                val code = connection.responseCode
                if (code in listOf(301, 302, 303, 307, 308)) {
                    val next = connection.getHeaderField("Location") ?: error("redirect without a target")
                    connection.disconnect()
                    if (++redirects > 5) error("too many redirects")
                    url = URL(next)
                    continue
                }
                // خادمٌ تجاهل Range يعيد 200 بالملفّ كاملاً، فنبدأ من الصفر
                if (code == 200 && from > 0) { from = 0L; part.delete() }
                if (code == 416) { part.renameTo(dest); return@runCatching dest }
                if (code != 200 && code != 206) error("HTTP $code")
                break
            }

            var received = from
            connection.inputStream.use { input ->
                java.io.FileOutputStream(part, from > 0).use { output ->
                    val buffer = ByteArray(64 * 1024)
                    while (true) {
                        if (isCancelled()) error("cancelled")
                        val n = input.read(buffer)
                        if (n <= 0) break
                        output.write(buffer, 0, n)
                        received += n
                        onProgress(received, asset.size)
                    }
                    output.flush()
                }
            }
            connection.disconnect()

            if (asset.size > 0 && part.length() != asset.size) {
                error("size mismatch: got ${part.length()}, expected ${asset.size}")
            }
            if (!part.renameTo(dest)) error("could not finalise the downloaded file")
            dest
        }
    }

    /**
     * يسلّم الحزمة لمُثبِّت النظام. لا يمكن للتطبيق أن يثبّت نفسه صامتاً — ولا ينبغي —
     * فالنظام يعرض شاشته الخاصّة ويطلب إذن "تثبيت تطبيقات غير معروفة" مرّةً واحدة.
     */
    fun install(context: Context, apk: File): Result<Unit> = runCatching {
        val uri = FileProvider.getUriForFile(context, "${context.packageName}.fileprovider", apk)
        val intent = Intent(Intent.ACTION_VIEW).apply {
            setDataAndType(uri, "application/vnd.android.package-archive")
            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_ACTIVITY_NEW_TASK)
        }
        context.startActivity(intent)
    }

    /** هل يسمح النظام لهذا التطبيق بتثبيت الحزم؟ (أندرويد 8 فما فوق) */
    fun canInstall(context: Context): Boolean =
        Build.VERSION.SDK_INT < Build.VERSION_CODES.O || context.packageManager.canRequestPackageInstalls()

    fun currentVersion(context: Context): String =
        runCatching {
            context.packageManager.getPackageInfo(context.packageName, 0).versionName
                ?.removeSuffix("-debug").orEmpty()
        }.getOrDefault("")

    private fun httpGet(spec: String): String {
        val connection = (URL(spec).openConnection() as HttpURLConnection).apply {
            connectTimeout = 20_000
            readTimeout = 20_000
            setRequestProperty("Accept", "application/vnd.github+json")
            setRequestProperty("User-Agent", "GMD-PHONE")
        }
        try {
            if (connection.responseCode == 403 &&
                connection.getHeaderField("x-ratelimit-remaining") == "0"
            ) error("rate-limited")
            if (connection.responseCode != 200) error("HTTP ${connection.responseCode}")
            return connection.inputStream.bufferedReader().use { it.readText() }
        } finally {
            connection.disconnect()
        }
    }
}
