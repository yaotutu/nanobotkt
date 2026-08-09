package com.nanobotkt.core.designsystem

import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Test

/**
 * 锁定第一阶段的设计令牌契约。
 *
 * 这些测试不渲染 UI，只验证主题入口不会悄悄退回旧的品牌色、非 MD3 间距或不完整的 type scale。
 * 具体组件截图仍需要在 Compose Preview/模拟器中由设计评审确认。
 */
class Material3ThemeTokensTest {
    @Test
    fun colorSchemesUseTheMaterial3BaselineRoles() {
        assertEquals(Color(0xFF6750A4), NanobotLightColorScheme.primary)
        assertEquals(Color(0xFFD0BCFF), NanobotDarkColorScheme.primary)
        assertEquals(Color(0xFFFFFBFE), NanobotLightColorScheme.background)
        assertEquals(Color(0xFF1C1B1F), NanobotDarkColorScheme.background)

        // 旧版 Nanobot 的橙色强调色不再出现在全局 tertiary role 中。
        assertNotEquals(Color(0xFFEF8E30), NanobotLightColorScheme.tertiary)
        assertNotEquals(Color(0xFFEF8E30), NanobotDarkColorScheme.tertiary)
    }

    @Test
    fun spacingKeepsTheMd3GridAndTouchTarget() {
        val spacing = NanobotSpacing()

        assertEquals(4.dp, spacing.xxs)
        assertEquals(8.dp, spacing.xs)
        assertEquals(16.dp, spacing.md)
        assertEquals(48.dp, spacing.touchTarget)
    }

    @Test
    fun typographyExposesTheCompleteMaterial3Scale() {
        assertEquals(57.sp, NanobotTypography.displayLarge.fontSize)
        assertEquals(32.sp, NanobotTypography.headlineLarge.fontSize)
        assertEquals(16.sp, NanobotTypography.bodyLarge.fontSize)
        assertEquals(11.sp, NanobotTypography.labelSmall.fontSize)
    }
}
