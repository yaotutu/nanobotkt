package com.nanobotkt.feature.settings

import com.nanobotkt.core.network.GatewayServerAddressError
import com.nanobotkt.core.network.GatewayServerAddressResult
import com.nanobotkt.core.network.normalizeGatewayServerAddress
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class GatewayReconfigurationRulesTest {
    @Test
    fun everyStrictAddressErrorHasAnActionableLabel() {
        GatewayServerAddressError.entries.forEach { error ->
            assertTrue(gatewayAddressErrorLabel(error).isNotBlank())
        }
        assertEquals(
            "Include http:// or https://.",
            gatewayAddressErrorLabel(GatewayServerAddressError.MISSING_SCHEME),
        )
    }

    @Test
    fun submitRequiresValidHttpAddressNonBlankSecretAndIdleState() {
        val valid = normalizeGatewayServerAddress("https://gateway.example/base/")
        val missingScheme = normalizeGatewayServerAddress("gateway.example")

        assertTrue(canSubmitGatewayReconfiguration(valid, "opaque-secret", submitting = false))
        assertFalse(canSubmitGatewayReconfiguration(missingScheme, "opaque-secret", submitting = false))
        assertFalse(canSubmitGatewayReconfiguration(valid, "   ", submitting = false))
        assertFalse(canSubmitGatewayReconfiguration(valid, "opaque-secret", submitting = true))
    }

    @Test
    fun sameEndpointDoesNotDisableACompleteReplacement() {
        val currentEndpoint = "http://192.168.55.147:8765"
        val normalized = normalizeGatewayServerAddress(currentEndpoint)

        // 规则没有“地址必须变化”的条件；同地址加新的完整 Secret 仍可提交。
        assertTrue(canSubmitGatewayReconfiguration(normalized, "new-complete-secret", submitting = false))
        assertTrue(normalized is GatewayServerAddressResult.Valid)
    }
}
