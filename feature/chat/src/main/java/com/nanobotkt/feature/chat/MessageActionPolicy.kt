package com.nanobotkt.feature.chat

import com.nanobotkt.core.model.UiMessage

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

internal fun canRetryFromMessage(messages: List<UiMessage>, index: Int): Boolean {
    val message = messages.getOrNull(index) ?: return false
    if (message.role != "assistant" || message.kind == "trace" || message.isStreaming == true) return false
    if (index != messages.indexOfLast { it.kind != "trace" }) return false
    return messages.drop(index + 1).none { it.role == "user" }
}