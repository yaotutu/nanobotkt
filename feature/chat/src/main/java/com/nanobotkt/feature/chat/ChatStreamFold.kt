package com.nanobotkt.feature.chat

import com.nanobotkt.core.model.InboundEvent
import com.nanobotkt.core.model.ToolProgressEvent
import com.nanobotkt.core.model.UiFileEdit
import com.nanobotkt.core.model.UiMediaAttachment
import com.nanobotkt.core.model.UiMessage
import java.util.UUID

/**
 * Pure in-memory fold for one chat's transient WebSocket stream.
 * Canonical history remains owned by [ChatRepository]; this class only models events that have
 * not yet been reconciled into the server thread payload.
 */
internal class ChatStreamFold(
    private val now: () -> Long = System::currentTimeMillis,
    private val newId: () -> String = { UUID.randomUUID().toString() },
) {
    private val messages = mutableListOf<UiMessage>()
    private val activeMessageByKey = mutableMapOf<String, String>()
    /**
     * 服务端重连或缓冲重放时可能重复投递同一 delta。按与 activeMessage 相同的流身份保存
     * sequence 水位，确保重复或乱序的旧片段不会再次追加到正文/思考内容。
     */
    private val lastSequenceByKey = mutableMapOf<String, Int>()
    private val completedTurns = mutableSetOf<String>()
    private val mediaCompletedTurns = mutableSetOf<String>()

    fun snapshot(): List<UiMessage> = messages.toList()

    fun reset() {
        messages.clear()
        activeMessageByKey.clear()
        lastSequenceByKey.clear()
        completedTurns.clear()
        mediaCompletedTurns.clear()
    }

    /** Removes transient projections now represented by canonical history. */
    fun discardTurns(turnIds: Set<String>) {
        if (turnIds.isEmpty()) return
        val removedMessageIds = messages
            .filter { it.turnId in turnIds }
            .mapTo(mutableSetOf(), UiMessage::id)
        messages.removeAll { it.turnId in turnIds }
        activeMessageByKey.entries.removeAll { entry ->
            entry.value in removedMessageIds || turnIds.any { turnId -> entry.key.startsWith("turn:$turnId") }
        }
        lastSequenceByKey.keys.removeAll { key ->
            turnIds.any { turnId -> key.startsWith("turn:$turnId") }
        }
        mediaCompletedTurns.removeAll(turnIds)
    }

    /** Records canonical completion so late socket events cannot recreate a finished turn. */
    fun markCompletedTurns(turnIds: Set<String>) {
        discardTurns(turnIds)
        completedTurns.addAll(turnIds)
    }

    fun fold(event: InboundEvent): List<UiMessage> {
        when (event) {
            is InboundEvent.Delta -> appendDelta(
                turnId = event.turnId,
                streamId = event.streamId,
                text = event.text,
                reasoning = false,
                phase = event.turnPhase,
                sequence = event.turnSeq,
            )
            is InboundEvent.ReasoningDelta -> appendDelta(
                turnId = event.turnId,
                streamId = event.streamId,
                text = event.text,
                reasoning = true,
                phase = event.turnPhase,
                sequence = event.turnSeq,
            )
            is InboundEvent.ReasoningEnd -> updateActive(event.turnId, event.streamId) {
                it.copy(reasoningStreaming = false)
            }
            is InboundEvent.FileEdit -> foldFileEdits(event)
            is InboundEvent.Message -> foldMessage(event)
            is InboundEvent.StreamEnd -> endStream(event)
            is InboundEvent.TurnEnd -> endTurn(event)
            else -> Unit
        }
        return snapshot()
    }

    private fun appendDelta(
        turnId: String?,
        streamId: String?,
        text: String,
        reasoning: Boolean,
        phase: String?,
        sequence: Int?,
    ) {
        if (turnId != null && (turnId in completedTurns || turnId in mediaCompletedTurns)) return
        val key = streamKey(turnId, streamId)
        if (!acceptSequence(key, sequence)) return
        updateOrCreate(turnId, streamId, phase, sequence) { current ->
            if (reasoning) {
                current.copy(
                    reasoning = current.reasoning.orEmpty() + text,
                    reasoningStreaming = true,
                )
            } else {
                current.copy(content = current.content + text, isStreaming = true)
            }
        }
    }

    private fun foldMessage(event: InboundEvent.Message) {
        val turnId = event.turnId
        if (turnId != null && turnId in completedTurns) return
        val isProgress = event.kind == "progress" || !event.toolEvents.isNullOrEmpty()
        val existingIndex = findTurnMessageIndex(turnId, preferTrace = isProgress)
        val base = if (existingIndex >= 0) {
            messages[existingIndex]
        } else {
            newAssistant(turnId, event.turnPhase, event.turnSeq, trace = isProgress)
        }
        val foldedTools = mergeToolEvents(base.toolEvents.orEmpty(), event.toolEvents.orEmpty())
        val media =
            event.media?.map { url ->
                UiMediaAttachment(kind = inferTimelineMediaKind(url = url), url = url)
            }
        val completed = base.copy(
            content = event.text,
            kind = if (isProgress) "trace" else event.kind,
            isStreaming = false,
            toolEvents = foldedTools.ifEmpty { null },
            latencyMs = event.latencyMs,
            source = event.source,
            media = media,
            images = event.mediaUrls,
            completedAt = now(),
            turnId = turnId ?: base.turnId,
            turnPhase = event.turnPhase ?: if (isProgress) "activity" else "answer",
            turnSeq = event.turnSeq ?: base.turnSeq,
        )
        if (existingIndex >= 0) messages[existingIndex] = completed else messages += completed
        turnId?.let { id ->
            activeMessageByKey.entries.removeAll { it.value == completed.id && it.key.startsWith("turn:$id") }
            if (!event.media.isNullOrEmpty() || !event.mediaUrls.isNullOrEmpty()) mediaCompletedTurns += id
        }
    }

    private fun foldFileEdits(event: InboundEvent.FileEdit) {
        if (event.turnId != null && event.turnId in completedTurns) return
        val index = findTurnMessageIndex(event.turnId, preferTrace = true)
        val base = if (index >= 0) messages[index] else newAssistant(
            event.turnId,
            event.turnPhase ?: "activity",
            event.turnSeq,
            trace = true,
        )
        val updated = base.copy(
            kind = "trace",
            turnPhase = event.turnPhase ?: "activity",
            fileEdits = mergeFileEdits(base.fileEdits.orEmpty(), event.edits),
        )
        if (index >= 0) messages[index] = updated else messages += updated
    }

    private fun endStream(event: InboundEvent.StreamEnd) {
        if (event.turnId != null && event.turnId in completedTurns) return
        val key = streamKey(event.turnId, event.streamId)
        val id = activeMessageByKey[key] ?: return
        val index = messages.indexOfFirst { it.id == id }
        if (index < 0) return
        messages[index] = messages[index].copy(
            content = event.text ?: messages[index].content,
            isStreaming = event.resuming == true,
            completedAt = if (event.resuming == true) null else now(),
        )
        if (event.resuming != true || event.mergeNext == false) activeMessageByKey.remove(key)
    }

    private fun endTurn(event: InboundEvent.TurnEnd) {
        val turnId = event.turnId
        if (turnId != null) {
            completedTurns += turnId
            mediaCompletedTurns -= turnId
            activeMessageByKey.keys.removeAll { it.startsWith("turn:$turnId") }
            // turn 已进入 completedTurns，后续事件会在入口被拒绝；水位可以同步释放，避免长会话泄漏。
            lastSequenceByKey.keys.removeAll { it.startsWith("turn:$turnId") }
        }
        val completedAt = now()
        messages.replaceAll { message ->
            if (turnId == null || message.turnId == turnId) {
                message.copy(
                    isStreaming = false,
                    reasoningStreaming = false,
                    latencyMs = event.latencyMs ?: message.latencyMs,
                    completedAt = completedAt,
                    turnPhase = "complete",
                )
            } else {
                message
            }
        }
        messages.removeAll { message ->
            (turnId == null || message.turnId == turnId) &&
                message.content.isBlank() &&
                message.toolEvents.isNullOrEmpty() &&
                message.fileEdits.isNullOrEmpty() &&
                message.media.isNullOrEmpty() &&
                message.images.isNullOrEmpty()
        }
    }

    private fun updateActive(
        turnId: String?,
        streamId: String?,
        update: (UiMessage) -> UiMessage,
    ) {
        val id = activeMessageByKey[streamKey(turnId, streamId)] ?: return
        val index = messages.indexOfFirst { it.id == id }
        if (index >= 0) messages[index] = update(messages[index])
    }

    private fun updateOrCreate(
        turnId: String?,
        streamId: String?,
        phase: String?,
        sequence: Int?,
        update: (UiMessage) -> UiMessage,
    ) {
        val key = streamKey(turnId, streamId)
        val existingId = activeMessageByKey[key]
        val index = existingId?.let { id -> messages.indexOfFirst { it.id == id } } ?: -1
        if (index >= 0) {
            messages[index] = update(messages[index])
        } else {
            val created = update(newAssistant(turnId, phase, sequence))
            messages += created
            activeMessageByKey[key] = created.id
        }
    }

    /**
     * 旧协议没有 turn_seq 时保持兼容，不做猜测性去重；新协议只接受严格递增的序号。
     * 水位与 [streamKey] 共用身份规则，避免用另一套 key 导致 reasoning/content 分叉失效。
     */
    private fun acceptSequence(key: String, sequence: Int?): Boolean {
        if (sequence == null) return true
        val previous = lastSequenceByKey[key]
        if (previous != null && sequence <= previous) return false
        lastSequenceByKey[key] = sequence
        return true
    }

    private fun findTurnMessageIndex(turnId: String?, preferTrace: Boolean): Int {
        if (turnId == null) return -1
        val preferred = messages.indexOfLast {
            it.turnId == turnId && ((it.kind == "trace") == preferTrace)
        }
        return if (preferred >= 0) preferred else messages.indexOfLast { it.turnId == turnId }
    }

    private fun newAssistant(
        turnId: String?,
        phase: String?,
        sequence: Int?,
        trace: Boolean = false,
    ): UiMessage = UiMessage(
        id = "stream:${newId()}",
        role = "assistant",
        content = "",
        kind = if (trace) "trace" else null,
        isStreaming = true,
        createdAt = now(),
        turnId = turnId,
        turnPhase = phase ?: if (trace) "activity" else null,
        turnSeq = sequence,
    )

    private fun mergeToolEvents(
        existing: List<ToolProgressEvent>,
        incoming: List<ToolProgressEvent>,
    ): List<ToolProgressEvent> {
        val merged = existing.toMutableList()
        incoming.forEach { event ->
            val index = merged.indexOfFirst { current ->
                event.callId != null && current.callId == event.callId ||
                    event.callId == null && current.callId == null && current.name == event.name
            }
            if (index >= 0) merged[index] = event else merged += event
        }
        return merged
    }

    private fun mergeFileEdits(
        existing: List<UiFileEdit>,
        incoming: List<UiFileEdit>,
    ): List<UiFileEdit> {
        val merged = existing.toMutableList()
        incoming.forEach { edit ->
            val index = merged.indexOfFirst { it.callId == edit.callId }
            if (index >= 0) merged[index] = edit else merged += edit
        }
        return merged
    }

    private fun streamKey(turnId: String?, streamId: String?): String = when {
        turnId != null && streamId != null -> "turn:$turnId:stream:$streamId"
        turnId != null -> "turn:$turnId"
        streamId != null -> "stream:$streamId"
        else -> "unscoped"
    }

}
