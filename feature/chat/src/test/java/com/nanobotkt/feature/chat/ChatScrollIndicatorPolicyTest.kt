package com.nanobotkt.feature.chat

import com.nanobotkt.core.model.UiMessage
import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * 锁定“新消息 / 回到底部”入口的纯状态策略。
 *
 * UI 只负责根据滚动位置显示圆形按钮；是否出现数字 Badge 由这里的回复轮次 key 决定。
 * 这些测试重点覆盖流式更新、同一回复内的工具消息以及头部分页，避免把 token、工具步骤
 * 或刚加载的旧历史错误计算成多条新消息。
 */
class ChatScrollIndicatorPolicyTest {
    @Test
    fun `同一回复轮次中的 reasoning 工具和正文只生成一个 key`() {
        val messages =
            listOf(
                message(id = "reasoning", role = "assistant", turnId = "turn-1"),
                message(id = "tool", role = "tool", turnId = "turn-1"),
                message(id = "answer", role = "assistant", turnId = "turn-1"),
            )

        assertEquals(listOf("turn:turn-1"), incomingAssistantTurnKeys(messages))
    }

    @Test
    fun `缺少 turnId 时同一消息的流式更新不会重复计数`() {
        val messages =
            listOf(
                message(id = "streaming-answer", content = "第一段"),
                message(id = "streaming-answer", content = "第一段和第二段"),
            )

        // 流式正文会持续替换内容，但 message id 稳定，因此 Badge 仍只代表一条回复。
        assertEquals(listOf("message:streaming-answer"), incomingAssistantTurnKeys(messages))
    }

    @Test
    fun `用户消息不属于入站回复`() {
        val messages =
            listOf(
                message(id = "user-1", role = "user"),
                message(id = "assistant-1", role = "assistant"),
            )

        assertEquals(listOf("message:assistant-1"), incomingAssistantTurnKeys(messages))
    }

    @Test
    fun `头部加载旧历史不会增加未读数量`() {
        val acknowledged = setOf("turn:seen-1", "turn:seen-2")
        val current = listOf("turn:older-1", "turn:older-2", "turn:seen-1", "turn:seen-2")

        // 已确认边界仍在列表尾部，前面插入的分页结果不能触发“新消息”提示。
        assertEquals(0, unreadIncomingTurnCount(current, acknowledged))
    }

    @Test
    fun `尾部追加回复轮次会正确累计未读数量`() {
        val acknowledged = setOf("turn:seen-1", "turn:seen-2")
        val current =
            listOf(
                "turn:older-1",
                "turn:seen-1",
                "turn:seen-2",
                "turn:new-1",
                "turn:new-2",
            )

        assertEquals(2, unreadIncomingTurnCount(current, acknowledged))
    }

    @Test
    fun `Badge 数字超过九条时使用紧凑上限`() {
        assertEquals("0", unreadBadgeLabel(0))
        assertEquals("3", unreadBadgeLabel(3))
        assertEquals("9+", unreadBadgeLabel(10))
    }

    /** 构造本组测试需要的最小消息，未参与策略的字段保持稳定默认值。 */
    private fun message(
        id: String,
        role: String = "assistant",
        content: String = "",
        turnId: String? = null,
    ): UiMessage =
        UiMessage(
            id = id,
            role = role,
            content = content,
            createdAt = 1L,
            turnId = turnId,
        )
}
