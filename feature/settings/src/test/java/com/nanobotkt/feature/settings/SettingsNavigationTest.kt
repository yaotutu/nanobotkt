package com.nanobotkt.feature.settings

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** 锁定统一设置中心的信息架构，避免后续详情页再次被塞回旧下拉导航。 */
class SettingsNavigationTest {
    @Test
    fun overviewAndOwnedSectionsAreAcceptedBySettingsScreen() {
        assertTrue(SETTINGS_SECTION_OVERVIEW in settingsSections)
        assertTrue(SETTINGS_SECTION_MODELS in settingsSections)
        assertTrue(SETTINGS_SECTION_IMAGE in settingsSections)
        assertTrue(SETTINGS_SECTION_SECURITY in settingsSections)
    }

    @Test
    fun siblingFeaturesAreNotTreatedAsInternalSettingsSections() {
        // Channels、Apps 等由 app 组合根导航，不能成为 Settings feature 内部 section，
        // 否则会重新引入兄弟 feature 依赖或空白占位页面。
        assertFalse("Channels" in settingsSections)
        assertFalse("Apps" in settingsSections)
        assertFalse("Workspaces" in settingsSections)
    }

    @Test
    fun gatewaySummaryUsesClientEndpointAndNormalizesTrailingSlash() {
        // Settings Home 的连接摘要必须来自 app 实际连接入口；这个纯函数只规范显示，
        // 不允许在空值时偷偷回退到 payload.runtime 的内部监听地址。
        assertEquals(
            "http://192.168.55.147:8765",
            gatewayEndpointLabel("  http://192.168.55.147:8765/  "),
        )
        assertEquals("Gateway endpoint unavailable", gatewayEndpointLabel("   "))
        // Compose 展示层会传入当前 Locale 的空值资源；纯函数必须原样使用调用方文案，
        // 不能再把英文哨兵值泄漏回中文界面。
        assertEquals("Gateway 地址不可用", gatewayEndpointLabel("   ", "Gateway 地址不可用"))
    }

    @Test
    fun sectionTitlesMatchUnifiedInformationArchitecture() {
        assertEquals("Settings", settingsSectionTitle(SETTINGS_SECTION_OVERVIEW))
        assertEquals("Models & Providers", settingsSectionTitle(SETTINGS_SECTION_MODELS))
        assertEquals("Gateway & System", settingsSectionTitle(SETTINGS_SECTION_SYSTEM))
    }
}
