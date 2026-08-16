package com.nanobotkt.feature.chat

import com.nanobotkt.core.model.UiMessage
import com.nanobotkt.core.model.WebUiIngressLimits

/**
 * 时间线的不可变输入快照。
 *
 * Repository 内部可以继续用适合增量归并的集合，但进入 StateFlow 前必须先冻结成此快照，
 * 使排序、去重和 FAILED 标记计算成为无副作用纯函数，单元测试无需启动 WebSocket 或协程。
 */
internal data class ChatTimelineInput(
    val canonical: List<UiMessage>,
    val optimistic: List<UiMessage>,
    val failedMessageIds: Set<String>,
    val transient: List<UiMessage>,
)

internal data class ChatTimelineProjection(
    val messages: List<UiMessage>,
    val failedMessageIds: Set<String>,
)

internal data class ChatTimelineMetadata(
    val loading: Boolean,
    val loadingOlder: Boolean,
    val hasMoreBefore: Boolean,
    val beforeCursor: String?,
    val activeTurnId: String?,
    val userMessageOffset: Int,
)

/**
 * 把规范历史、本地乐观消息和流式临时消息投影成唯一 UI 时间线。
 *
 * 服务端规范消息按 turnId 淘汰同 turn 的乐观气泡；规范 assistant 消息同样淘汰流式快照，
 * 防止 canonical refresh 与 WebSocket 结束事件交错时出现重复回答。
 */
internal fun projectChatTimeline(input: ChatTimelineInput): ChatTimelineProjection {
    val canonicalTurns = input.canonical.mapNotNullTo(mutableSetOf(), UiMessage::turnId)
    val canonicalAssistantTurns = canonicalAssistantTurnIds(input.canonical)
    val messages = buildList {
        addAll(input.canonical)
        addAll(input.optimistic.filter { message -> message.turnId !in canonicalTurns })
        addAll(
            input.transient.filterNot { message ->
                message.turnId != null && message.turnId in canonicalAssistantTurns
            },
        )
    }.sortedBy(UiMessage::createdAt)
    return ChatTimelineProjection(
        messages = messages,
        failedMessageIds = input.failedMessageIds.toSet(),
    )
}

/** 纯函数只替换时间线负责的字段，目录、Workspace、Model 与错误等并发状态保持调用时最新值。 */
internal fun reduceChatTimeline(
    current: ChatUiState,
    projection: ChatTimelineProjection,
    metadata: ChatTimelineMetadata,
    limits: WebUiIngressLimits?,
): ChatUiState = current.copy(
    messages = projection.messages,
    failedMessageIds = projection.failedMessageIds,
    loading = metadata.loading,
    loadingOlder = metadata.loadingOlder,
    hasMoreBefore = metadata.hasMoreBefore,
    beforeCursor = metadata.beforeCursor,
    activeTurnId = metadata.activeTurnId,
    userMessageOffset = metadata.userMessageOffset,
    limits = limits,
)

internal fun canonicalAssistantTurnIds(messages: List<UiMessage>): Set<String> = messages
    .asSequence()
    .filter { message -> message.role != "user" }
    .mapNotNull(UiMessage::turnId)
    .toSet()
