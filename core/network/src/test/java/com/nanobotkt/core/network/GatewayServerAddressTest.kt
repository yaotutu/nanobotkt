package com.nanobotkt.core.network

import org.junit.Assert.assertEquals
import org.junit.Test

class GatewayServerAddressTest {
    @Test
    fun `normalizes supported gateway addresses without guessing the protocol`() {
        assertEquals(
            GatewayServerAddressResult.Valid("http://192.168.55.147:8765"),
            normalizeGatewayServerAddress("  http://192.168.55.147:8765///  "),
        )
        assertEquals(
            GatewayServerAddressResult.Valid("https://example.com/nanobot"),
            normalizeGatewayServerAddress("https://example.com/nanobot/"),
        )
    }

    @Test
    fun `rejects missing or unsupported schemes`() {
        assertEquals(
            GatewayServerAddressResult.Invalid(GatewayServerAddressError.MISSING_SCHEME),
            normalizeGatewayServerAddress("example.com:8765"),
        )
        assertEquals(
            GatewayServerAddressResult.Invalid(GatewayServerAddressError.UNSUPPORTED_SCHEME),
            normalizeGatewayServerAddress("ftp://example.com"),
        )
    }

    @Test
    fun `rejects credentials query and fragment because they are not part of a gateway base address`() {
        assertEquals(
            GatewayServerAddressResult.Invalid(GatewayServerAddressError.EMBEDDED_CREDENTIALS),
            normalizeGatewayServerAddress("https://user:password@example.com"),
        )
        assertEquals(
            GatewayServerAddressResult.Invalid(GatewayServerAddressError.QUERY_NOT_ALLOWED),
            normalizeGatewayServerAddress("https://example.com?token=value"),
        )
        assertEquals(
            GatewayServerAddressResult.Invalid(GatewayServerAddressError.FRAGMENT_NOT_ALLOWED),
            normalizeGatewayServerAddress("https://example.com#gateway"),
        )
    }
}
