package com.nanobotkt.feature.chat

import com.nanobotkt.core.model.UiMessage

/**
 * 从已合并的正式消息、乐观消息与流式折叠结果中提取的一条用户 Prompt。
 * 字段形状与 RN 端的 `PromptAnchor` 保持一致，便于两端共享相同导航语义。
 */
data class PromptNavigatorItem(
    /** Stable identifier — the message id. */
    val stableId: String,
    /** The user message id (same as stableId for now). */
    val messageId: String,
    /** Short label (first 80 chars). */
    val label: String,
    /** Preview text (first 320 chars, compacted whitespace). */
    val preview: String,
    /** First 240 chars of the following assistant message, if any. */
    val answerPreview: String,
    /** Unix-epoch milliseconds. */
    val createdAt: Long,
    /** 0-based sequential index across all user prompts. */
    val ordinal: Int,
)

/**
 * 从合并后的消息列表中提取用户 Prompt 锚点。
 * 这里只接收 `role == "user"` 的消息，并与 RN 端 `userPromptAnchors()` 保持相同筛选规则。
 */
fun extractPromptAnchors(messages: List<UiMessage>): List<PromptNavigatorItem> {
    var promptOrdinal = 0
    return buildList {
        for (i in messages.indices) {
            val message = messages[i]
            if (!message.isPromptNavigatorEntry()) continue
            add(
                PromptNavigatorItem(
                    stableId = message.id,
                    messageId = message.id,
                    label = promptLabel(message.content, promptOrdinal),
                    preview = promptPreview(message.content, promptOrdinal),
                    answerPreview = nextAssistantPreview(messages, i),
                    createdAt = message.createdAt,
                    ordinal = promptOrdinal,
                )
            )
            promptOrdinal += 1
        }
    }
}

/**
 * 按搜索词过滤 Prompt 锚点；匹配时忽略大小写，并同时检索标题和预览文本。
 */
fun filterPrompts(
    items: List<PromptNavigatorItem>,
    query: String,
): List<PromptNavigatorItem> {
    val needle = query.trim().lowercase()
    if (needle.isEmpty()) return items
    return items.filter { item ->
        "${item.label}\n${item.preview}".lowercase().contains(needle)
    }
}


/**
 * Prompt 导航只收录已经进入会话历史的用户指令。失败消息和仍在本地 Queue 中的内容不属于可稳定
 * 跳转的历史锚点；Automation 指令仍然是 role=user，因此会自然保留并由 UI 展示来源标签。
 */
private fun UiMessage.isPromptNavigatorEntry(): Boolean {
    if (role != "user" || kind == "trace") return false
    val phase = turnPhase?.lowercase()
    return phase !in setOf("queued", "pending", "failed", "error", "send_failed")
}

// ---------------------------------------------------------------------------
// 私有辅助函数直接对齐 RN 端 prompt-navigation.ts 的文本规整规则。
// ---------------------------------------------------------------------------

private fun promptLabel(content: String, index: Int): String {
    val text = content.replace(Regex("\\s+"), " ").trim()
    if (text.isEmpty()) return "Prompt ${index + 1}"
    return truncatePreview(text, 80)
}

private fun promptPreview(content: String, index: Int): String {
    val text = compactPreview(content)
    if (text.isEmpty()) return "Prompt ${index + 1}"
    return truncatePreview(text, 320)
}

private fun nextAssistantPreview(messages: List<UiMessage>, promptIndex: Int): String {
    for (i in (promptIndex + 1) until messages.size) {
        val message = messages[i]
        if (message.role == "user") return ""
        if (message.role != "assistant") continue
        val preview = truncatePreview(compactPreview(message.content), 240)
        if (preview.isNotEmpty()) return preview
    }
    return ""
}

private fun compactPreview(content: String): String =
    content.replace(Regex("\\n{3,}"), "\n\n").trim()

private fun truncatePreview(text: String, maxLength: Int): String =
    if (text.length > maxLength) "${text.take(maxLength - 3)}..." else text
