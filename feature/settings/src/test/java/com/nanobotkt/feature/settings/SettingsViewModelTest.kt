package com.nanobotkt.feature.settings

import com.nanobotkt.core.model.ApiServicePayload
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class SettingsViewModelTest {
    @Test
    fun apiServiceStartUsesCurrentConfigWhenArgumentsAreOmitted() {
        val request = resolveApiServiceStartRequest(
            current = ApiServicePayload(
                host = "0.0.0.0",
                port = 19000,
                timeout = 240,
            ),
            host = null,
            port = null,
            timeout = null,
            key = null,
        )

        assertEquals("0.0.0.0", request.host)
        assertEquals(19000, request.port)
        assertEquals(240, request.timeout)
        // null 让服务端沿用已有私密配置，而不是把 key 写成空值。
        assertNull(request.apiKey)
    }

    @Test
    fun apiServiceStartFallsBackToSafeDefaultsWhenCurrentConfigIsUnavailable() {
        val request = resolveApiServiceStartRequest(
            current = null,
            host = " ",
            port = 0,
            timeout = -1,
            key = "temporary-key",
        )

        assertEquals("127.0.0.1", request.host)
        assertEquals(18765, request.port)
        assertEquals(120, request.timeout)
        assertEquals("temporary-key", request.apiKey)
    }

    @Test
    fun explicitApiServiceArgumentsOverrideCurrentConfig() {
        val request = resolveApiServiceStartRequest(
            current = ApiServicePayload(host = "127.0.0.1", port = 18765, timeout = 120),
            host = "10.0.0.5",
            port = 20000,
            timeout = 60,
            key = "explicit-key",
        )

        assertEquals("10.0.0.5", request.host)
        assertEquals(20000, request.port)
        assertEquals(60, request.timeout)
        assertEquals("explicit-key", request.apiKey)
    }
}
