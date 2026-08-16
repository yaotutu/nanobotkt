package com.nanobotkt.feature.chat

import com.nanobotkt.core.model.UiMessage
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class ChatTimelineReducerTest {
    @Test
    fun `projection removes optimistic and transient duplicates owned by canonical timeline`() {
        val canonicalUser = message("canonical-user", "user", 10, turnId = "turn-user")
        val canonicalAssistant = message("canonical-assistant", "assistant", 30, turnId = "turn-answer")
        val localDuplicate = message("local-user", "user", 5, turnId = "turn-user")
        val localPending = message("local-pending", "user", 20, turnId = "turn-pending")
        val transientDuplicate = message("stream-answer", "assistant", 25, turnId = "turn-answer")
        val transientPending = message("stream-pending", "assistant", 40, turnId = "turn-stream")

        val result = projectChatTimeline(
            ChatTimelineInput(
                canonical = listOf(canonicalUser, canonicalAssistant),
                optimistic = listOf(localDuplicate, localPending),
                failedMessageIds = setOf("local-pending"),
                transient = listOf(transientDuplicate, transientPending),
                canonicalCompletedTurnIds = setOf("turn-answer"),
            ),
        )

        assertEquals(
            listOf("canonical-user", "local-pending", "canonical-assistant", "stream-pending"),
            result.messages.map(UiMessage::id),
        )
        assertEquals(setOf("local-pending"), result.failedMessageIds)
    }

    @Test
    fun `active transient replaces a stale canonical assistant until completion`() {
        val canonicalUser = message("canonical-user", "user", 10, turnId = "turn-user")
        val staleCanonical = message("canonical-partial", "assistant", 20, turnId = "turn-answer")
            .copy(content = "Partial")
        val newerTransient = message("stream-answer", "assistant", 30, turnId = "turn-answer")
            .copy(content = "Partial plus newer delta", isStreaming = true)

        val active = projectChatTimeline(
            ChatTimelineInput(
                canonical = listOf(canonicalUser, staleCanonical),
                optimistic = emptyList(),
                failedMessageIds = emptySet(),
                transient = listOf(newerTransient),
            ),
        )

        // active turn 以本机已接收的 WebSocket 投影为准，避免锁屏恢复时被较旧 HTTP partial 回退。
        assertEquals(listOf("canonical-user", "stream-answer"), active.messages.map(UiMessage::id))
        assertEquals("Partial plus newer delta", active.messages.last().content)

        val completed = projectChatTimeline(
            ChatTimelineInput(
                canonical = listOf(canonicalUser, staleCanonical.copy(content = "Final canonical")),
                optimistic = emptyList(),
                failedMessageIds = emptySet(),
                transient = listOf(newerTransient),
                canonicalCompletedTurnIds = setOf("turn-answer"),
            ),
        )

        // 服务端明确 completed 后再由 canonical 永久接管，迟到 transient 不得重新出现。
        assertEquals(listOf("canonical-user", "canonical-partial"), completed.messages.map(UiMessage::id))
        assertEquals("Final canonical", completed.messages.last().content)
    }

    @Test
    fun `reducer updates timeline metadata without overwriting unrelated state`() {
        val current = ChatUiState(
            sessionKey = "session-1",
            chatId = "chat-1",
            error = "keep-error",
            slashCommands = emptyList(),
        )
        val projected = ChatTimelineProjection(
            messages = listOf(message("m1", "user", 1, turnId = null)),
            failedMessageIds = emptySet(),
        )

        val result = reduceChatTimeline(
            current = current,
            projection = projected,
            metadata = ChatTimelineMetadata(
                loading = false,
                loadingOlder = true,
                hasMoreBefore = false,
                beforeCursor = null,
                activeTurnId = null,
                userMessageOffset = 4,
            ),
            limits = null,
        )

        assertEquals("session-1", result.sessionKey)
        assertEquals("chat-1", result.chatId)
        assertEquals("keep-error", result.error)
        assertEquals(listOf("m1"), result.messages.map(UiMessage::id))
        assertEquals(4, result.userMessageOffset)
        assertNull(result.beforeCursor)
    }

    private fun message(
        id: String,
        role: String,
        createdAt: Long,
        turnId: String?,
    ): UiMessage = UiMessage(
        id = id,
        role = role,
        content = id,
        createdAt = createdAt,
        turnId = turnId,
    )
}
