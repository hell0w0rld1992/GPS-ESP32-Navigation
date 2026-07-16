package com.bikegps.android.ui.theme

import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color

private val LightColorScheme = lightColorScheme(
    primary = Color(0xFF1A73E8),
    onPrimary = Color.White,
    primaryContainer = Color(0xFFD3E3FD),
    background = Color.White,
    surface = Color(0xFFF8F9FA),
    surfaceVariant = Color(0xFFE8EAED),
    error = Color(0xFFD93025),
    onBackground = Color(0xFF202124),
    onSurface = Color(0xFF202124),
)

@Composable
fun BikeGPSTheme(content: @Composable () -> Unit) {
    MaterialTheme(
        colorScheme = LightColorScheme,
        content = content
    )
}
