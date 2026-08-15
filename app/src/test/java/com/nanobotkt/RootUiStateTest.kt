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



}
