package com.dev2go.ui.theme

import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.runtime.Composable

private val Dev2goColors = darkColorScheme(
    primary = Accent,
    onPrimary = Ground,
    background = Ground,
    onBackground = TextPrimary,
    surface = Surface,
    onSurface = TextPrimary,
    surfaceVariant = SurfaceHigh,
    onSurfaceVariant = TextSecondary,
    outline = Outline,
    outlineVariant = Outline,
    error = Negative,
    onError = Ground,
)

/**
 * Dark only, for now. The app is a developer tool and almost always sits next to a
 * terminal; a light scheme is worth adding once there is a real palette behind it rather
 * than as a mechanical inversion.
 */
@Composable
fun Dev2goTheme(content: @Composable () -> Unit) {
    @Suppress("UNUSED_EXPRESSION")
    isSystemInDarkTheme()
    MaterialTheme(colorScheme = Dev2goColors, content = content)
}