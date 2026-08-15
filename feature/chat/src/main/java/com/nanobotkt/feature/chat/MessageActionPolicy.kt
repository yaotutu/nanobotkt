package com.nanobotkt.feature.chat

import com.nanobotkt.core.model.UiMessage

/** 长按菜单只暴露用户已确认的四个低频动作。 */
internal enum class MessageAction {
    COPY,
    QUOTE,
    FORK,
    VIEW,
}

/**
 * 根据消息状态裁剪菜单项，避免展示点击后无法完成的伪操作。
 *
 * - 失败消息只允许复制和查看，重试由消息右侧常驻按钮承担。
 * - 排队消息不能 Fork，但可以引用已经展示在时间轴中的原文。
 * - 流式 Assistant 允许复制当前内容、引用和查看，但必须等完成后才能 Fork。
 */
internal fun availableMessageActions(
    role: String,
    deliveryState: UserMessageDeliveryState = UserMessageDeliveryState.SENT,
    streaming: Boolean = false,
    canFork: Boolean = false,
    hasContent: Boolean = true,
): List<MessageAction> {
    if (!hasContent) return emptyList()
    if (deliveryState == UserMessageDeliveryState.FAILED) {
        return listOf(MessageAction.COPY, MessageAction.VIEW)
    }
    return buildList {
        add(MessageAction.COPY)
        add(MessageAction.QUOTE)
        if (canFork && !streaming && deliveryState != UserMessageDeliveryState.QUEUED) {
            add(MessageAction.FORK)
        }
        add(MessageAction.VIEW)
    }
}

internal fun assistantForkIndexes(
    messages: List<UiMessage>,
    userMessageOffset: Int,
): List<Int?> {
    val finalAssistant = MutableList(messages.size) { true }
    var hasLaterMessageBeforeUser = false
    for (index in messages.indices.reversed()) {
        val message = messages[index]
        if (message.role == "user") {
            hasLaterMessageBeforeUser = false
            continue
        }
        if (message.role == "assistant") {
            finalAssistant[index] = !hasLaterMessageBeforeUser
        }
        hasLaterMessageBeforeUser = true
    }

    var nextUserIndex = userMessageOffset.coerceAtLeast(0)
    return messages.mapIndexed { index, message ->
        val forkIndex = if (
            message.role == "assistant" &&
            message.kind != "trace" &&
            finalAssistant[index]
        ) nextUserIndex else null
        if (message.role == "user") nextUserIndex += 1
        forkIndex
    }
}

/** 保留底层能力判断供既有 ViewModel 契约测试使用，但聊天时间轴不展示 Assistant Retry。 */
internal fun canRetryFromMessage(messages: List<UiMessage>, index: Int): Boolean {
    val message = messages.getOrNull(index) ?: return false
    if (message.role != "assistant" || message.kind == "trace" || message.isStreaming == true) return false
    if (index != messages.indexOfLast { it.kind != "trace" }) return false
    return messages.drop(index + 1).none { it.role == "user" }
}
