package com.nanobotkt.feature.chat

import com.nanobotkt.core.model.UiMessage
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class MessageActionPolicyTest {
    @Test
    fun `fork index is attached only to final assistant before each user`() {
        val messages = listOf(
            message("u1", "user"),
            message("trace", "assistant", kind = "trace"),
            message("a1", "assistant"),
            message("u2", "user"),
            message("a2", "assistant"),
        )

        assertEquals(listOf(null, null, 4, null, 5), assistantForkIndexes(messages, userMessageOffset = 3))
    }

    @Test
    fun `retry is available only for final completed assistant message`() {
        val messages = listOf(message("u", "user"), message("a", "assistant"))
        assertTrue(canRetryFromMessage(messages, 1))
        assertFalse(canRetryFromMessage(messages, 0))
        assertFalse(canRetryFromMessage(messages + message("u2", "user"), 1))
        assertFalse(canRetryFromMessage(listOf(message("a", "assistant", streaming = true)), 0))
    }

    @Test
    fun `message actions shrink by delivery and streaming state`() {
        assertEquals(
            listOf(MessageAction.COPY, MessageAction.QUOTE, MessageAction.FORK, MessageAction.VIEW),
            availableMessageActions(role = "assistant", canFork = true),
        )
        assertEquals(
            listOf(MessageAction.COPY, MessageAction.QUOTE, MessageAction.VIEW),
            availableMessageActions(role = "assistant", streaming = true, canFork = true),
        )
        assertEquals(
            listOf(MessageAction.COPY, MessageAction.VIEW),
            availableMessageActions(
                role = "user",
                deliveryState = UserMessageDeliveryState.FAILED,
                canFork = true,
            ),
        )
        assertEquals(
            listOf(MessageAction.COPY, MessageAction.QUOTE, MessageAction.VIEW),
            availableMessageActions(
                role = "user",
                deliveryState = UserMessageDeliveryState.QUEUED,
                canFork = true,
            ),
        )
    }

    private fun message(
        id: String,
        role: String,
        kind: String? = null,
        streaming: Boolean? = null,
    ) = UiMessage(id = id, role = role, content = id, kind = kind, isStreaming = streaming, createdAt = 1)
}