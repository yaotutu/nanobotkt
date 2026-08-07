package com.nanobotkt.feature.chat

import com.nanobotkt.core.model.UiMessage
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test

class MessageReconciliationTest {
    @Test
    fun `matches stable id or semantic identity`() {
        assertTrue(sameSemanticMessage(message("same", "old"), message("same", "new")))
        assertTrue(
            sameSemanticMessage(
                message("", "Answer", turnId = "turn-1"),
                message("", "Answer", turnId = "turn-1"),
            ),
        )
        assertFalse(
            sameSemanticMessage(
                message("", "Answer", turnId = "turn-1"),
                message("", "Answer", turnId = "turn-2"),
            ),
        )
    }

    @Test
    fun `latest page replaces overlapping suffix without duplication`() {
        val first = message("first", "First", role = "user")
        val overlap = message("overlap", "Existing answer")
        val refreshed = message("overlap", "Refreshed answer")
        val latest = message("latest", "Latest answer")

        assertEquals(
            listOf(first, refreshed, latest),
            mergeLatestMessages(listOf(first, overlap), listOf(refreshed, latest)),
        )
    }

    @Test
    fun `latest fallback prepends only unseen stable ids`() {
        val result = mergeLatestMessages(
            current = listOf(message("current", "Current")),
            latest = listOf(message("new", "New"), message("current", "Server copy")),
        )

        assertEquals(listOf("new", "current"), result.map(UiMessage::id))
    }

    @Test
    fun `older page stops at current semantic boundary`() {
        val boundary = message("boundary", "Boundary")
        val current = listOf(boundary, message("latest", "Latest"))
        val result = prependOlderMessages(
            current,
            listOf(message("oldest", "Oldest"), message("boundary", "Boundary")),
        )

        assertEquals(listOf("oldest", "boundary", "latest"), result.map(UiMessage::id))
        assertSame(current, prependOlderMessages(current, emptyList()))
    }

    private fun message(
        id: String,
        content: String,
        role: String = "assistant",
        kind: String? = null,
        turnId: String? = null,
    ) = UiMessage(
        id = id,
        role = role,
        content = content,
        kind = kind,
        createdAt = 1,
        turnId = turnId,
    )
}
