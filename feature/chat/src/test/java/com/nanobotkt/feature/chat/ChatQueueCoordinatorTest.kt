package com.nanobotkt.feature.chat

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ChatQueueCoordinatorTest {
    @Test
    fun `active turn becoming idle requests queue flush`() {
        val coordinator = ChatQueueCoordinator(initialTurnActive = true)
        val composer = ComposerUiState(queuedPrompts = listOf(prompt("first")))

        assertTrue(coordinator.onChatStateChanged(turnActive = false, composer = composer))
    }

    @Test
    fun `turn end during send defers flush until acceptance succeeds`() {
        val coordinator = ChatQueueCoordinator(initialTurnActive = true)
        val composer =
            ComposerUiState(
                sending = true,
                queuedPrompts = listOf(prompt("second")),
            )

        assertFalse(coordinator.onChatStateChanged(turnActive = false, composer = composer))
        assertTrue(coordinator.onSubmitSucceeded())
        // 延迟信号只能消费一次，避免同一 turn-end 重复发送两个队首。
        assertFalse(coordinator.onSubmitSucceeded())
    }

    @Test
    fun `stop skips the next active to idle edge`() {
        val coordinator = ChatQueueCoordinator(initialTurnActive = true)
        coordinator.onStop(hasQueuedPrompts = true)
        val composer = ComposerUiState(queuedPrompts = listOf(prompt("discarded")))

        assertFalse(coordinator.onChatStateChanged(turnActive = false, composer = composer))
    }

    @Test
    fun `failed acceptance clears deferred flush`() {
        val coordinator = ChatQueueCoordinator(initialTurnActive = true)
        val composer =
            ComposerUiState(
                sending = true,
                queuedPrompts = listOf(prompt("retry later")),
            )
        coordinator.onChatStateChanged(turnActive = false, composer = composer)

        coordinator.onSubmitFailed()

        assertFalse(coordinator.onSubmitSucceeded())
    }

    private fun prompt(text: String) =
        QueuedPrompt(
            id = text,
            text = text,
            attachments = emptyList(),
        )
}
