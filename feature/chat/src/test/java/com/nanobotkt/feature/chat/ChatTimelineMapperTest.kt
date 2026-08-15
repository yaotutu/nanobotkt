package com.nanobotkt.feature.chat

import com.nanobotkt.core.model.ToolProgressEvent
import com.nanobotkt.core.model.UiFileEdit
import com.nanobotkt.core.model.UiImage
import com.nanobotkt.core.model.UiMediaAttachment
import com.nanobotkt.core.model.UiMessage
import com.nanobotkt.core.model.UiMessageSource
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class ChatTimelineMapperTest {
    @Test
    fun `普通用户与助手消息保持一级顺序`() {
        val items =
            buildChatTimelineItems(
                listOf(
                    message("u1", role = "user", content = "hello"),
                    message("a1", content = "world"),
                )
            )

        assertTrue(items[0] is ChatTimelineItem.UserMessage)
        assertTrue(items[1] is ChatTimelineItem.AssistantMessage)
    }

    @Test
    fun `失败 ID 只把对应用户消息映射为 FAILED`() {
        val items =
            buildChatTimelineItems(
                messages =
                    listOf(
                        message("local:failed", role = "user", content = "retry me"),
                        message("history:sent", role = "user", content = "already sent"),
                    ),
                failedMessageIds = setOf("local:failed"),
            ).filterIsInstance<ChatTimelineItem.UserMessage>()

        assertEquals(UserMessageDeliveryState.FAILED, items[0].deliveryState)
        assertEquals(UserMessageDeliveryState.SENT, items[1].deliveryState)
    }

    @Test
    fun `同一轮 reasoning tool 和普通 trace 合并为活动组`() {
        val items =
            buildChatTimelineItems(
                listOf(
                    message("u1", role = "user", turnId = "turn-1"),
                    message("r1", reasoning = "分析", turnId = "turn-1"),
                    message(
                        "t1",
                        kind = "trace",
                        turnId = "turn-1",
                        toolEvents = listOf(ToolProgressEvent(callId = "read", name = "read_file")),
                    ),
                    message("a1", content = "完成", turnId = "turn-1"),
                )
            )

        val activity = items.filterIsInstance<ChatTimelineItem.AgentActivity>().single()
        assertEquals(listOf("r1", "t1"), activity.messages.map { it.id })
        assertEquals("turn-1", activity.turnId)
    }

    @Test
    fun `trace 不会渲染为普通助手消息`() {
        val items = buildChatTimelineItems(listOf(message("trace", kind = "trace")))

        assertEquals(1, items.size)
        assertTrue(items.single() is ChatTimelineItem.AgentActivity)
    }

    @Test
    fun `含 reasoning 的最终正文拆成活动与纯正文`() {
        val items =
            buildChatTimelineItems(
                listOf(message("answer", content = "final", reasoning = "thinking"))
            )

        assertTrue(items[0] is ChatTimelineItem.AgentActivity)
        val answer = items[1] as ChatTimelineItem.AssistantMessage
        assertEquals("final", answer.message.content)
        assertNull(answer.message.reasoning)
        assertEquals("answer-reasoning", (items[0] as ChatTimelineItem.AgentActivity).messages.single().id)
    }

    @Test
    fun `活跃轮次保留尾部活动而完成轮次移动到最终答案之前`() {
        val messages =
            listOf(
                message("a1", content = "answer", turnId = "turn-1"),
                message("trace", kind = "trace", turnId = "turn-1"),
            )

        val completed = buildChatTimelineItems(messages)
        assertTrue(completed[0] is ChatTimelineItem.AgentActivity)
        assertTrue(completed[1] is ChatTimelineItem.AssistantMessage)

        val active = buildChatTimelineItems(messages, activeTurnId = "turn-1")
        assertTrue(active[0] is ChatTimelineItem.AssistantMessage)
        assertTrue(active[1] is ChatTimelineItem.AgentActivity)
        assertTrue((active[1] as ChatTimelineItem.AgentActivity).isStreaming)
    }

    @Test
    fun `turnSeq 全部存在时按序排列且缺失时保持原顺序`() {
        val sorted =
            buildChatTimelineItems(
                listOf(
                    message("late", content = "late", turnSeq = 2),
                    message("early", content = "early", turnSeq = 1),
                )
            ).filterIsInstance<ChatTimelineItem.AssistantMessage>()
        assertEquals(listOf("early", "late"), sorted.map { it.message.id })

        val fallback =
            buildChatTimelineItems(
                listOf(
                    message("first", content = "first", turnSeq = null),
                    message("second", content = "second", turnSeq = 1),
                )
            ).filterIsInstance<ChatTimelineItem.AssistantMessage>()
        assertEquals(listOf("first", "second"), fallback.map { it.message.id })
    }

    @Test
    fun `不同文件 activity segment 不会被错误合并`() {
        val items =
            buildChatTimelineItems(
                listOf(
                    fileTrace("edit-1", "segment-1", "a.kt"),
                    fileTrace("edit-2", "segment-2", "b.kt"),
                )
            )

        assertEquals(2, items.filterIsInstance<ChatTimelineItem.AgentActivity>().size)
    }

    @Test
    fun `媒体 Automation 和原始下标在正文中保留`() {
        val message =
            message("a1", content = "result").copy(
                images = listOf(UiImage(url = "https://example.invalid/a.png")),
                media = listOf(UiMediaAttachment(kind = "file", url = "/file", name = "a.txt")),
                source = UiMessageSource(kind = "automation", label = "Nightly"),
            )
        val item = buildChatTimelineItems(listOf(message)).single() as ChatTimelineItem.AssistantMessage

        assertEquals(0, item.originalIndex)
        assertEquals("Nightly", item.message.source?.label)
        assertEquals(1, item.message.images?.size)
        assertEquals(1, item.message.media?.size)
    }

    @Test
    fun `空 reasoning 占位不会产生无内容活动`() {
        val items = buildChatTimelineItems(listOf(message("empty", reasoning = "")))

        assertEquals(1, items.size)
        assertTrue(items.single() is ChatTimelineItem.AssistantMessage)
        assertFalse(items.single() is ChatTimelineItem.AgentActivity)
    }

    @Test
    fun `流式空 assistant 保留为活动占位等待后续增量`() {
        val items =
            buildChatTimelineItems(
                messages = listOf(message("streaming", turnId = "turn-1").copy(isStreaming = true)),
                activeTurnId = "turn-1",
            )

        val activity = items.single() as ChatTimelineItem.AgentActivity
        assertTrue(activity.isStreaming)
        assertEquals("streaming", activity.messages.single().id)
    }

    @Test
    fun `旧历史 tool 角色不会降级为 Marker`() {
        val items =
            buildChatTimelineItems(
                listOf(message("tool-1", role = "tool", content = "web_fetch(...)"))
            )

        assertEquals(1, items.size)
        assertTrue(items.single() is ChatTimelineItem.AgentActivity)
        assertFalse(items.single() is ChatTimelineItem.Marker)
    }

    @Test
    fun `同一轮连续旧工具合并为一个 Activity 并保持正文顺序`() {
        val items =
            buildChatTimelineItems(
                listOf(
                    message("user-1", role = "user", content = "调研", turnId = "turn-1"),
                    message("tool-1", role = "tool", content = "web_fetch(...)", turnId = "turn-1"),
                    message("tool-2", role = "tool", content = "web_search(...)", turnId = "turn-1"),
                    message("tool-3", role = "tool", content = "web_fetch(...)", turnId = "turn-1"),
                    message("answer-1", content = "调研完成", turnId = "turn-1"),
                )
            )

        assertEquals(3, items.size)
        assertTrue(items[0] is ChatTimelineItem.UserMessage)
        val activity = items[1] as ChatTimelineItem.AgentActivity
        assertEquals(listOf("tool-1", "tool-2", "tool-3"), activity.messages.map { it.id })
        assertTrue(items[2] is ChatTimelineItem.AssistantMessage)
    }

    @Test
    fun `不同 Turn 的旧工具不会跨轮错误合并`() {
        val items =
            buildChatTimelineItems(
                listOf(
                    message("user-1", role = "user", turnId = "turn-1"),
                    message("tool-1", role = "tool", content = "first", turnId = "turn-1"),
                    message("answer-1", content = "first done", turnId = "turn-1"),
                    message("user-2", role = "user", turnId = "turn-2"),
                    message("tool-2", role = "tool", content = "second", turnId = "turn-2"),
                    message("answer-2", content = "second done", turnId = "turn-2"),
                )
            )

        val activities = items.filterIsInstance<ChatTimelineItem.AgentActivity>()
        assertEquals(2, activities.size)
        assertEquals(listOf("tool-1"), activities[0].messages.map { it.id })
        assertEquals(listOf("tool-2"), activities[1].messages.map { it.id })
    }

    @Test
    fun `未知角色使用时间轴标记稳定降级`() {
        val items = buildChatTimelineItems(listOf(message("system", role = "system", content = "notice")))

        assertTrue(items.single() is ChatTimelineItem.Marker)
    }

    private fun fileTrace(id: String, segmentId: String, path: String): UiMessage =
        message(id, kind = "trace").copy(
            activitySegmentId = segmentId,
            fileEdits =
                listOf(
                    UiFileEdit(
                        callId = id,
                        tool = "apply_patch",
                        path = path,
                        status = "completed",
                    )
                ),
        )

    private fun message(
        id: String,
        role: String = "assistant",
        content: String = "",
        kind: String? = null,
        reasoning: String? = null,
        turnId: String? = null,
        turnSeq: Int? = null,
        toolEvents: List<ToolProgressEvent>? = null,
    ): UiMessage =
        UiMessage(
            id = id,
            role = role,
            content = content,
            kind = kind,
            createdAt = 1L,
            reasoning = reasoning,
            turnId = turnId,
            turnSeq = turnSeq,
            toolEvents = toolEvents,
        )
}
