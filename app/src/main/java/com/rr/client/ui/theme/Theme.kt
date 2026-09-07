package com.rr.client.ui.theme

import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color

private val DarkColorScheme = darkColorScheme(
    primary = BluePrimary,
    onPrimary = Color(0xFF112443),
    primaryContainer = BlueContainer,
    onPrimaryContainer = OnBlueContainer,
    inversePrimary = Color(0xFF365D9D),
    secondary = BlueSecondary,
    onSecondary = Color(0xFF1C293E),
    secondaryContainer = BlueContainer,
    onSecondaryContainer = OnBlueContainer,
    tertiary = Color(0xFFAFC5D8),
    onTertiary = Color(0xFF142C3C),
    tertiaryContainer = Color(0xFF2C4151),
    onTertiaryContainer = Color(0xFFD3E7F8),
    background = DarkBackground,
    onBackground = TextPrimary,
    surface = DarkSurface,
    onSurface = TextPrimary,
    surfaceVariant = DarkSurfaceVariant,
    onSurfaceVariant = TextSecondary,
    surfaceTint = BluePrimary,
    inverseSurface = TextPrimary,
    inverseOnSurface = DarkBackground,
    error = AccentRed,
    onError = Color(0xFF4E1616),
    errorContainer = Color(0xFF62302F),
    onErrorContainer = Color(0xFFFFDAD6),
    outline = Color(0xFF6F7787),
    outlineVariant = CardBorder,
    scrim = Color.Black,
    surfaceBright = Color(0xFF353A44),
    surfaceDim = DarkBackground,
    surfaceContainerLowest = Color(0xFF0D0F13),
    surfaceContainerLow = Color(0xFF171A20),
    surfaceContainer = DarkSurface,
    surfaceContainerHigh = Color(0xFF252932),
    surfaceContainerHighest = Color(0xFF303540)
)

@Composable
fun RRClientTheme(content: @Composable () -> Unit) {
    MaterialTheme(
        colorScheme = DarkColorScheme,
        content = content
    )
}
