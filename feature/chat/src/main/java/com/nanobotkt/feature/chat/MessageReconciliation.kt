package com.nanobotkt.feature.chat

import com.nanobotkt.core.model.UiMessage

/** Matches the React Native store's stable-id and semantic fallback identity rules. */
internal fun sameSemanticMessage(left: UiMessage, right: UiMessage): Boolean {
    if (left.id.isNotEmpty() && right.id.isNotEmpty() && left.id == right.id) return true
    return left.role == right.role &&
        left.kind.orEmpty() == right.kind.orEmpty() &&
        left.content == right.content &&
        (left.turnId == null || right.turnId == null || left.turnId == right.turnId)
}

/**
 * Replaces an overlapping latest suffix with the canonical server page.
 *
 * When the server page has no semantic overlap, preserve the current page and prepend only records
 * whose stable IDs are not already present. This deliberately mirrors the RN reconciliation order.
 */
internal fun mergeLatestMessages(
    current: List<UiMessage>,
    latest: List<UiMessage>,
): List<UiMessage> {
    if (current.isEmpty()) return latest
    val maxOverlap = minOf(current.size, latest.size)
    for (overlap in maxOverlap downTo 1) {
        val start = current.size - overlap
        val matches = (0 until overlap).all { index ->
            sameSemanticMessage(current[start + index], latest[index])
        }
        if (matches) return current.subList(0, start) + latest
    }
    val seenIds = current.mapNotNullTo(mutableSetOf()) { message ->
        message.id.takeIf(String::isNotEmpty)
    }
    val extras = latest.filter { message -> message.id.isEmpty() || message.id !in seenIds }
    return extras + current
}

/** Prepends only messages before the current oldest semantic boundary. */
internal fun prependOlderMessages(
    current: List<UiMessage>,
    older: List<UiMessage>,
): List<UiMessage> {
    if (older.isEmpty()) return current
    val firstCurrent = current.firstOrNull()
    val boundary = if (firstCurrent == null) {
        -1
    } else {
        older.indexOfFirst { message -> sameSemanticMessage(message, firstCurrent) }
    }
    val prefix = if (boundary >= 0) older.subList(0, boundary) else older
    val seenIds = current.mapTo(mutableSetOf(), UiMessage::id)
    return prefix.filter { it.id !in seenIds } + current
}
