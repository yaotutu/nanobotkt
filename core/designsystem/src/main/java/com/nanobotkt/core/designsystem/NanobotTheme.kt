package com.nanobotkt.core.designsystem

import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.Immutable
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp

/**
 * Native equivalents of the neutral design tokens in the upstream WebUI's
 * `webui/src/globals.css`. Keeping these fixed avoids Android dynamic color
 * tinting the product UI purple/blue on different devices.
 */
private val NanobotLightColors = lightColorScheme(
    primary = Color(0xFF29292C),
    onPrimary = Color(0xFFFAFAFA),
    primaryContainer = Color(0xFFF5F5F5),
    onPrimaryContainer = Color(0xFF171717),
    secondary = Color(0xFFF5F5F5),
    onSecondary = Color(0xFF171717),
    tertiary = Color(0xFFEF8E30),
    onTertiary = Color.White,
    background = Color.White,
    onBackground = Color(0xFF1D1D1F),
    surface = Color.White,
    onSurface = Color(0xFF1D1D1F),
    surfaceVariant = Color(0xFFF7F7F6),
    onSurfaceVariant = Color(0xFF737373),
    outline = Color(0xFFE8E7E5),
    outlineVariant = Color(0xFFE8E7E5),
    error = Color(0xFFEF4444),
    onError = Color.White,
)

private val NanobotDarkColors = darkColorScheme(
    primary = Color(0xFFFAFAFA),
    onPrimary = Color(0xFF171717),
    primaryContainer = Color(0xFF404040),
    onPrimaryContainer = Color(0xFFFAFAFA),
    secondary = Color(0xFF383838),
    onSecondary = Color(0xFFFAFAFA),
    tertiary = Color(0xFFEF8E30),
    onTertiary = Color(0xFF171717),
    background = Color(0xFF303030),
    onBackground = Color(0xFFF5F5F6),
    surface = Color(0xFF383838),
    onSurface = Color(0xFFF5F5F6),
    surfaceVariant = Color(0xFF404040),
    onSurfaceVariant = Color(0xFFA6A6A6),
    outline = Color(0xFF474747),
    outlineVariant = Color(0xFF474747),
    error = Color(0xFFEF4444),
    onError = Color.White,
)

@Immutable
data class NanobotSpacing(
    val xxs: Dp,
    val xs: Dp,
    val sm: Dp,
    val md: Dp,
    val lg: Dp,
    val xl: Dp,
    val touchTarget: Dp = 48.dp,
)

private val ComfortableSpacing = NanobotSpacing(4.dp, 8.dp, 12.dp, 16.dp, 24.dp, 32.dp)
private val CompactSpacing = NanobotSpacing(2.dp, 6.dp, 8.dp, 12.dp, 18.dp, 24.dp)
val LocalNanobotSpacing = staticCompositionLocalOf { ComfortableSpacing }

object NanobotThemeDefaults {
    val spacing: NanobotSpacing
        @Composable get() = LocalNanobotSpacing.current
}

@Composable
fun NanobotTheme(
    darkTheme: Boolean = isSystemInDarkTheme(),
    dynamicColor: Boolean = false,
    compact: Boolean = false,
    content: @Composable () -> Unit,
) {
    // dynamicColor is retained for source compatibility, but the official
    // nanobot palette deliberately wins so every Android device matches WebUI.
    @Suppress("UNUSED_VARIABLE")
    val ignoredDynamicColor = dynamicColor
    CompositionLocalProvider(LocalNanobotSpacing provides if (compact) CompactSpacing else ComfortableSpacing) {
        MaterialTheme(
            colorScheme = if (darkTheme) NanobotDarkColors else NanobotLightColors,
            typography = NanobotTypography,
            shapes = NanobotShapes,
            content = content,
        )
    }
}
