package com.nanobotkt.feature.security

import com.nanobotkt.core.model.PairingRequestInfo
import org.junit.Assert.assertEquals
import org.junit.Test

class SecurityExpiryTest {
    @Test
    fun usesAbsoluteExpiryAndShowsExpired() {
        val request = PairingRequestInfo(
            code = "ABC123",
            channel = "demo",
            senderId = "sender",
            expiresAtMs = 10_000L,
        )

        assertEquals("Expires in 5s", pairingExpiryText(request, 5_000L))
        assertEquals("Expired", pairingExpiryText(request, 10_000L))
    }

    @Test
    fun fallsBackToCreatedAtAndRelativeLifetime() {
        val request = PairingRequestInfo(
            code = "ABC123",
            channel = "demo",
            senderId = "sender",
            createdAtMs = 1_000L,
            expiresInSeconds = 120L,
        )

        // 120 秒的相对生命周期在创建时仍完整剩余，因此应显示为 2 分钟。
        assertEquals("Expires in 2m", pairingExpiryText(request, 1_000L))
    }

    @Test
    fun reportsUnavailableWhenServerHasNoExpiryFields() {
        val request = PairingRequestInfo(code = "ABC123", channel = "demo", senderId = "sender")
        assertEquals("Expiry unavailable", pairingExpiryText(request, 1_000L))
    }
}
