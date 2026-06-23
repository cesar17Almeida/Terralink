package com.astralink.terralink.ui.theme

import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color

/**
 * App-wide theme. A calm, minimalist precision-agriculture palette: a leaf-green
 * primary, a sage secondary, and a water-teal tertiary over warm off-white
 * neutrals. Every screen inherits these tokens, so colour stays consistent
 * without hard-coding values in the UI. Light only for now (predictable for the
 * field demo); a dark scheme can be added later behind isSystemInDarkTheme().
 */
private val TerraLightColors = lightColorScheme(
    primary = Color(0xFF2E6A4B),
    onPrimary = Color(0xFFFFFFFF),
    primaryContainer = Color(0xFFB1F1C8),
    onPrimaryContainer = Color(0xFF00210F),
    secondary = Color(0xFF4E6355),
    onSecondary = Color(0xFFFFFFFF),
    secondaryContainer = Color(0xFFD1E8D7),
    onSecondaryContainer = Color(0xFF0C1F14),
    tertiary = Color(0xFF3A656E),
    onTertiary = Color(0xFFFFFFFF),
    tertiaryContainer = Color(0xFFBDEAF4),
    onTertiaryContainer = Color(0xFF001F25),
    error = Color(0xFFBA1A1A),
    onError = Color(0xFFFFFFFF),
    errorContainer = Color(0xFFFFDAD6),
    onErrorContainer = Color(0xFF410002),
    background = Color(0xFFF6FBF3),
    onBackground = Color(0xFF181D18),
    surface = Color(0xFFF6FBF3),
    onSurface = Color(0xFF181D18),
    surfaceVariant = Color(0xFFDBE5DC),
    onSurfaceVariant = Color(0xFF404942),
    outline = Color(0xFF707972),
    outlineVariant = Color(0xFFC0C9BF),
    surfaceTint = Color(0xFF2E6A4B),
    inverseSurface = Color(0xFF2D322C),
    inverseOnSurface = Color(0xFFEEF2EA),
    inversePrimary = Color(0xFF96D5AC),
    scrim = Color(0xFF000000),
    surfaceContainerLowest = Color(0xFFFFFFFF),
    surfaceContainerLow = Color(0xFFF0F5ED),
    surfaceContainer = Color(0xFFEBF0E8),
    surfaceContainerHigh = Color(0xFFE5EAE2),
    surfaceContainerHighest = Color(0xFFDFE4DD),
    surfaceBright = Color(0xFFF6FBF3),
    surfaceDim = Color(0xFFD7DBD3),
)

@Composable
fun TerraTheme(content: @Composable () -> Unit) {
    MaterialTheme(colorScheme = TerraLightColors, content = content)
}
