package com.heartbeatheaven.app

import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color

private val HeartbeatDarkColors = darkColorScheme(
    primary = Color(0xFFFF6B81),
    onPrimary = Color(0xFF2A0010),
    primaryContainer = Color(0xFF5A1627),
    onPrimaryContainer = Color(0xFFFFD9DF),
    secondary = Color(0xFFFFB4C0),
    onSecondary = Color(0xFF3B0715),
    secondaryContainer = Color(0xFF5A1A2A),
    onSecondaryContainer = Color(0xFFFFD9DF),
    tertiary = Color(0xFFD6BAFF),
    onTertiary = Color(0xFF2A124F),
    tertiaryContainer = Color(0xFF43256F),
    onTertiaryContainer = Color(0xFFEBDDFF),
    background = Color(0xFF08090C),
    onBackground = Color(0xFFF1F1F5),
    surface = Color(0xFF0E1014),
    onSurface = Color(0xFFF1F1F5),
    surfaceVariant = Color(0xFF1B1E24),
    onSurfaceVariant = Color(0xFFC4C7CF),
    outline = Color(0xFF8E9199),
    outlineVariant = Color(0xFF42454D),
    error = Color(0xFFFFB4AB),
    onError = Color(0xFF690005),
    errorContainer = Color(0xFF93000A),
    onErrorContainer = Color(0xFFFFDAD6)
)

@Composable
internal fun HeartbeatHeavenTheme(content: @Composable () -> Unit) {
    MaterialTheme(
        colorScheme = HeartbeatDarkColors,
        content = content
    )
}
