package com.aurora.companion.android.ui

import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color

val AuroraTeal = Color(0xFF4FE3C1)
val AuroraViolet = Color(0xFF9B7BFF)
val AuroraPink = Color(0xFFFF7AD9)
val Panel = Color(0xFF111418)
val PanelHi = Color(0xFF1A1F26)
val UserBubble = Color(0xFF2A2350)
val AuroraBrush = Brush.linearGradient(listOf(AuroraTeal, AuroraViolet, AuroraPink))

private val scheme = darkColorScheme(
    primary = AuroraTeal,
    onPrimary = Color.Black,
    secondary = AuroraViolet,
    onSecondary = Color.Black,
    background = Color.Black,
    onBackground = Color(0xFFE8EAED),
    surface = Panel,
    onSurface = Color(0xFFE8EAED),
    surfaceVariant = PanelHi,
    onSurfaceVariant = Color(0xFFB0B6BE),
    error = Color(0xFFFF6B6B),
)

@Composable
fun AuroraTheme(content: @Composable () -> Unit) {
    MaterialTheme(colorScheme = scheme, content = content)
}
