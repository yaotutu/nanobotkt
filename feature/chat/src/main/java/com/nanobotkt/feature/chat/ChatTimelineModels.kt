package com.nanobotkt.feature.chat

import com.nanobotkt.core.model.UiMessage

/**
 * 聊天时间轴的展示单元。
 *
 * Gateway 返回的 [UiMessage] 同时承担传输、流式折叠和历史回放职责，不能直接等同于一行 UI：
 * reasoning、tool trace 和文件修改虽然也是消息记录，但产品上属于同一个 Agent 活动组。
 * 因此本模型只存在于 chat feature 的展示层，不反向写回 Repository，也不改变 WebSocket 协议。
 */
internal sealed interface ChatTimelineItem {
    /** LazyColumn 使用的稳定键；流式增量到达时不得因为内容变化而重建整个单元。 */
    val key: String

    /** 用户输入始终是一级时间轴消息，附件是消息内部内容块而不是独立行。 */
    data class UserMessage(
        val message: UiMessage,
        val originalIndex: Int,
    ) : ChatTimelineItem {
        override val key: String = "user:${message.id}"
    }

    /** 助手最终正文是独立阅读内容，Reasoning 会在 Mapper 中剥离到 AgentActivity。 */
    data class AssistantMessage(
        val message: UiMessage,
        val originalIndex: Int,
    ) : ChatTimelineItem {
        override val key: String = "assistant:${message.id}"
    }

    /**
     * 同一段 Agent 执行活动。
     *
     * [messages] 保留原始 trace 数据，渲染层据此提取 reasoning、tool、CLI/MCP 和 file edit。
     * 展开/折叠属于临时 UI 状态，不放进这个不可变模型。
     */
    data class AgentActivity(
        override val key: String,
        val messages: List<UiMessage>,
        val turnId: String?,
        val turnLatencyMs: Long?,
        val startedAtMs: Long?,
        val isStreaming: Boolean,
    ) : ChatTimelineItem

    /**
     * 非 user/assistant 角色的真实历史记录使用轻量标记兜底。
     * 当前 Gateway 通常不会返回此类记录，但保留稳定降级可以避免未知角色被误画成助手回答。
     */
    data class Marker(
        val message: UiMessage,
        val originalIndex: Int,
    ) : ChatTimelineItem {
        override val key: String = "marker:${message.id}"
    }
}
