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
 * NanobotKT 的固定浅色角色。
 *
 * Material 3 已经定义颜色角色如何被组件消费，这里只做产品级选择：以低彩度靛蓝作为品牌强调，
 * 以冷中性色作为阅读背景。固定 scheme 可以避免壁纸动态色让 Gateway 状态、会话选中态等业务语义
 * 在不同设备上发生漂移；动态色仍作为主题入口的显式可选项保留。
 */
val NanobotLightColorScheme: ColorScheme = lightColorScheme(
    primary = Color(0xFF415F91),
    onPrimary = Color(0xFFFFFFFF),
    primaryContainer = Color(0xFFD6E3FF),
    onPrimaryContainer = Color(0xFF001B3E),
    inversePrimary = Color(0xFFAAC7FF),
    secondary = Color(0xFF565F71),
    onSecondary = Color(0xFFFFFFFF),
    secondaryContainer = Color(0xFFDAE2F9),
    onSecondaryContainer = Color(0xFF131C2B),
    tertiary = Color(0xFF705575),
    onTertiary = Color(0xFFFFFFFF),
    tertiaryContainer = Color(0xFFFAD8FD),
    onTertiaryContainer = Color(0xFF28132D),
    background = Color(0xFFF9F9FF),
    onBackground = Color(0xFF191C20),
    surface = Color(0xFFF9F9FF),
    onSurface = Color(0xFF191C20),
    surfaceVariant = Color(0xFFE0E2EC),
    onSurfaceVariant = Color(0xFF44474E),
    surfaceTint = Color(0xFF415F91),
    inverseSurface = Color(0xFF2E3036),
    inverseOnSurface = Color(0xFFF0F0F7),
    outline = Color(0xFF74777F),
    outlineVariant = Color(0xFFC4C6D0),
    error = Color(0xFFBA1A1A),
    onError = Color(0xFFFFFFFF),
    errorContainer = Color(0xFFFFDAD6),
    onErrorContainer = Color(0xFF410002),
    scrim = Color(0xFF000000),
)

/**
 * NanobotKT 的固定深色角色。
 *
 * 深色方案使用独立 tonal roles，而不是对浅色方案做机械反相。这样低强调 Surface、选中态容器和
 * 错误状态在暗色 Canvas 上仍能保持稳定对比，也避免所有容器都退化成同一层灰色。
 */
val NanobotDarkColorScheme: ColorScheme = darkColorScheme(
    primary = Color(0xFFAAC7FF),
    onPrimary = Color(0xFF0A305F),
    primaryContainer = Color(0xFF294777),
    onPrimaryContainer = Color(0xFFD6E3FF),
    inversePrimary = Color(0xFF415F91),
    secondary = Color(0xFFBEC6DC),
    onSecondary = Color(0xFF283141),
    secondaryContainer = Color(0xFF3E4759),
    onSecondaryContainer = Color(0xFFDAE2F9),
    tertiary = Color(0xFFDDBCE0),
    onTertiary = Color(0xFF3F2844),
    tertiaryContainer = Color(0xFF573E5C),
    onTertiaryContainer = Color(0xFFFAD8FD),
    background = Color(0xFF111318),
    onBackground = Color(0xFFE2E2E9),
    surface = Color(0xFF111318),
    onSurface = Color(0xFFE2E2E9),
    surfaceVariant = Color(0xFF44474E),
    onSurfaceVariant = Color(0xFFC4C6D0),
    surfaceTint = Color(0xFFAAC7FF),
    inverseSurface = Color(0xFFE2E2E9),
    inverseOnSurface = Color(0xFF2E3036),
    outline = Color(0xFF8E9099),
    outlineVariant = Color(0xFF44474E),
    error = Color(0xFFFFB4AB),
    onError = Color(0xFF690005),
    errorContainer = Color(0xFF93000A),
    onErrorContainer = Color(0xFFFFDAD6),
    scrim = Color(0xFF000000),
)

/**
 * Material 3 没有 Success 与 Warning 角色，因此只在这里补充 Nanobot 业务所需的语义颜色。
 * Active 继续使用 Material `primary`，Error 继续使用 Material `error`，避免复制 MD3 已有定义。
 */
@Immutable
data class NanobotStatusColors(
    val success: Color,
    val onSuccess: Color,
    val successContainer: Color,
    val onSuccessContainer: Color,
    val warning: Color,
    val onWarning: Color,
    val warningContainer: Color,
    val onWarningContainer: Color,
)

private val LightStatusColors = NanobotStatusColors(
    success = Color(0xFF2D6A4F),
    onSuccess = Color(0xFFFFFFFF),
    successContainer = Color(0xFFB9F0D0),
    onSuccessContainer = Color(0xFF002114),
    warning = Color(0xFF805600),
    onWarning = Color(0xFFFFFFFF),
    warningContainer = Color(0xFFFFDEA4),
    onWarningContainer = Color(0xFF281900),
)

private val DarkStatusColors = NanobotStatusColors(
    success = Color(0xFF9BD8B5),
    onSuccess = Color(0xFF003824),
    successContainer = Color(0xFF0C5038),
    onSuccessContainer = Color(0xFFB9F0D0),
    warning = Color(0xFFF7BD58),
    onWarning = Color(0xFF432C00),
    warningContainer = Color(0xFF614100),
    onWarningContainer = Color(0xFFFFDEA4),
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
 * 这让已有 density preference 继续有效，同时不产生非 Material 节奏值。
 */
private val CompactSpacing = NanobotSpacing(
    sm = 8.dp,
    md = 12.dp,
    lg = 16.dp,
    xl = 24.dp,
    xxl = 32.dp,
)

val LocalNanobotSpacing = staticCompositionLocalOf { ComfortableSpacing }
val LocalNanobotStatusColors = staticCompositionLocalOf { LightStatusColors }

object NanobotThemeDefaults {
    /** 当前 CompositionLocal 中的全局间距令牌。 */
    val spacing: NanobotSpacing
        @Composable get() = LocalNanobotSpacing.current

    /** Material 3 未覆盖的 Success / Warning 产品状态颜色。 */
    val statusColors: NanobotStatusColors
        @Composable get() = LocalNanobotStatusColors.current
}

/**
 * 应用唯一的 Material 3 主题入口。
 *
 * 默认使用固定 scheme，保证产品状态与层级可复现；调用方只有显式开启 dynamicColor 且系统版本支持时
 * 才读取壁纸动态色。Nanobot 扩展状态和间距与 MaterialTheme 在同一 Provider 边界注入，Feature
 * 不得再声明自己的全局色板或间距系统。
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
    val statusColors = if (darkTheme) DarkStatusColors else LightStatusColors
    val spacing = if (compact) CompactSpacing else ComfortableSpacing

    CompositionLocalProvider(
        LocalNanobotSpacing provides spacing,
        LocalNanobotStatusColors provides statusColors,
    ) {
        MaterialTheme(
            colorScheme = colorScheme,
            typography = NanobotTypography,
            shapes = NanobotShapes,
            content = content,
        )
    }
}
