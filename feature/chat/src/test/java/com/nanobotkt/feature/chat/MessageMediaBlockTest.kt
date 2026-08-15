package com.nanobotkt.feature.chat

import org.junit.Assert.assertEquals
import org.junit.Test

class MessageMediaBlockTest {
    @Test
    fun `format media time keeps minute shape below one hour`() {
        assertEquals("0:00", formatMediaTime(0L))
        assertEquals("1:05", formatMediaTime(65_999L))
    }

    @Test
    fun `format media time adds hour without losing zero padding`() {
        assertEquals("1:01:01", formatMediaTime(3_661_900L))
    }

    @Test
    fun `format media time clamps invalid negative positions`() {
        assertEquals("0:00", formatMediaTime(-2_000L))
    }
}
