package com.example.bluewave_mobile.ui.theme

import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color
import com.example.bluewave_mobile.preferences.ThemeMode

private val LightColorScheme = lightColorScheme(
    primary = BrandBlue,
    onPrimary = Color.White,
    primaryContainer = BrandContainerLight,
    onPrimaryContainer = BrandBlueDark,
    secondary = AccentCyan,
    onSecondary = Color.White,
    secondaryContainer = BrandContainerLight,
    onSecondaryContainer = BrandBlueDark,
    tertiary = AccentIndigo,
    onTertiary = Color.White,
    background = NeutralBackgroundLight,
    onBackground = NeutralOnSurfaceLight,
    surface = NeutralSurfaceLight,
    onSurface = NeutralOnSurfaceLight,
    surfaceVariant = NeutralSurfaceVariantLight,
    onSurfaceVariant = NeutralOnSurfaceVariantLight,
    outline = NeutralOutlineLight,
    outlineVariant = NeutralOutlineLight,
    error = DangerRed,
    onError = Color.White,
)

private val DarkColorScheme = darkColorScheme(
    primary = BrandBlueLight,
    onPrimary = Color.Black,
    primaryContainer = BrandContainerDark,
    onPrimaryContainer = BrandBlueLight,
    secondary = AccentCyan,
    onSecondary = Color.Black,
    secondaryContainer = BrandContainerDark,
    onSecondaryContainer = BrandBlueLight,
    tertiary = AccentIndigo,
    onTertiary = Color.White,
    background = NeutralBackgroundDark,
    onBackground = NeutralOnSurfaceDark,
    surface = NeutralSurfaceDark,
    onSurface = NeutralOnSurfaceDark,
    surfaceVariant = NeutralSurfaceVariantDark,
    onSurfaceVariant = NeutralOnSurfaceVariantDark,
    outline = NeutralOutlineDark,
    outlineVariant = NeutralOutlineDark,
    error = DangerRed,
    onError = Color.Black,
)

/**
 * Wraps [content] in the BlueWave [MaterialTheme] color scheme,
 * resolving [themeMode] against `isSystemInDarkTheme()` for
 * [ThemeMode.SYSTEM].
 */
@Composable
fun BlueWaveTheme(
    themeMode: ThemeMode = ThemeMode.SYSTEM,
    content: @Composable () -> Unit,
) {
    val darkTheme = when (themeMode) {
        ThemeMode.SYSTEM -> isSystemInDarkTheme()
        ThemeMode.LIGHT -> false
        ThemeMode.DARK -> true
    }
    val colorScheme = if (darkTheme) DarkColorScheme else LightColorScheme

    MaterialTheme(
        colorScheme = colorScheme,
        typography = BlueWaveTypography,
        shapes = BlueWaveShapes,
        content = content,
    )
}
