package com.omnicore.emulator.ui.theme

import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color

private val DarkColors = darkColorScheme(
    primary = Color(0xFF9B7BFF),
    secondary = Color(0xFF53D8FB),
    background = Color(0xFF0B0B10),
    surface = Color(0xFF14141D),
    surfaceVariant = Color(0xFF1E1E2A)
)

private val LightColors = lightColorScheme(
    primary = Color(0xFF6241C8),
    secondary = Color(0xFF00677A),
    background = Color(0xFFF8F7FC),
    surface = Color(0xFFFFFFFF)
)

@Composable
fun OmniCoreTheme(content: @Composable () -> Unit) {
    MaterialTheme(
        colorScheme = if (isSystemInDarkTheme()) DarkColors else LightColors,
        content = content
    )
}
