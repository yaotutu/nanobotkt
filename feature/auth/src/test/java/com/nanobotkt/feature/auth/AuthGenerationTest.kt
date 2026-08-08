package com.nanobotkt.feature.auth

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class AuthGenerationTest {
    @Test
    fun invalidatingGenerationRejectsAnInFlightRequest() {
        val generations = AuthGeneration()
        val requestGeneration = generations.current()

        assertTrue(generations.isCurrent(requestGeneration))

        generations.invalidate()

        // logout 之后，旧请求即使晚于 logout 返回，也不能再写回认证状态。
        assertFalse(generations.isCurrent(requestGeneration))
        assertTrue(generations.isCurrent(generations.current()))
    }
}
