package com.nanobotkt.core.designsystem

import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Test

/**
 * 锁定 NanobotKT 在 Material 3 之上的增量令牌契约。
 *
 * 这些测试不渲染 UI，只验证主题入口不会悄悄退回模板紫色、旧品牌橙色、非 MD3 间距
 * 或不完整的 type scale。
 * 具体组件截图仍需要在 Compose Preview/模拟器中由设计评审确认。
 */
class Material3ThemeTokensTest {
    @Test
    fun colorSchemesUseTheNanobotBrandRoles() {
        // 品牌主色使用低彩度靛蓝，浅色与深色分别使用同一 tonal palette 的不同角色。
        assertEquals(Color(0xFF415F91), NanobotLightColorScheme.primary)
        assertEquals(Color(0xFFAAC7FF), NanobotDarkColorScheme.primary)
        assertEquals(Color(0xFFF9F9FF), NanobotLightColorScheme.background)
        assertEquals(Color(0xFF111318), NanobotDarkColorScheme.background)

        // Material 模板紫色和旧版 Nanobot 橙色都不应重新成为全局强调角色。
        assertNotEquals(Color(0xFF6750A4), NanobotLightColorScheme.primary)
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
