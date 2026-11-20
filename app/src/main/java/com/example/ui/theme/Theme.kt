package com.example.ui.theme

import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Shapes
import androidx.compose.material3.darkColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.unit.dp

private val YutaCyberColorScheme = darkColorScheme(
    primary = NeonCyan,
    onPrimary = DeepBlack,
    primaryContainer = CyberElevatedSurface,
    onPrimaryContainer = NeonCyan,
    secondary = ElectricBlue,
    onSecondary = PureWhite,
    secondaryContainer = CyberCardSurface,
    onSecondaryContainer = PureWhite,
    tertiary = NeonPurple,
    onTertiary = PureWhite,
    tertiaryContainer = CyberElevatedSurface,
    onTertiaryContainer = NeonPurple,
    background = DeepBlack,
    onBackground = PureWhite,
    surface = ObsidianSurface,
    onSurface = PureWhite,
    surfaceVariant = CyberCardSurface,
    onSurfaceVariant = CyberTextSecondary,
    outline = CyberBorderSubtle,
    outlineVariant = CyberBorderSubtle,
    error = ViolationCrimson,
    onError = PureWhite
)

val YutaShapes = Shapes(
    small = RoundedCornerShape(10.dp),
    medium = RoundedCornerShape(16.dp),
    large = RoundedCornerShape(24.dp)
)

@Composable
fun YutaAiTheme(
    content: @Composable () -> Unit
) {
    MaterialTheme(
        colorScheme = YutaCyberColorScheme,
        typography = YutaTypography,
        shapes = YutaShapes,
        content = content
    )
}
