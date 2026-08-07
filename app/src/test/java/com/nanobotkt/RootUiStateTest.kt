package com.nanobotkt

import androidx.lifecycle.SavedStateHandle
import com.nanobotkt.feature.settings.SETTINGS_SECTION_OVERVIEW
import org.junit.Assert.assertEquals
import org.junit.Test

class RootUiStateTest {
    @Test
    fun defaultsToChatWhenNoStateWasSaved() {
        assertEquals(RootUiState(), SavedStateHandle().readRootUiState())
    }

    @Test
    fun restoresRootDestinationSessionAndSettingsSection() {
        val restored = SavedStateHandle(
            mapOf(
                "root.selectedKey" to "websocket:restored",
                "root.destination" to AppDestination.SETTINGS.name,
                "root.draftingNewTopic" to true,
                "root.settingsSection" to "Image",
            ),
        ).readRootUiState()

        assertEquals("websocket:restored", restored.selectedKey)
        assertEquals(AppDestination.SETTINGS, restored.destination)
        assertEquals(true, restored.draftingNewTopic)
        assertEquals("Image", restored.settingsSection)
    }

    @Test
    fun invalidSavedDestinationFallsBackToChat() {
        val restored = SavedStateHandle(
            mapOf(
                "root.destination" to "REMOVED_DESTINATION",
                "root.settingsSection" to "",
            ),
        ).readRootUiState()

        assertEquals(AppDestination.CHAT, restored.destination)
        assertEquals(SETTINGS_SECTION_OVERVIEW, restored.settingsSection)
    }
}