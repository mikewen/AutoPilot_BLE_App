package com.mikewen.autopilot.ui.theme

import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color

// ── Palette ───────────────────────────────────────────────────────────────────

val NavyDeep    = Color(0xFF0A1628)
val NavyMid     = Color(0xFF0D2240)
val NavyLight   = Color(0xFF1A3A5C)
val TealAccent  = Color(0xFF00BCD4)
val TealLight   = Color(0xFF4DD0E1)
val AmberWarn   = Color(0xFFFFC107)
val RedAlarm    = Color(0xFFF44336)
val GreenGo     = Color(0xFF4CAF50)
val SurfaceCard = Color(0xFF112233)
val OnSurface   = Color(0xFFCFDEEF)
val Muted       = Color(0xFF6B8EAE)

private val DarkColorScheme = darkColorScheme(
    primary          = TealAccent,
    onPrimary        = NavyDeep,
    primaryContainer = NavyLight,
    onPrimaryContainer = TealLight,
    secondary        = AmberWarn,
    onSecondary      = NavyDeep,
    background       = NavyDeep,
    onBackground     = OnSurface,
    surface          = SurfaceCard,
    onSurface        = OnSurface,
    surfaceVariant   = NavyMid,
    onSurfaceVariant = Muted,
    error            = RedAlarm,
    onError          = Color.White,
    outline          = NavyLight,
)

@Composable
fun AutoPilotTheme(content: @Composable () -> Unit) {
    MaterialTheme(
        colorScheme = DarkColorScheme,
        typography  = AutoPilotTypography,
        content     = content
    )
}
