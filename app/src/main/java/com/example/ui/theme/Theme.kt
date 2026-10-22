package com.example.ui.theme

import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color

private val DarkColorScheme = darkColorScheme(
    primary = CyberEmerald,
    onPrimary = Color(0xFF042F21),
    primaryContainer = CyberEmeraldContainer,
    onPrimaryContainer = OnCyberEmeraldContainer,
    secondary = ElectricCyan,
    onSecondary = Color(0xFF042A36),
    secondaryContainer = ElectricCyanContainer,
    onSecondaryContainer = OnElectricCyanContainer,
    tertiary = AmberUnpaid,
    onTertiary = Color(0xFF331B00),
    tertiaryContainer = AmberUnpaidContainer,
    onTertiaryContainer = OnAmberUnpaidContainer,
    error = CrimsonError,
    onError = Color.White,
    errorContainer = CrimsonErrorContainer,
    onErrorContainer = OnCrimsonErrorContainer,
    background = ObsidianBackground,
    onBackground = Color(0xFFF1F5F9),
    surface = ObsidianSurface,
    onSurface = Color(0xFFF1F5F9),
    surfaceVariant = ObsidianSurfaceVariant,
    onSurfaceVariant = Color(0xFF94A3B8),
    outline = ObsidianOutline
)

private val LightColorScheme = lightColorScheme(
    primary = CyberEmeraldDark,
    onPrimary = Color.White,
    primaryContainer = Color(0xFFD1FAE5),
    onPrimaryContainer = Color(0xFF064E3B),
    secondary = Color(0xFF0891B2),
    onSecondary = Color.White,
    secondaryContainer = Color(0xFFCFFAFE),
    onSecondaryContainer = Color(0xFF164E63),
    tertiary = Color(0xFFD97706),
    onTertiary = Color.White,
    tertiaryContainer = Color(0xFFFEF3C7),
    onTertiaryContainer = Color(0xFF78350F),
    error = Color(0xFFDC2626),
    onError = Color.White,
    errorContainer = Color(0xFFFEE2E2),
    onErrorContainer = Color(0xFF7F1D1D),
    background = SlateLightBackground,
    onBackground = SlateDarkText,
    surface = SlateLightSurface,
    onSurface = SlateDarkText,
    surfaceVariant = SlateLightSurfaceVariant,
    onSurfaceVariant = SlateMutedText,
    outline = SlateLightOutline
)

@Composable
fun VeloLedgerTheme(
    darkTheme: Boolean = isSystemInDarkTheme(),
    content: @Composable () -> Unit
) {
    val colorScheme = if (darkTheme) DarkColorScheme else LightColorScheme

    MaterialTheme(
        colorScheme = colorScheme,
        typography = Typography,
        content = content
    )
}
