package com.lezi.babylog.designsystem

import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color

private val Seed = Color(0xFF2F6FED)
private val LightColors = lightColorScheme(
    primary = Seed,
    onPrimary = Color.White,
    secondary = Color(0xFF5B6B8C),
    background = Color(0xFFF7F8FA),
    surface = Color.White,
)
private val DarkColors = darkColorScheme(
    primary = Color(0xFF8AB4FF),
    onPrimary = Color(0xFF002F6C),
    secondary = Color(0xFFB0BDD6),
    background = Color(0xFF101418),
    surface = Color(0xFF1A1F26),
)

@Composable
fun LeziTheme(
    darkTheme: Boolean = isSystemInDarkTheme(),
    content: @Composable () -> Unit,
) {
    MaterialTheme(
        colorScheme = if (darkTheme) DarkColors else LightColors,
        content = content,
    )
}
