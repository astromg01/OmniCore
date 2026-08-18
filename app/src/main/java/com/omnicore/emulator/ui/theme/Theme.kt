package com.omnicore.emulator.ui.theme

import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color

private val OmniStarDark = darkColorScheme(
    primary = Color(0xFFA58BFF),
    onPrimary = Color(0xFFFFFFFF),
    primaryContainer = Color(0xFF342B64),
    onPrimaryContainer = Color(0xFFF1ECFF),
    secondary = Color(0xFF74DFFF),
    onSecondary = Color(0xFF002631),
    secondaryContainer = Color(0xFF123D4B),
    tertiary = Color(0xFFFFD85A),
    onTertiary = Color(0xFF352B00),
    background = Color(0xFF060712),
    onBackground = Color(0xFFF5F2FF),
    surface = Color(0xFF111324),
    onSurface = Color(0xFFF5F2FF),
    surfaceVariant = Color(0xFF1B1D32),
    onSurfaceVariant = Color(0xFFB3B8CF),
    outline = Color(0xFF5B607B),
    error = Color(0xFFFF728A)
)

@Composable
fun OmniCoreTheme(content: @Composable () -> Unit) {
    MaterialTheme(colorScheme = OmniStarDark, content = content)
}
