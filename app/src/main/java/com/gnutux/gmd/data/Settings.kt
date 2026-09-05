package com.gnutux.gmd.data

import android.content.Context
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.longPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map

private val Context.dataStore by preferencesDataStore("gmd-settings")

/** الإعدادات القليلة التي تحتاج البقاء بين الجلسات. */
class Settings(private val context: Context) {

    val autoCheckUpdates: Flow<Boolean> = context.dataStore.data.map { it[AUTO_CHECK] ?: true }
    val allowPrerelease: Flow<Boolean> = context.dataStore.data.map { it[ALLOW_PRE] ?: false }
    val lastUpdateCheck: Flow<Long> = context.dataStore.data.map { it[LAST_CHECK] ?: 0L }

    /**
     * شكلُ بطاقاتِ القائمةِ الرئيسة: مربَّعاتٌ اثنانِ في الصفِّ (الافتراض) أو
     * مستطيلاتٌ في عمودٍ واحد.
     *
     * والمربَّعُ افتراضاً كنسخةِ الحاسوب: تُرى ثمانيةُ أقسامٍ في شاشةٍ واحدةٍ بدلَ
     * أربعةٍ ونصف، ولكلِّ قسمٍ وصفُه تحتَ اسمِه.
     */
    val squareCards: Flow<Boolean> = context.dataStore.data.map { it[SQUARE_CARDS] ?: true }

    suspend fun setAutoCheckUpdates(value: Boolean) =
        context.dataStore.edit { it[AUTO_CHECK] = value }.let { }

    suspend fun setAllowPrerelease(value: Boolean) =
        context.dataStore.edit { it[ALLOW_PRE] = value }.let { }

    suspend fun setSquareCards(value: Boolean) =
        context.dataStore.edit { it[SQUARE_CARDS] = value }.let { }

    suspend fun markUpdateChecked() =
        context.dataStore.edit { it[LAST_CHECK] = System.currentTimeMillis() }.let { }

    companion object {
        private val AUTO_CHECK = booleanPreferencesKey("auto_check_updates")
        private val ALLOW_PRE = booleanPreferencesKey("allow_prerelease")
        private val LAST_CHECK = longPreferencesKey("last_update_check")
        private val SQUARE_CARDS = booleanPreferencesKey("square_cards")

        /** فاصل الفحص التلقائيّ: ستّ ساعات، حتّى لا يُستنفَد حدّ طلبات GitHub. */
        const val CHECK_INTERVAL_MS = 6L * 60 * 60 * 1000
    }
}
