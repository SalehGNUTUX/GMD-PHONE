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
     * يُغلِّفُ السياقَ باللغةِ المختارة. و«system» يُعيدُه كما هو، فلا نُثبِّتُ لغةً
     * على مَن لم يختر ولا نمنعُ الواجهةَ من متابعةِ تبديلِ لغةِ النظام.
     */
    fun wrap(base: Context): Context {
        val tag = get(base)
        if (tag == SYSTEM) return base
        val locale = Locale.forLanguageTag(tag)
        Locale.setDefault(locale)
        val config = Configuration(base.resources.configuration).apply {
            setLocale(locale)
            // الاتّجاهُ لا يتبعُ اللغةَ تلقائيّاً هنا، وبدونِه تبقى الواجهةُ
            // يَسارِيّةً مع العربيّة
            setLayoutDirection(locale)
        }
        return base.createConfigurationContext(config)
    }
}
