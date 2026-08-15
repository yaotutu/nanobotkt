package com.nanobotkt.feature.chat

import android.net.TestUri
import com.nanobotkt.core.model.OutboundMedia
import org.junit.Assert.assertEquals
import org.junit.Test

class QueuedPromptTimelineTest {
    @Test
    fun `queued prompts are appended as user messages without mutating canonical timeline`() {
        val original = emptyList<ChatTimelineItem>()
        val queued =
            QueuedPrompt(
                id = "queue-1",
                text = "继续检查",
                quotedContext = "上一条回答",
                attachments =
                    listOf(
                        ComposerAttachment(
                            uri = TestUri("test://audio"),
                            name = "note.m4a",
                            mimeType = "audio/mp4",
                            bytes = 12,
                            outbound = OutboundMedia("data:audio/mp4;base64,AA==", "note.m4a"),
                        )
                    ),
            )

        val result = appendQueuedPromptsToTimeline(original, listOf(queued), nowMs = 10)
        val item = result.single() as ChatTimelineItem.UserMessage

        assertEquals(UserMessageDeliveryState.QUEUED, item.deliveryState)
        assertEquals("queue-1", item.message.id)
        assertEquals("queued", item.message.turnPhase)
        assertEquals("audio", item.message.media?.single()?.kind)
        assertEquals(0, original.size)
    }
}
