package com.gnutux.gmd.data

import android.content.Context
import android.content.res.Configuration
import java.util.Locale

/**
 * لغةُ الواجهة: تتبعُ النظامَ أو تُفرَضُ عربيّةً أو إنجليزيّة.
 *
 * تُقرَأُ في `attachBaseContext` — أي قبلَ إنشاءِ أيِّ موردٍ — ولذلك لا تصلحُ لها
 * DataStore التي يعتمدُها بقيّةُ الإعدادات: قراءتُها مُعلَّقةٌ (suspend) واللحظةُ
 * المطلوبةُ متزامنة. فأُفرِدَت لها SharedPreferences، وهي كافيةٌ لقيمةٍ واحدةٍ
 * تُقرَأُ مرّةً عندَ الإقلاع.
 */
object LocalePrefs {

    /** اتّباعُ لغةِ النظام — وهو الأصل. */
    const val SYSTEM = "system"

    private const val FILE = "gmd-locale"
    private const val KEY = "ui_language"

    fun get(context: Context): String =
        context.getSharedPreferences(FILE, Context.MODE_PRIVATE).getString(KEY, SYSTEM) ?: SYSTEM

    fun set(context: Context, tag: String) {
        context.getSharedPreferences(FILE, Context.MODE_PRIVATE).edit().putString(KEY, tag).apply()
    }

    /**
     * يُغلِّفُ السياقَ باللغةِ المختارة. و«system» يتبعُ لغةَ النظام، فلا نُثبِّتُ
     * لغةً على مَن لم يختر ولا نمنعُ الواجهةَ من متابعةِ تبديلِ لغةِ النظام —
     * إلّا في شيءٍ واحد: نظامُ الأرقام (انظر [latinDigits]).
     */
    fun wrap(base: Context): Context {
        val tag = get(base)
        val chosen =
            if (tag == SYSTEM) base.resources.configuration.locales[0]
            else Locale.forLanguageTag(tag)
        val locale = latinDigits(chosen)

        // لغةُ النظامِ عربيّةٌ بأرقامٍ مغربيّةٍ أصلاً: لا حاجةَ إلى تغليفٍ
        if (tag == SYSTEM && locale == chosen) return base

        // `String.format` يقرأُ اللغةَ الافتراضيّةَ للعمليّةِ لا لغةَ الموارد،
        // فالساعاتُ والأحجامُ تُبنى بها. وتُضبَطُ هنا كي يستويَ الاثنان.
        Locale.setDefault(locale)
        val config = Configuration(base.resources.configuration).apply {
            setLocale(locale)
            // الاتّجاهُ لا يتبعُ اللغةَ تلقائيّاً هنا، وبدونِه تبقى الواجهةُ
            // يَسارِيّةً مع العربيّة
            setLayoutDirection(locale)
        }
        return base.createConfigurationContext(config)
    }

    /**
     * العربيّةُ بأرقامٍ **مغربيّةٍ 0-9** لا مشرقيّةٍ (هنديّة، نطاق U+0660).
     *
     * الأرقامُ في `%d` و`%.1f` يبنيها النظامُ بنظامِ أرقامِ اللغة، ونظامُ `ar`
     * المجرَّدةِ عندَ ICU هو `arab` — المشرقيّة. فكانَ عدَدُ العنصرِ في شاشةِ
     * التنزيلِ يخرجُ مشرقيّاً بينما بقيّةُ الأرقامِ مغربيّةٌ لأنّها نصٌّ لا صيغةُ
     * عدد، فيختلطُ النظامانِ في شاشةٍ واحدة.
     *
     * والعلاجُ لاحقةُ يونيكود `-u-nu-latn` على اللغةِ العربيّةِ وحدَها: تُبدّلُ
     * نظامَ الأرقامِ ولا تمسُّ شيئاً آخرَ من اللغةِ ولا اتّجاهَها. وتُطبَّقُ ولو
     * كانت اللغةُ متبوعةً من النظام، فالقاعدةُ في المشروعِ 0-9 حصراً.
     */
    private fun latinDigits(locale: Locale): Locale {
        if (locale.language != "ar") return locale
        if (locale.getUnicodeLocaleType("nu") == "latn") return locale
        return runCatching {
            Locale.Builder().setLocale(locale).setUnicodeLocaleKeyword("nu", "latn").build()
        }.getOrDefault(locale)
    }
}
