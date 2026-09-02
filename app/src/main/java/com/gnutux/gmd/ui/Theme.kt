package com.gnutux.gmd.ui

import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color

// لوحة GMD كما هي في نسخة سطح المكتب: أرضيّة شبه سوداء ولمسة حمراء
private val GmdRed        = Color(0xFFDC2626)
private val GmdRedLight   = Color(0xFFF87171)
private val DarkBackground = Color(0xFF0B0B0D)
private val DarkSurface    = Color(0xFF16161A)
private val DarkSurfaceAlt = Color(0xFF1F1F25)
private val DarkOutline    = Color(0xFF2E2E36)

private val DarkColors = darkColorScheme(
    primary = GmdRedLight,
    onPrimary = Color(0xFF1A0505),
    primaryContainer = Color(0xFF3B1010),
    onPrimaryContainer = Color(0xFFFFDAD6),
    secondary = Color(0xFF9CA3AF),
    background = DarkBackground,
    onBackground = Color(0xFFEDEDF0),
    surface = DarkSurface,
    onSurface = Color(0xFFEDEDF0),
    surfaceVariant = DarkSurfaceAlt,
    onSurfaceVariant = Color(0xFFB6B6BF),
    outline = DarkOutline,
    error = Color(0xFFFF8A80),
)

private val LightColors = lightColorScheme(
    primary = GmdRed,
    onPrimary = Color.White,
    primaryContainer = Color(0xFFFFDAD6),
    onPrimaryContainer = Color(0xFF410002),
    secondary = Color(0xFF6B7280),
    background = Color(0xFFFAF9F8),
    onBackground = Color(0xFF1A1A1D),
    surface = Color.White,
    onSurface = Color(0xFF1A1A1D),
    surfaceVariant = Color(0xFFF1EFEE),
    onSurfaceVariant = Color(0xFF4B4B52),
    outline = Color(0xFFDBD7D5),
)

@Composable
fun GmdTheme(dark: Boolean = isSystemInDarkTheme(), content: @Composable () -> Unit) {
    MaterialTheme(colorScheme = if (dark) DarkColors else LightColors, content = content)
}
