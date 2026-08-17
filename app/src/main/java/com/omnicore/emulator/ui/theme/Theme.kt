package com.omnicore.emulator.ui.theme

import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color

private val OmniDark = darkColorScheme(
    primary = Color(0xFF9A7CFF),
    onPrimary = Color(0xFFFFFFFF),
    primaryContainer = Color(0xFF302760),
    onPrimaryContainer = Color(0xFFE9E2FF),
    secondary = Color(0xFF5EDBFF),
    onSecondary = Color(0xFF001F28),
    secondaryContainer = Color(0xFF123B48),
    background = Color(0xFF070812),
    onBackground = Color(0xFFF3F1FF),
    surface = Color(0xFF111426),
    onSurface = Color(0xFFF3F1FF),
    surfaceVariant = Color(0xFF1B1E33),
    onSurfaceVariant = Color(0xFFADB5CF),
    outline = Color(0xFF545B78),
    error = Color(0xFFFF7088)
)

@Composable
fun OmniCoreTheme(content: @Composable () -> Unit) {
    MaterialTheme(colorScheme = OmniDark, content = content)
}
