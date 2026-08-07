package com.nanobotkt.feature.chat

import com.nanobotkt.core.model.UiMessage

/**
 * A single user prompt extracted from the canonical + optimistic + stream-fold
 * message list. Mirrors the RN [PromptAnchor] shape.
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
 * Extracts user-prompt anchors from the merged message list.
 * Only messages with `role == "user"` are included.
 * Mirrors `userPromptAnchors()` in the RN codebase.
 */
fun extractPromptAnchors(messages: List<UiMessage>): List<PromptNavigatorItem> {
    var promptOrdinal = 0
    return buildList {
        for (i in messages.indices) {
            val message = messages[i]
            if (message.role != "user") continue
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
 * Filters prompt anchors by a search query (case-insensitive substring match
 * on label + preview).
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

// ---------------------------------------------------------------------------
// Private helpers — direct ports of RN prompt-navigation.ts helpers
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
