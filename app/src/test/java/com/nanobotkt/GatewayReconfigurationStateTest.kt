package com.nanobotkt

import com.nanobotkt.feature.auth.AuthState
import com.nanobotkt.feature.auth.GatewayConfigurationError
import com.nanobotkt.feature.auth.GatewayConnectionState
import com.nanobotkt.feature.auth.GatewayConfigurationResult
import com.nanobotkt.feature.settings.SETTINGS_SECTION_SYSTEM
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Test

class GatewayReconfigurationStateTest {
    @Test
    fun successfulSameAddressReplacementStillAdvancesSuccessGeneration() {
        val current = GatewayReconfigurationUiState(
            submitting = true,
            successGeneration = 7L,
        )

        val updated = current.afterGatewayReconfiguration(
            GatewayConfigurationResult.Success("http://192.168.55.147:8765", profileId = "profile-new"),
        )

        assertEquals(8L, updated.successGeneration)
        assertFalse(updated.submitting)
        assertNull(updated.error)
    }

    @Test
    fun failedCandidateUnlocksFormAndKeepsSuccessGeneration() {
        val current = GatewayReconfigurationUiState(
            submitting = true,
            successGeneration = 3L,
        )

        val updated = current.afterGatewayReconfiguration(
            GatewayConfigurationResult.Failure(GatewayConfigurationError.AuthenticationRejected),
        )

        assertEquals(3L, updated.successGeneration)
        assertFalse(updated.submitting)
        assertEquals(GatewayConfigurationError.AuthenticationRejected, updated.error)
    }

    @Test
    fun persistedSelectionIsAppliedOnlyToTheSameProfileAndEpoch() {
        val expected = AuthState.Ready(
            sessionEpoch = 4L,
            profileId = "profile-a",
            connection = GatewayConnectionState.CONNECTING,
        )
        val root = RootUiState()

        assertEquals(
            root.copy(selectedKey = "webui:cached"),
            root.restorePersistedSelectionIfCurrent(
                expectedAuth = expected,
                currentAuth = expected.copy(connection = GatewayConnectionState.ONLINE),
                persistedSelection = "webui:cached",
            ),
        )
        assertEquals(
            root,
            root.restorePersistedSelectionIfCurrent(
                expectedAuth = expected,
                currentAuth = expected.copy(sessionEpoch = 5L, profileId = "profile-b"),
                persistedSelection = "webui:old-account",
            ),
        )
    }

    @Test
    fun persistedSelectionCannotOverrideRestoredRootOrDraftingGuard() {
        val auth = AuthState.Ready(1L, "profile-a", GatewayConnectionState.CONNECTING)
        val selected = RootUiState(selectedKey = "webui:saved-state")
        val drafting = RootUiState(draftingNewTopic = true)

        assertEquals(
            selected,
            selected.restorePersistedSelectionIfCurrent(auth, auth, "webui:room"),
        )
        assertEquals(
            drafting,
            drafting.restorePersistedSelectionIfCurrent(auth, auth, "webui:room"),
        )
    }

    @Test
    fun gatewayScopedResetPreservesSettingsDestinationAndSection() {
        val current = RootUiState(
            selectedKey = "websocket:old-session",
            destination = AppDestination.SETTINGS,
            draftingNewTopic = true,
            settingsSection = SETTINGS_SECTION_SYSTEM,
            returnDestination = AppDestination.SETTINGS,
        )

        assertEquals(
            current.copy(selectedKey = null, draftingNewTopic = false),
            current.clearGatewayScopedSelection(),
        )
    }
}
