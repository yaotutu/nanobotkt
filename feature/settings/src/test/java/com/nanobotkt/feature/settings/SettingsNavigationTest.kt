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
    }

    @Test
    fun serverSwitchRequiresValidExplicitAddressFreshSecretAndIdleState() {
        assertTrue(canSubmitServerSwitch("https://gateway.example/nanobot", "new-secret", false))

        // 地址规则与 Auth 共用 core:network 规范化器；无 scheme、非 HTTP(S)、query 都不能
        // 在 UI 层进入候选验证，空 Secret 与正在切换时也必须禁用按钮。
        assertFalse(canSubmitServerSwitch("gateway.example", "new-secret", false))
        assertFalse(canSubmitServerSwitch("ftp://gateway.example", "new-secret", false))
        assertFalse(canSubmitServerSwitch("https://gateway.example?token=bad", "new-secret", false))
        assertFalse(canSubmitServerSwitch("https://gateway.example", "   ", false))
        assertFalse(canSubmitServerSwitch("https://gateway.example", "new-secret", true))
    }

    @Test
    fun sectionTitlesMatchUnifiedInformationArchitecture() {
        assertEquals("Settings", settingsSectionTitle(SETTINGS_SECTION_OVERVIEW))
        assertEquals("Models & Providers", settingsSectionTitle(SETTINGS_SECTION_MODELS))
        assertEquals("Gateway & System", settingsSectionTitle(SETTINGS_SECTION_SYSTEM))
    }
}
