package com.example.ui.theme

import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Shapes
import androidx.compose.material3.darkColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.unit.dp

private val AutomotiveDarkColorScheme =
  darkColorScheme(
    primary = CyanPrimary,
    onPrimary = VoidBlack,
    primaryContainer = CyanPrimaryContainer,
    onPrimaryContainer = TextPrimary,
    secondary = EmeraldOptimal,
    onSecondary = VoidBlack,
    secondaryContainer = EmeraldContainer,
    onSecondaryContainer = TextPrimary,
    tertiary = AmberWarning,
    onTertiary = VoidBlack,
    tertiaryContainer = AmberContainer,
    onTertiaryContainer = TextPrimary,
    error = CoralCritical,
    onError = VoidBlack,
    errorContainer = CoralContainer,
    onErrorContainer = TextPrimary,
    background = VoidBlack,
    onBackground = TextPrimary,
    surface = SurfaceGraphite,
    onSurface = TextPrimary,
    surfaceVariant = SurfaceElevated,
    onSurfaceVariant = TextSecondary,
    outline = BorderHairline,
    outlineVariant = BorderHairline,
  )

val AppShapes =
  Shapes(
    small = RoundedCornerShape(10.dp),
    medium = RoundedCornerShape(14.dp),
    large = RoundedCornerShape(20.dp),
  )

@Composable
fun SCrossTelemetryTheme(
  content: @Composable () -> Unit,
) {
  MaterialTheme(
    colorScheme = AutomotiveDarkColorScheme,
    typography = Typography,
    shapes = AppShapes,
    content = content,
  )
}
