package com.nanobotkt

import com.nanobotkt.feature.auth.GatewayConfigurationError
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
            GatewayConfigurationResult.Success("http://192.168.55.147:8765"),
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
