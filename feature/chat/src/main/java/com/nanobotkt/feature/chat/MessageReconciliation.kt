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
 * 用服务端最新页替换本地规范时间线的重叠后缀。
 *
 * HTTP transcript replay 生成的消息 ID 可能随每次请求变化，因此不能把“没有连续语义重叠”解释成
 * “服务器返回了更早的一页”。latest 请求本身就是当前窗口的权威快照；此时若把 latest 再拼到
 * current 前面，每经历一次后台恢复都会完整复制一次历史消息。
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
    // latest 没有任何连续边界时直接重置为服务端快照。旧 current 可能包含过期进度状态或
    // 使用另一批 replay ID 的同一段历史，保留它没有可靠依据，只会造成重复和状态回退。
    return latest
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
