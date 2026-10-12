package com.dev2go.ui.theme

import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Typography
import androidx.compose.material3.darkColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.sp

/**
 * Typography follows OpenChamber's own stack rather than the Material defaults.
 *
 * Sans is the system stack (`-apple-system, BlinkMacSystemFont, "Segoe UI", system-ui`) and
 * mono is `JetBrains Mono, "SF Mono", "Fira Code", ui-monospace`. Bundling a display font
 * would look better in English and fall back per-glyph elsewhere, which visibly breaks the
 * rhythm of a chat transcript — and shipping JetBrains Mono itself is a multi-megabyte
 * cost for something the system already approximates.
 */
private val Sans = FontFamily.SansSerif
private val Mono = FontFamily.Monospace

val Dev2goTypography = Typography(
    titleLarge = TextStyle(fontFamily = Sans, fontSize = 20.sp, fontWeight = FontWeight.SemiBold),
    titleMedium = TextStyle(fontFamily = Sans, fontSize = 16.sp, fontWeight = FontWeight.SemiBold),
    titleSmall = TextStyle(fontFamily = Sans, fontSize = 14.sp, fontWeight = FontWeight.Medium),
    bodyLarge = TextStyle(fontFamily = Sans, fontSize = 15.sp, lineHeight = 22.sp),
    bodyMedium = TextStyle(fontFamily = Sans, fontSize = 13.5.sp, lineHeight = 20.sp),
    bodySmall = TextStyle(fontFamily = Sans, fontSize = 12.sp, lineHeight = 17.sp),
    labelLarge = TextStyle(fontFamily = Sans, fontSize = 13.sp, fontWeight = FontWeight.Medium),
    labelMedium = TextStyle(fontFamily = Sans, fontSize = 11.5.sp),
    labelSmall = TextStyle(fontFamily = Sans, fontSize = 10.5.sp, fontWeight = FontWeight.Medium),
)

private val Dev2goColors = darkColorScheme(
    primary = OpenChamber.Primary,
    onPrimary = OpenChamber.Surface,
    primaryContainer = OpenChamber.PrimaryMuted,
    onPrimaryContainer = OpenChamber.Foreground,

    background = OpenChamber.Surface,
    onBackground = OpenChamber.Foreground,
    surface = OpenChamber.SurfaceElevated,
    onSurface = OpenChamber.Foreground,
    surfaceVariant = OpenChamber.SurfaceSubtle,
    onSurfaceVariant = OpenChamber.ForegroundMuted,
    surfaceContainer = OpenChamber.SurfaceElevated,
    surfaceContainerHigh = OpenChamber.SurfaceSubtle,

    outline = OpenChamber.Border,
    outlineVariant = OpenChamber.ChatDivider,

    error = OpenChamber.Error,
    onError = OpenChamber.Surface,
    errorContainer = OpenChamber.ToolRemovedBackground,
    onErrorContainer = OpenChamber.Error,

    secondary = OpenChamber.ForegroundMuted,
    onSecondary = OpenChamber.Surface,
    tertiary = OpenChamber.Warning,
    onTertiary = OpenChamber.Surface,
)

/**
 * Dark only.
 *
 * OpenChamber ships both variants and resolves one before first paint — including from a
 * blocking inline script, precisely so the WebView does not flash white on a cold launch.
 * That concern does not apply the same way to Compose, which paints its own surface, but
 * the window background is set to the same value so the cold-start frame still matches.
 */
@Composable
fun Dev2goTheme(content: @Composable () -> Unit) {
    MaterialTheme(
        colorScheme = Dev2goColors,
        typography = Dev2goTypography,
        content = content,
    )
}