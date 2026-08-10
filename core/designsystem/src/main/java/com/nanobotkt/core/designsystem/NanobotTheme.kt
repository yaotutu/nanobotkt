package com.nanobotkt.core.designsystem

import android.os.Build
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.ColorScheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.dynamicDarkColorScheme
import androidx.compose.material3.dynamicLightColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.Immutable
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp

/**
 * Material 3 的固定浅色方案。
 *
 * 这里使用 Material Theme Builder 的基准紫色方案，而不是 Nanobot 旧的黑白/橙色调。
 * 固定方案是本阶段的刻意选择：在设计样板确认前，所有设备都应看到同一套角色颜色，
 * 这样才能判断颜色层级、对比度和组件状态本身，而不会被不同手机的动态壁纸干扰。
 */
val NanobotLightColorScheme: ColorScheme = lightColorScheme(
    primary = Color(0xFF6750A4),
    onPrimary = Color(0xFFFFFFFF),
    primaryContainer = Color(0xFFEADDFF),
    onPrimaryContainer = Color(0xFF21005D),
    inversePrimary = Color(0xFFD0BCFF),
    secondary = Color(0xFF625B71),
    onSecondary = Color(0xFFFFFFFF),
    secondaryContainer = Color(0xFFE8DEF8),
    onSecondaryContainer = Color(0xFF1D192B),
    tertiary = Color(0xFF7D5260),
    onTertiary = Color(0xFFFFFFFF),
    tertiaryContainer = Color(0xFFFFD8E4),
    onTertiaryContainer = Color(0xFF31111D),
    background = Color(0xFFFFFBFE),
    onBackground = Color(0xFF1C1B1F),
    surface = Color(0xFFFFFBFE),
    onSurface = Color(0xFF1C1B1F),
    surfaceVariant = Color(0xFFE7E0EC),
    onSurfaceVariant = Color(0xFF49454F),
    surfaceTint = Color(0xFF6750A4),
    inverseSurface = Color(0xFF313033),
    inverseOnSurface = Color(0xFFF4EFF4),
    outline = Color(0xFF79747E),
    outlineVariant = Color(0xFFCAC4D0),
    error = Color(0xFFB3261E),
    onError = Color(0xFFFFFFFF),
    errorContainer = Color(0xFFF9DEDC),
    onErrorContainer = Color(0xFF410E0B),
    scrim = Color(0xFF000000),
)

/**
 * Material 3 的固定深色方案。
 *
 * 深色不是简单地把浅色反相，而是使用一组独立的 tonal role，保证 primary container、
 * surfaceVariant 和错误状态在深色背景上仍然有清晰的层级与可读性。
 */
val NanobotDarkColorScheme: ColorScheme = darkColorScheme(
    primary = Color(0xFFD0BCFF),
    onPrimary = Color(0xFF381E72),
    primaryContainer = Color(0xFF4F378B),
    onPrimaryContainer = Color(0xFFEADDFF),
    inversePrimary = Color(0xFF6750A4),
    secondary = Color(0xFFCCC2DC),
    onSecondary = Color(0xFF332D41),
    secondaryContainer = Color(0xFF4A4458),
    onSecondaryContainer = Color(0xFFE8DEF8),
    tertiary = Color(0xFFEFB8C8),
    onTertiary = Color(0xFF492532),
    tertiaryContainer = Color(0xFF633B48),
    onTertiaryContainer = Color(0xFFFFD8E4),
    background = Color(0xFF1C1B1F),
    onBackground = Color(0xFFE6E1E5),
    surface = Color(0xFF1C1B1F),
    onSurface = Color(0xFFE6E1E5),
    surfaceVariant = Color(0xFF49454F),
    onSurfaceVariant = Color(0xFFCAC4D0),
    surfaceTint = Color(0xFFD0BCFF),
    inverseSurface = Color(0xFFE6E1E5),
    inverseOnSurface = Color(0xFF313033),
    outline = Color(0xFF938F99),
    outlineVariant = Color(0xFF49454F),
    error = Color(0xFFF2B8B5),
    onError = Color(0xFF601410),
    errorContainer = Color(0xFF8C1D18),
    onErrorContainer = Color(0xFFF9DEDC),
    scrim = Color(0xFF000000),
)

/**
 * 全局间距契约。
 *
 * 主间距遵循 4dp 基线与 8dp 节奏：4dp 用于图标/文字等紧邻元素，8dp 及以上用于布局层级。
 * touchTarget 固定为 48dp，避免“紧凑密度”误伤触控与 TalkBack 的最小目标尺寸。
 */
@Immutable
data class NanobotSpacing(
    val xxs: Dp = 4.dp,
    val xs: Dp = 8.dp,
    val sm: Dp = 12.dp,
    val md: Dp = 16.dp,
    val lg: Dp = 24.dp,
    val xl: Dp = 32.dp,
    val xxl: Dp = 40.dp,
    val touchTarget: Dp = 48.dp,
)

private val ComfortableSpacing = NanobotSpacing()

/**
 * 紧凑模式只压缩大间距，不改变 4dp 基线和 48dp 触控目标。
 * 这让已有的 density preference 继续有效，同时不再产生 2dp/6dp 等非 MD3 节奏值。
 */
private val CompactSpacing = NanobotSpacing(
    sm = 8.dp,
    md = 12.dp,
    lg = 16.dp,
    xl = 24.dp,
    xxl = 32.dp,
)

val LocalNanobotSpacing = staticCompositionLocalOf { ComfortableSpacing }

object NanobotThemeDefaults {
    /** 当前 CompositionLocal 中的全局间距令牌。 */
    val spacing: NanobotSpacing
        @Composable get() = LocalNanobotSpacing.current
}

/**
 * 应用唯一的 Material 3 主题入口。
 *
 * 默认使用固定 scheme，确保 Light/Dark 的视觉样板在不同设备上可复现；dynamicColor 仍保留为
 * 明确的可选能力，只有调用方主动开启且设备为 Android 12（API 31）以上时才读取系统动态色。
 * 这样既符合 MD3 的 dynamic color 能力，也不会在本阶段未经确认就改变产品基线。
 */
@Composable
fun NanobotTheme(
    darkTheme: Boolean = isSystemInDarkTheme(),
    dynamicColor: Boolean = false,
    compact: Boolean = false,
    content: @Composable () -> Unit,
) {
    val colorScheme = when {
        dynamicColor && Build.VERSION.SDK_INT >= Build.VERSION_CODES.S -> {
            val context = LocalContext.current
            if (darkTheme) dynamicDarkColorScheme(context) else dynamicLightColorScheme(context)
        }
        darkTheme -> NanobotDarkColorScheme
        else -> NanobotLightColorScheme
    }

    CompositionLocalProvider(LocalNanobotSpacing provides if (compact) CompactSpacing else ComfortableSpacing) {
        MaterialTheme(
            colorScheme = colorScheme,
            typography = NanobotTypography,
            shapes = NanobotShapes,
            content = content,
        )
    }
}
