package com.nanobotkt

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class SessionSelectionTest {
    @Test
    fun pendingCreatedSessionIsNotReplacedByAnOlderVisibleSession() {
        val result = reconcileSessionSelection(
            visibleKeys = listOf("websocket:old"),
            selectedKey = "websocket:new",
            draftingNewTopic = true,
        )

        assertEquals("websocket:new", result.selectedKey)
        assertEquals(true, result.draftingNewTopic)
    }

    @Test
    fun draftGuardClearsOnceCreatedSessionAppearsInSidebar() {
        val result = reconcileSessionSelection(
            visibleKeys = listOf("websocket:new", "websocket:old"),
            selectedKey = "websocket:new",
            draftingNewTopic = true,
        )

        assertEquals("websocket:new", result.selectedKey)
        assertEquals(false, result.draftingNewTopic)
    }

    @Test
    fun restoredSelectionSurvivesInitialEmptySidebar() {
        val result = reconcileSessionSelection(
            visibleKeys = emptyList(),
            selectedKey = "websocket:restored",
            draftingNewTopic = false,
        )

        assertEquals("websocket:restored", result.selectedKey)
        assertEquals(false, result.draftingNewTopic)
    }

    @Test
    fun deletedLastSessionClearsSelectionAfterSidebarFinishedLoading() {
        val result = reconcileSessionSelection(
            visibleKeys = emptyList(),
            selectedKey = "websocket:deleted",
            draftingNewTopic = false,
            sidebarLoaded = true,
        )

        assertNull(result.selectedKey)
        assertEquals(false, result.draftingNewTopic)
    }

    @Test
    fun ordinaryMissingSelectionFallsBackToFirstVisibleSession() {
        val result = reconcileSessionSelection(
            visibleKeys = listOf("websocket:first", "websocket:second"),
            selectedKey = "websocket:deleted",
            draftingNewTopic = false,
        )

        assertEquals("websocket:first", result.selectedKey)
        assertEquals(false, result.draftingNewTopic)
    }
}
