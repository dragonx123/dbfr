package com.jarvis.assistant.ui.theme

import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color

private val JarvisDarkColors = darkColorScheme(
    primary = JarvisAccent,
    onPrimary = JarvisBackground,
    secondary = JarvisAccentDim,
    background = JarvisBackground,
    onBackground = JarvisOnBackground,
    surface = JarvisSurface,
    onSurface = JarvisOnBackground,
    surfaceVariant = JarvisSurfaceVariant,
    onSurfaceVariant = JarvisOnSurfaceMuted,
    error = JarvisError,
)

private val JarvisLightColors = lightColorScheme(
    primary = JarvisAccentDim,
    onPrimary = Color.White,
    secondary = JarvisAccent,
    background = Color.White,
    onBackground = Color.Black,
    surface = Color(0xFFF2F5F7),
    onSurface = Color.Black,
    surfaceVariant = Color(0xFFE3E8EB),
    onSurfaceVariant = Color(0xFF44515A),
    error = JarvisError,
)

@Composable
fun JarvisTheme(
    darkTheme: Boolean = isSystemInDarkTheme(),
    content: @Composable () -> Unit,
) {
    MaterialTheme(
        colorScheme = if (darkTheme) JarvisDarkColors else JarvisLightColors,
        typography = JarvisTypography,
        content = content,
    )
}
