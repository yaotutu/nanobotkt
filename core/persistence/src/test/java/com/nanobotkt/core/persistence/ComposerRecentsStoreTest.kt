package com.nanobotkt.core.persistence

import org.junit.Assert.assertEquals
import org.junit.Test

class ComposerRecentsStoreTest {
    @Test
    fun `decode accepts only string entries and keeps source order`() {
        assertEquals(
            listOf("/status", "\$review", "", "/help"),
            decodeComposerRecents("[\"/status\",4,\"\\u0024review\",null,\"\",{},\"/help\"]"),
        )
    }

    @Test
    fun `decode returns empty for missing malformed or non-array payloads`() {
        assertEquals(emptyList<String>(), decodeComposerRecents(null))
        assertEquals(emptyList<String>(), decodeComposerRecents("not-json"))
        assertEquals(emptyList<String>(), decodeComposerRecents("{\"command\":\"/help\"}"))
    }

    @Test
    fun `decode and normalize cap recents at five`() {
        val commands = (1..7).map { "/command-$it" }

        assertEquals(commands.take(5), normalizeComposerRecents(commands))
        assertEquals(commands.take(5), decodeComposerRecents(commands.joinToString(",", "[\"", "\"]") { it }.replace(",", "\",\"")))
    }
}