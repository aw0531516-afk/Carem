package com.example.ui.theme

import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Shapes
import androidx.compose.material3.darkColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.ui.unit.dp

private val CarromDarkColorScheme = darkColorScheme(
    primary = TournamentEmerald,
    onPrimary = ObsidianEmerald,
    primaryContainer = EmeraldContainer,
    onPrimaryContainer = MintGlow,
    secondary = BurnishedBrass,
    onSecondary = ObsidianEmerald,
    secondaryContainer = BrassContainer,
    onSecondaryContainer = BrassBright,
    tertiary = CyanTelemetry,
    onTertiary = ObsidianEmerald,
    tertiaryContainer = CyanContainer,
    onTertiaryContainer = IvoryText,
    error = QueenCrimson,
    onError = IvoryText,
    errorContainer = CrimsonContainer,
    onErrorContainer = IvoryText,
    background = ObsidianEmerald,
    onBackground = IvoryText,
    surface = DeepFeltBackground,
    onSurface = IvoryText,
    surfaceVariant = SlateGreenSurface,
    onSurfaceVariant = SageMuted,
    surfaceContainerHigh = ElevatedGreenCard,
    outline = BorderEmerald,
    outlineVariant = SubtleDivider
)

val CarromShapes = Shapes(
    small = RoundedCornerShape(10.dp),
    medium = RoundedCornerShape(16.dp),
    large = RoundedCornerShape(22.dp),
    extraLarge = RoundedCornerShape(28.dp)
)

@Composable
fun CarromPilotTheme(
    content: @Composable () -> Unit
) {
    MaterialTheme(
        colorScheme = CarromDarkColorScheme,
        typography = CarromTypography,
        shapes = CarromShapes,
        content = content
    )
}
