package com.gnutux.gmd.data

import android.content.Context
import android.content.Intent
import androidx.core.content.FileProvider
import com.gnutux.gmd.R
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File

/**
 * مشاركةُ البرنامجِ نفسِه: نصّاً تعريفيّاً، أو حزمتَه.
 *
 * والنصُّ يُبنى من موارِدِ اللغةِ لا من نصٍّ مكتوبٍ في الشيفرة، فيخرجُ بلغةِ
 * الواجهةِ التي اختارَها المستخدم: من قرأَ البرنامجَ بالعربيّةِ يُشارِكُ عربيّةً،
 * ومن قرأَه بالإنجليزيّةِ يُشارِكُ إنجليزيّةً. والوسومُ في المورِدِ نفسِه فتتبدّلُ
 * معه.
 *
 * وهو المسلكُ نفسُه المعتمَدُ في GT-SALAT وGT-SPEEDOMETER.
 */
object Share {

    /** نصٌّ تعريفيٌّ بالبرنامج: نبذةٌ ثمّ الإصدارُ ثمّ الموقعُ والمستودَعُ والوسوم. */
    fun appText(context: Context): String = buildString {
        append(context.getString(R.string.share_pitch))
        append("\n\n").append(context.getString(R.string.share_version, version(context)))
        append("\n").append(context.getString(R.string.share_site_label)).append(": ")
            .append(SITE)
        append("\n").append(context.getString(R.string.share_repo_label)).append(": ")
            .append(REPO)
        append("\n").append(context.getString(R.string.share_desktop_label)).append(": ")
            .append(DESKTOP_REPO)
        append("\n\n").append(context.getString(R.string.share_hashtags))
    }

    /** الزرُّ الأوّل: نصٌّ ورابطٌ — لمن عندَه إنترنت. */
    fun shareText(context: Context) {
        val intent = Intent(Intent.ACTION_SEND).apply {
            type = "text/plain"
            putExtra(Intent.EXTRA_TEXT, appText(context))
        }
        open(context, intent, context.getString(R.string.share_app_text))
    }

    /**
     * الزرُّ الثاني: حزمةُ التثبيتِ نفسُها عبرَ تطبيقاتِ المراسلة — لمن لا إنترنتَ
     * عندَه.
     *
     * وحزمةُ التطبيقِ المثبَّتِ تُقرَأُ من `sourceDir`، وتُنسَخُ إلى الذاكرةِ المؤقّتةِ
     * باسمٍ يحملُ الإصدار: `FileProvider` لا يخدمُ مساراتِ النظامِ خارجَ ما أُعلِنَ
     * له، ولأنّ اسمَ `base.apk` لا يدلُّ على شيءٍ في يدِ من يستقبلُه.
     */
    suspend fun shareApk(context: Context): Boolean {
        val uri = withContext(Dispatchers.IO) {
            runCatching {
                val source = File(context.applicationInfo.sourceDir)
                val dir = File(context.cacheDir, "shared_apk").apply { mkdirs() }
                dir.listFiles()?.forEach { it.delete() }
                val dest = File(dir, "GMD-${version(context)}.apk")
                source.copyTo(dest, overwrite = true)
                FileProvider.getUriForFile(context, "${context.packageName}.fileprovider", dest)
            }.getOrNull()
        } ?: return false

        val intent = Intent(Intent.ACTION_SEND).apply {
            type = "application/vnd.android.package-archive"
            putExtra(Intent.EXTRA_STREAM, uri)
            putExtra(Intent.EXTRA_TEXT, appText(context))
            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        }
        return open(context, intent, context.getString(R.string.share_app_apk))
    }

    /**
     * رقمُ الإصدارِ من مديرِ الحزمِ لا من `BuildConfig`.
     *
     * توليدُ `BuildConfig` مطفأٌ في هذا المشروع، والمصدرُ الوحيدُ للرقمِ هو
     * `versionName` في `build.gradle.kts` — ومديرُ الحزمِ يقرؤه كما ثُبِّتَ فعلاً.
     * وهو ما يفعلُه [com.gnutux.gmd.update.Updater] نفسُه.
     */
    private fun version(context: Context): String = runCatching {
        context.packageManager.getPackageInfo(context.packageName, 0).versionName
    }.getOrNull().orEmpty()

    private fun open(context: Context, intent: Intent, title: String): Boolean =
        runCatching {
            context.startActivity(
                Intent.createChooser(intent, title).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            )
            true
        }.getOrDefault(false)

    const val SITE = "https://salehgnutux.github.io/GMD/"
    const val REPO = "https://github.com/SalehGNUTUX/GMD-PHONE"
    const val DESKTOP_REPO = "https://github.com/SalehGNUTUX/GMD"
}
