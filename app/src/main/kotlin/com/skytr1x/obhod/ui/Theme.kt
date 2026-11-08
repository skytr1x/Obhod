package com.skytr1x.obhod.ui

import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Shapes
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp

// m3e theme
private val LightScheme = lightColorScheme(
    primary = Color(0xFF000000),
    onPrimary = Color(0xFFFFFFFF),
    primaryContainer = Color(0xFFE2E2E2),
    onPrimaryContainer = Color(0xFF000000),
    inversePrimary = Color(0xFFC6C6C6),
    secondary = Color(0xFF5C5C5C),
    onSecondary = Color(0xFFFFFFFF),
    secondaryContainer = Color(0xFFE8E8E8),
    onSecondaryContainer = Color(0xFF111111),
    tertiary = Color(0xFF3C3C3C),
    onTertiary = Color(0xFFFFFFFF),
    tertiaryContainer = Color(0xFFDDDDDD),
    onTertiaryContainer = Color(0xFF111111),
    background = Color(0xFFF7F7F7),
    onBackground = Color(0xFF1B1B1B),
    surface = Color(0xFFF7F7F7),
    onSurface = Color(0xFF1B1B1B),
    surfaceVariant = Color(0xFFE4E4E4),
    onSurfaceVariant = Color(0xFF474747),
    surfaceTint = Color(0xFF000000),
    inverseSurface = Color(0xFF2F2F2F),
    inverseOnSurface = Color(0xFFF1F1F1),
    error = Color(0xFF5C5C5C),
    onError = Color(0xFFFFFFFF),
    errorContainer = Color(0xFFE2E2E2),
    onErrorContainer = Color(0xFF1B1B1B),
    outline = Color(0xFF767676),
    outlineVariant = Color(0xFFC9C9C9),
    scrim = Color(0xFF000000),
    surfaceBright = Color(0xFFFBFBFB),
    surfaceDim = Color(0xFFDEDEDE),
    surfaceContainerLowest = Color(0xFFFFFFFF),
    surfaceContainerLow = Color(0xFFF2F2F2),
    surfaceContainer = Color(0xFFEDEDED),
    surfaceContainerHigh = Color(0xFFE7E7E7),
    surfaceContainerHighest = Color(0xFFE1E1E1)
)

private val DarkScheme = darkColorScheme(
    primary = Color(0xFFFFFFFF),
    onPrimary = Color(0xFF000000),
    primaryContainer = Color(0xFF2A2A2A),
    onPrimaryContainer = Color(0xFFFFFFFF),
    inversePrimary = Color(0xFF3D3D3D),
    secondary = Color(0xFFC7C7C7),
    onSecondary = Color(0xFF111111),
    secondaryContainer = Color(0xFF303030),
    onSecondaryContainer = Color(0xFFEAEAEA),
    tertiary = Color(0xFFDDDDDD),
    onTertiary = Color(0xFF111111),
    tertiaryContainer = Color(0xFF383838),
    onTertiaryContainer = Color(0xFFF0F0F0),
    background = Color(0xFF111111),
    onBackground = Color(0xFFEAEAEA),
    surface = Color(0xFF111111),
    onSurface = Color(0xFFEAEAEA),
    surfaceVariant = Color(0xFF262626),
    onSurfaceVariant = Color(0xFFC3C3C3),
    surfaceTint = Color(0xFFFFFFFF),
    inverseSurface = Color(0xFFE8E8E8),
    inverseOnSurface = Color(0xFF1B1B1B),
    error = Color(0xFFBDBDBD),
    onError = Color(0xFF1B1B1B),
    errorContainer = Color(0xFF3A3A3A),
    onErrorContainer = Color(0xFFEAEAEA),
    outline = Color(0xFF8F8F8F),
    outlineVariant = Color(0xFF444444),
    scrim = Color(0xFF000000),
    surfaceBright = Color(0xFF3A3A3A),
    surfaceDim = Color(0xFF111111),
    surfaceContainerLowest = Color(0xFF0C0C0C),
    surfaceContainerLow = Color(0xFF161616),
    surfaceContainer = Color(0xFF1B1B1B),
    surfaceContainerHigh = Color(0xFF232323),
    surfaceContainerHighest = Color(0xFF2C2C2C)
)

// shapes
private val ExpressiveShapes = Shapes(
    extraSmall = RoundedCornerShape(8.dp),
    small = RoundedCornerShape(12.dp),
    medium = RoundedCornerShape(16.dp),
    large = RoundedCornerShape(24.dp),
    extraLarge = RoundedCornerShape(32.dp)
)

@Composable
fun ObhodTheme(
    darkTheme: Boolean = isSystemInDarkTheme(),
    content: @Composable () -> Unit
) {
    MaterialTheme(
        colorScheme = if (darkTheme) DarkScheme else LightScheme,
        shapes = ExpressiveShapes,
        content = content
    )
}
