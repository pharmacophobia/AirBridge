package com.airbridge.app.ui

import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color

val BgDark = Color(0xFF0B0F19)
val SurfaceDark = Color(0xFF1E293B)
val CardDark = Color(0xFF151E2E)
val CyanAccent = Color(0xFF06B6D4)
val EmeraldGreen = Color(0xFF10B981)
val CrimsonRed = Color(0xFFEF4444)
val AmberWarning = Color(0xFFF59E0B)
val TextPrimary = Color(0xFFF8FAFC)
val TextSecondary = Color(0xFF94A3B8)
val BorderDark = Color(0xFF334155)

private val DarkColorScheme = darkColorScheme(
    primary = CyanAccent,
    onPrimary = Color.Black,
    secondary = EmeraldGreen,
    onSecondary = Color.Black,
    tertiary = AmberWarning,
    background = BgDark,
    surface = SurfaceDark,
    onBackground = TextPrimary,
    onSurface = TextPrimary,
    surfaceVariant = CardDark,
    onSurfaceVariant = TextSecondary,
    outline = BorderDark,
    error = CrimsonRed
)

@Composable
fun AirBridgeTheme(
    content: @Composable () -> Unit
) {
    MaterialTheme(
        colorScheme = DarkColorScheme,
        content = content
    )
}
