package com.nanobotkt.feature.chat

import com.nanobotkt.core.model.ToolProgressEvent
import com.nanobotkt.core.model.UiFileEdit
import com.nanobotkt.core.model.UiMessage
import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * 锁定 Activity 在主时间轴中的视觉等级，避免后续新增 trace 类型时把内部推理重新暴露成大卡片。
 *
 * 这些测试只验证纯展示策略，不依赖 Compose 运行时：Gateway 原始消息先被装入不可变时间轴模型，
 * 再由 [activityDisplayMode] 根据执行状态和可复查价值决定隐藏、紧凑或强调展示。
 */
class AgentActivityDisplayModeTest {
    @Test
    fun `完成的纯 reasoning 默认隐藏`() {
        val item = activity(message(reasoning = "内部分析过程"))

        assertEquals(ActivityDisplayMode.Hidden, activityDisplayMode(item))
    }

    @Test
    fun `完成的真实工具保留紧凑摘要`() {
        val item =
            activity(
                message(
                    toolEvents =
                        listOf(
                            ToolProgressEvent(
                                phase = "completed",
                                callId = "read-1",
                                name = "read_file",
                            )
                        )
                )
            )

        assertEquals(ActivityDisplayMode.Compact, activityDisplayMode(item))
    }

    @Test
    fun `完成的文件修改保留紧凑摘要`() {
        val item =
            activity(
                message(
                    fileEdits =
                        listOf(
                            UiFileEdit(
                                callId = "patch-1",
                                tool = "apply_patch",
                                path = "feature/chat/ChatScreen.kt",
                                status = "completed",
                            )
                        )
                )
            )

        assertEquals(ActivityDisplayMode.Compact, activityDisplayMode(item))
    }

    @Test
    fun `完成的普通 trace 文本默认隐藏`() {
        val item = activity(message(content = "内部过程", kind = "trace"))

        assertEquals(ActivityDisplayMode.Hidden, activityDisplayMode(item))
    }

    @Test
    fun `完成的旧 tool 角色保留紧凑摘要`() {
        val item = activity(message(role = "tool", content = "web_fetch(...)", kind = null))

        assertEquals(ActivityDisplayMode.Compact, activityDisplayMode(item))
    }

    @Test
    fun `旧 tool 角色按真实消息数量计算步骤`() {
        val item =
            activity(
                message(role = "tool", content = "same", kind = null),
                message(role = "tool", content = "same", kind = null),
                message(role = "tool", content = "", kind = null),
            )

        // 相同或空结果仍代表独立工具调用，摘要必须显示 3 个步骤，不能被 distinct/filter 吞掉。
        assertEquals(3, activityStepCount(item))
    }

    @Test
    fun `结构化 role tool 不会与 content 重复计数`() {
        val item =
            activity(
                message(
                    role = "tool",
                    content = "web_search(...)",
                    toolEvents =
                        listOf(
                            ToolProgressEvent(
                                phase = "completed",
                                callId = "search-1",
                                name = "web_search",
                            )
                        ),
                )
            )

        assertEquals(1, activityStepCount(item))
    }

    @Test
    fun `流式空占位使用强调展示`() {
        val item = activity(message(), isStreaming = true)

        assertEquals(ActivityDisplayMode.Emphasized, activityDisplayMode(item))
    }

    @Test
    fun `工具失败使用强调展示`() {
        val item =
            activity(
                message(
                    toolEvents =
                        listOf(
                            ToolProgressEvent(
                                phase = "failed",
                                callId = "tool-1",
                                name = "shell",
                            )
                        )
                )
            )

        assertEquals(ActivityDisplayMode.Emphasized, activityDisplayMode(item))
    }

    @Test
    fun `等待用户确认使用强调展示`() {
        val item =
            activity(
                message(
                    toolEvents =
                        listOf(
                            ToolProgressEvent(
                                phase = "awaiting_confirmation",
                                callId = "confirm-1",
                                name = "dangerous_action",
                            )
                        )
                )
            )

        assertEquals(ActivityDisplayMode.Emphasized, activityDisplayMode(item))
    }

    @Test
    fun `可见时间轴移除隐藏 Activity 并保持其他单元顺序`() {
        val user =
            ChatTimelineItem.UserMessage(
                message = message(role = "user", content = "hello", kind = null),
                originalIndex = 0,
            )
        val hidden = activity(message(reasoning = "内部分析过程"))
        val durable =
            activity(
                message(
                    toolEvents =
                        listOf(
                            ToolProgressEvent(
                                phase = "completed",
                                callId = "read-2",
                                name = "read_file",
                            )
                        )
                )
            )

        val visible = visibleChatTimelineItems(listOf(user, hidden, durable))

        // 隐藏项必须在生成 LazyColumn 索引前移除；保留下来的顺序决定导航和尾部滚动目标。
        assertEquals(listOf(user, durable), visible)
    }

    /** 构造最小展示模型，避免测试被 Mapper 的排序和合并规则干扰。 */
    private fun activity(
        vararg messages: UiMessage,
        isStreaming: Boolean = false,
    ): ChatTimelineItem.AgentActivity =
        ChatTimelineItem.AgentActivity(
            key = "activity:test",
            messages = messages.toList(),
            turnId = null,
            turnLatencyMs = null,
            startedAtMs = null,
            isStreaming = isStreaming,
        )

    /** 只填充本组测试关心的 Activity 字段，其余传输字段保持稳定默认值。 */
    private fun message(
        role: String = "assistant",
        content: String = "",
        kind: String? = "trace",
        reasoning: String? = null,
        toolEvents: List<ToolProgressEvent>? = null,
        fileEdits: List<UiFileEdit>? = null,
    ): UiMessage =
        UiMessage(
            id = "message:test",
            role = role,
            content = content,
            kind = kind,
            createdAt = 1L,
            reasoning = reasoning,
            toolEvents = toolEvents,
            fileEdits = fileEdits,
        )
}
