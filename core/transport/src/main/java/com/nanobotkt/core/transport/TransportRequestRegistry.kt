package com.nanobotkt.core.transport

import kotlinx.coroutines.CompletableDeferred
import java.util.concurrent.ConcurrentHashMap

/**
 * WebSocket 请求关联表。
 *
 * Transport 负责协议帧和连接生命周期，本类只负责 pending 请求的登记、精确领取和断线清理。
 * 所有完成操作都先从表中移除再完成 Deferred，因此超时、取消、服务端响应和断线同时到达时，
 * 最多只有一个路径能够取得请求并产生结果；迟到响应只会得到 null，不会复活已经结束的请求。
 */
internal class TransportRequestRegistry {
    private val newChatLock = Any()
    private var pendingNewChat: CompletableDeferred<String>? = null
    private val messages = ConcurrentHashMap<String, PendingTransportMessage>()
    private val transcriptions = ConcurrentHashMap<String, CompletableDeferred<String>>()
    private val systemCommands = ConcurrentHashMap<String, CompletableDeferred<Unit>>()

    fun registerNewChat(request: CompletableDeferred<String>) {
        synchronized(newChatLock) {
            check(pendingNewChat == null) { "new_chat_pending" }
            pendingNewChat = request
        }
    }

    fun hasPendingNewChat(): Boolean = synchronized(newChatLock) { pendingNewChat != null }

    fun completeNewChat(chatId: String): Boolean {
        val request = synchronized(newChatLock) {
            pendingNewChat.also { pendingNewChat = null }
        } ?: return false
        request.complete(chatId)
        return true
    }

    fun removeNewChat(expected: CompletableDeferred<String>): Boolean {
        synchronized(newChatLock) {
            if (pendingNewChat !== expected) return false
            pendingNewChat = null
            return true
        }
    }

    fun registerMessage(key: String, pending: PendingTransportMessage) {
        check(messages.putIfAbsent(key, pending) == null) { "message_pending" }
    }

    fun markMessageSent(key: String) {
        messages[key]?.sent = true
    }

    fun takeMessage(key: String): PendingTransportMessage? = messages.remove(key)

    fun removeMessage(key: String, expected: PendingTransportMessage): Boolean =
        messages.remove(key, expected)

    fun takeUnambiguousMessage(
        chatId: String,
        startsNewRun: Boolean?,
    ): Pair<String, PendingTransportMessage>? {
        val candidates = messages.entries.filter { entry ->
            entry.value.chatId == chatId &&
                (startsNewRun == null || entry.value.startsNewRun == startsNewRun)
        }
        if (candidates.size != 1) return null
        val candidate = candidates.single()
        return if (messages.remove(candidate.key, candidate.value)) {
            candidate.key to candidate.value
        } else {
            null
        }
    }

    fun registerTranscription(requestId: String, pending: CompletableDeferred<String>) {
        check(transcriptions.putIfAbsent(requestId, pending) == null) { "transcription_pending" }
    }

    fun takeTranscription(requestId: String): CompletableDeferred<String>? = transcriptions.remove(requestId)

    fun removeTranscription(requestId: String, expected: CompletableDeferred<String>): Boolean =
        transcriptions.remove(requestId, expected)

    fun rejectTranscriptions(error: Throwable, requestId: String?) {
        if (requestId != null) {
            takeTranscription(requestId)?.completeExceptionally(error)
            return
        }
        // 旧协议不携带 request_id。逐项 compare-remove，避免清理时误伤刚刚登记的新请求。
        transcriptions.entries.forEach { entry ->
            if (transcriptions.remove(entry.key, entry.value)) {
                entry.value.completeExceptionally(error)
            }
        }
    }

    fun registerSystemCommand(turnId: String, pending: CompletableDeferred<Unit>) {
        check(systemCommands.putIfAbsent(turnId, pending) == null) { "system_command_pending" }
    }

    fun takeSystemCommand(turnId: String): CompletableDeferred<Unit>? = systemCommands.remove(turnId)

    fun removeSystemCommand(turnId: String, expected: CompletableDeferred<Unit>): Boolean =
        systemCommands.remove(turnId, expected)

    /**
     * 断线快照先原子式领取当前请求，再由 Deferred 的幂等完成语义处理同时发生的响应。
     * 返回已经实际发出的消息，调用方据此上报 delivery unknown；未发出的消息只报告连接关闭。
     */
    fun rejectAll(messageTooBig: Boolean): PendingDisconnectResult {
        val queueIds = linkedSetOf<String>()
        val deliveryUnknown = mutableListOf<PendingTransportMessage>()

        val newChat = synchronized(newChatLock) {
            pendingNewChat.also { pendingNewChat = null }
        }
        if (newChat != null) {
            queueIds += NEW_CHAT_QUEUE_ID
            newChat.completeExceptionally(
                IllegalStateException(if (messageTooBig) "message_too_big" else "connection_closed"),
            )
        }

        messages.entries.forEach { entry ->
            if (!messages.remove(entry.key, entry.value)) return@forEach
            queueIds += "message:${entry.key}"
            val message = when {
                messageTooBig -> "message_too_big"
                entry.value.sent -> "socket_delivery_unknown"
                else -> "connection_closed"
            }
            entry.value.accepted.completeExceptionally(IllegalStateException(message))
            if (entry.value.sent) deliveryUnknown += entry.value
        }
        systemCommands.entries.forEach { entry ->
            if (!systemCommands.remove(entry.key, entry.value)) return@forEach
            queueIds += "system:${entry.key}"
            entry.value.completeExceptionally(IllegalStateException("connection_closed"))
        }
        transcriptions.entries.forEach { entry ->
            if (!transcriptions.remove(entry.key, entry.value)) return@forEach
            queueIds += "transcription:${entry.key}"
            entry.value.completeExceptionally(IllegalStateException("connection_closed"))
        }
        return PendingDisconnectResult(queueIds, deliveryUnknown)
    }

    private companion object {
        const val NEW_CHAT_QUEUE_ID = "new-chat"
    }
}

internal data class PendingTransportMessage(
    val chatId: String,
    val turnId: String,
    val accepted: CompletableDeferred<Unit>,
    val startsNewRun: Boolean,
    @Volatile var sent: Boolean = false,
)

internal data class PendingDisconnectResult(
    val queueIds: Set<String>,
    val deliveryUnknown: List<PendingTransportMessage>,
)
