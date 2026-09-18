package com.dskmusic.lokate.ui.theme

import android.app.Activity
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.SideEffect
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.lerp
import androidx.compose.ui.graphics.luminance
import androidx.compose.ui.platform.LocalView
import androidx.core.view.WindowCompat
import com.dskmusic.lokate.util.ThemeMode

/**
 * Material3 no deriva automáticamente "container"/"on" a partir de solo primary/secondary
 * (eso solo pasa con dynamicColor, que necesita Android 12+ y el fondo del sistema). Sin esto,
 * componentes como FilterChip, Switch o el indicador de NavigationBarItem seguían saliendo con
 * el lila por defecto de Material aunque se cambiara el acento — de ahí que "no se aplicara".
 * Aproximación sencilla con mezclas de color (no es la paleta tonal HCT exacta de Material, pero
 * da resultados de aspecto correcto sin añadir una librería nueva solo para esto).
 */
private fun onColorFor(background: Color): Color =
    if (background.luminance() > 0.45f) Color(0xFF1A1A1A) else Color.White

private fun buildColorScheme(accent: Color, dark: Boolean, amoled: Boolean = false): androidx.compose.material3.ColorScheme {
    val container = if (dark) lerp(accent, Color.Black, 0.55f) else lerp(accent, Color.White, 0.78f)
    val onContainer = if (dark) lerp(accent, Color.White, 0.75f) else lerp(accent, Color.Black, 0.45f)

    return if (dark) {
        darkColorScheme(
            primary = accent,
            onPrimary = onColorFor(accent),
            primaryContainer = container,
            onPrimaryContainer = onContainer,
            secondary = accent,
            onSecondary = onColorFor(accent),
            secondaryContainer = container,
            onSecondaryContainer = onContainer,
            tertiary = accent,
            background = if (amoled) Color.Black else Color(0xFF121212),
            surface = if (amoled) Color.Black else Color(0xFF1A1A1A),
            surfaceVariant = if (amoled) Color(0xFF181818) else Color(0xFF262626),
        )
    } else {
        lightColorScheme(
            primary = accent,
            onPrimary = onColorFor(accent),
            primaryContainer = container,
            onPrimaryContainer = onContainer,
            secondary = accent,
            onSecondary = onColorFor(accent),
            secondaryContainer = container,
            onSecondaryContainer = onContainer,
            tertiary = accent,
        )
    }
}

@Composable
fun LokateTheme(
    themeMode: ThemeMode,
    accentColor: Color,
    content: @Composable () -> Unit,
) {
    val systemDark = isSystemInDarkTheme()
    val darkTheme = when (themeMode) {
        ThemeMode.LIGHT -> false
        ThemeMode.DARK, ThemeMode.AMOLED -> true
        ThemeMode.SYSTEM -> systemDark
    }

    val colorScheme = buildColorScheme(accentColor, darkTheme, amoled = themeMode == ThemeMode.AMOLED)

    val view = LocalView.current
    if (!view.isInEditMode) {
        SideEffect {
            val window = (view.context as Activity).window
            WindowCompat.getInsetsController(window, view).isAppearanceLightStatusBars = !darkTheme
        }
    }

    MaterialTheme(
        colorScheme = colorScheme,
        typography = LokateTypography,
        shapes = LokateShapes,
        content = content,
    )
}
