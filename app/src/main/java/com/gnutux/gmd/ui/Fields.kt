package com.gnutux.gmd.ui

import androidx.compose.material3.LocalTextStyle
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.ReadOnlyComposable
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.style.TextAlign

/**
 * نمطُ نصِّ حقولِ الإدخال: في وسطِ الحقلِ لا على حافّتِه.
 *
 * الحقلُ يحملُ أيقونةً أو زرَّ لصقٍ في طرفِه، وكانَ النصُّ ملتصقاً بأحدِهما فيبدو
 * مزاحماً له. والنمطُ مشترَكٌ لا مكرَّرٌ في كلِّ حقل: الروابطُ وحدودُ الاقتصاصِ
 * وأسماءُ القوائمِ تستوي، وهو النمطُ نفسُه الذي اعتمدَتْه نسخةُ الحاسوب.
 */
val centeredFieldStyle: TextStyle
    @Composable @ReadOnlyComposable
    get() = LocalTextStyle.current.copy(textAlign = TextAlign.Center)
