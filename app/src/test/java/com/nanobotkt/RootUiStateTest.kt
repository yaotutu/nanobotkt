package com.nanobotkt

import androidx.lifecycle.SavedStateHandle
import com.nanobotkt.feature.settings.SETTINGS_SECTION_MODELS
import com.nanobotkt.feature.settings.SETTINGS_SECTION_OVERVIEW
import com.nanobotkt.feature.settings.SETTINGS_SECTION_SYSTEM
import org.junit.Assert.assertEquals
import org.junit.Test

class RootUiStateTest {
    @Test
    fun defaultsToChatWhenNoStateWasSaved() {
        assertEquals(RootUiState(), SavedStateHandle().readRootUiState())
    }

    @Test
    fun restoresRootDestinationSessionSettingsSectionAndReturnTarget() {
        val restored = SavedStateHandle(
            mapOf(
                "root.selectedKey" to "websocket:restored",
                "root.destination" to AppDestination.APPS.name,
                "root.draftingNewTopic" to true,
                "root.settingsSection" to "Image",
                "root.returnDestination" to AppDestination.SETTINGS.name,
            ),
        ).readRootUiState()

        assertEquals("websocket:restored", restored.selectedKey)
        assertEquals(AppDestination.APPS, restored.destination)
        assertEquals(true, restored.draftingNewTopic)
        assertEquals("Image", restored.settingsSection)
        assertEquals(AppDestination.SETTINGS, restored.returnDestination)
    }

    @Test
    fun settingsDetailOpenedFromHomeReturnsToSettingsHomeFirst() {
        val restoredDetail = RootUiState(
            destination = AppDestination.SETTINGS,
            settingsSection = SETTINGS_SECTION_MODELS,
            returnDestination = AppDestination.SETTINGS,
        )

        assertEquals(
            RootUiState(
                destination = AppDestination.SETTINGS,
                settingsSection = SETTINGS_SECTION_OVERVIEW,
                returnDestination = AppDestination.CHAT,
            ),
            restoredDetail.navigateBackState(),
        )
    }

    @Test
    fun independentSettingsChildReturnsToSettingsHome() {
        val apps = RootUiState(
            destination = AppDestination.APPS,
            returnDestination = AppDestination.SETTINGS,
        )

        assertEquals(
            RootUiState(
                destination = AppDestination.SETTINGS,
                settingsSection = SETTINGS_SECTION_OVERVIEW,
                returnDestination = AppDestination.CHAT,
            ),
            apps.navigateBackState(),
        )
    }

    @Test
    fun settingsShortcutOpenedFromChatReturnsDirectlyToChat() {
        val models = RootUiState(
            destination = AppDestination.SETTINGS,
            settingsSection = SETTINGS_SECTION_MODELS,
            returnDestination = AppDestination.CHAT,
        )

        assertEquals(
            models.copy(destination = AppDestination.CHAT),
            models.navigateBackState(),
        )
    }

    @Test
    fun invalidSavedDestinationAndReturnTargetFallBackToChat() {
        val restored = SavedStateHandle(
            mapOf(
                "root.destination" to "REMOVED_DESTINATION",
                "root.settingsSection" to "",
                "root.returnDestination" to "REMOVED_DESTINATION",
            ),
        ).readRootUiState()

        assertEquals(AppDestination.CHAT, restored.destination)
        assertEquals(SETTINGS_SECTION_OVERVIEW, restored.settingsSection)
        assertEquals(AppDestination.CHAT, restored.returnDestination)
    }

    @Test
    fun legacyNonPageReturnTargetFallsBackToChat() {
        val restored = SavedStateHandle(
            mapOf("root.returnDestination" to AppDestination.APPS.name),
        ).readRootUiState()

        // 旧版本或损坏状态可能保存任意 destination；新导航只允许 Chat/Settings
        // 作为返回目标，避免恢复后生成兄弟 feature 之间的循环返回。
        assertEquals(AppDestination.CHAT, restored.returnDestination)
    }

    @Test
    fun backOnChatDoesNotMutateSessionOrDraftState() {
        val chat = RootUiState(
            selectedKey = "websocket:active",
            draftingNewTopic = true,
        )

        assertEquals(chat, chat.navigateBackState())
    }

    @Test
    fun successfulServerSwitchKeepsSystemSettingsOpenAndClearsChatSelection() {
        val before = RootUiState(
            selectedKey = "websocket:old-server-chat",
            destination = AppDestination.SETTINGS,
            draftingNewTopic = false,
            settingsSection = SETTINGS_SECTION_SYSTEM,
            returnDestination = AppDestination.SETTINGS,
        )

        val after = before.afterServerSwitch()

        // 切换后不能让旧服务器会话继续成为当前选择；页面保留在 Gateway & System，
        // 让用户立即看到新端点和连接结果，而不是被突然送回聊天页。
        assertEquals(null, after.selectedKey)
        assertEquals(AppDestination.SETTINGS, after.destination)
        assertEquals(true, after.draftingNewTopic)
        assertEquals(SETTINGS_SECTION_SYSTEM, after.settingsSection)
        assertEquals(AppDestination.CHAT, after.returnDestination)
    }

    @Test
    fun idleGatewaySwitchFeedbackIsClearedBetweenSystemPageVisits() {
        val failed = GatewaySwitchUiState(
            feedback = "Could not reach this server.",
            succeeded = false,
        )
        val succeeded = GatewaySwitchUiState(
            feedback = "Connected to candidate.",
            succeeded = true,
        )

        // 已完成请求的反馈只服务于当前一次页面访问；离页后无论成功还是失败都应清空，
        // 避免恢复后的当前地址与上一轮候选结果同时出现，造成错误的状态暗示。
        assertEquals(GatewaySwitchUiState(), failed.dismissFeedbackIfIdle())
        assertEquals(GatewaySwitchUiState(), succeeded.dismissFeedbackIfIdle())
    }

    @Test
    fun activeGatewayValidationIsNotClearedWhenSystemPageTemporarilyLeavesComposition() {
        val validating = GatewaySwitchUiState(switching = true)

        // 用户可能在请求未结束时按返回键；此时必须保留互斥标记，防止再次提交与原请求
        // 竞争端点持久化、旧 Session 清理和 Transport 激活顺序。
        assertEquals(validating, validating.dismissFeedbackIfIdle())
    }

    @Test
    fun serverSwitchCleanupRunsInSecurityCriticalOrder() {
        val events = mutableListOf<String>()

        runServerSwitchCleanup(
            resetSessionState = { events += "session" },
            clearAttachments = { events += "attachments" },
            closeTransport = { events += "close" },
            resetRootUiState = { events += "root" },
        )

        // Repository 代次先失效，随后移除旧附件恢复登记并关闭 Socket，最后才发布 Root
        // 页面变化；这个顺序避免新 Transport 在旧缓存仍可见时恢复连接。
        assertEquals(listOf("session", "attachments", "close", "root"), events)
    }
}
