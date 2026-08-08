package com.nanobotkt.core.transport

import com.nanobotkt.core.model.InboundEvent
import com.nanobotkt.core.model.OutboundFrame
import com.nanobotkt.core.model.OutboundMedia
import com.nanobotkt.core.model.UiCliAppAttachment
import com.nanobotkt.core.model.UiMcpPresetAttachment
import com.nanobotkt.core.model.WorkspaceScope
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.serialization.json.Json
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.Response
import okhttp3.WebSocket
import okhttp3.WebSocketListener
import okio.ByteString
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap
import javax.inject.Inject
import javax.inject.Singleton
import kotlin.math.min

interface TransportCredentials {
    fun currentWebSocketUrl(): String?
    suspend fun reauthenticateWebSocketUrl(): String?
    fun maxFrameBytes(): Int?
}

enum class TransportStatus { IDLE, CONNECTING, OPEN, RECONNECTING, CLOSED, ERROR }
data class TransportState(val status: TransportStatus = TransportStatus.IDLE, val networkAvailable: Boolean = true, val lastOpenedAt: Long? = null, val lastActivityAt: Long? = null, val needsCanonicalRefresh: Boolean = false, val error: String? = null)
sealed interface TransportError { data class MessageTooBig(val chatId: String? = null, val turnId: String? = null) : TransportError; data class DeliveryUnknown(val chatId: String, val turnId: String) : TransportError; data class TurnRejected(val chatId: String, val turnId: String, val detail: String?, val reason: String?) : TransportError; data class WorkspaceScopeRejected(val chatId: String?, val turnId: String?, val reason: String?) : TransportError }
data class MessageSendResult(val turnId: String, val accepted: CompletableDeferred<Unit>)

@Singleton
class NanobotTransport @Inject constructor(
    @param:com.nanobotkt.core.network.WebSocketClient private val client: OkHttpClient,
    private val json: Json,
    private val credentials: TransportCredentials,
) {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val mutableState = MutableStateFlow(TransportState())
    private val mutableEvents = MutableSharedFlow<InboundEvent>(extraBufferCapacity = 128)
    private val mutableErrors = MutableSharedFlow<TransportError>(extraBufferCapacity = 32)
    private val knownChats = linkedSetOf<String>()
    private val outbound = ArrayDeque<QueuedFrame>()
    private val pendingMessages = ConcurrentHashMap<String, PendingMessage>()
    private val pendingTranscriptions = ConcurrentHashMap<String, CompletableDeferred<String>>()
    private val pendingSystemCommands = ConcurrentHashMap<String, CompletableDeferred<Unit>>()
    private var pendingNewChat: CompletableDeferred<String>? = null
    private var socket: WebSocket? = null
    private var reconnectAttempt = 0
    private var reconnectJob: Job? = null
    private var intentionallyClosed = false
    private var networkAvailable = true
    private var backgroundAt: Long? = null
    private var backgroundCloseJob: Job? = null

    val state: StateFlow<TransportState> = mutableState.asStateFlow()
    val events: SharedFlow<InboundEvent> = mutableEvents.asSharedFlow()
    val errors: SharedFlow<TransportError> = mutableErrors.asSharedFlow()

    @Synchronized fun connect() {
        if (intentionallyClosed || !networkAvailable || socket != null) return
        val url = credentials.currentWebSocketUrl() ?: return
        open(url, reconnecting = reconnectAttempt > 0)
    }

    @Synchronized fun close() {
        intentionallyClosed = true
        reconnectJob?.cancel()
        reconnectJob = null
        val active = socket
        socket = null
        rejectPendingOnDisconnect(messageTooBig = false)
        outbound.clear()
        active?.close(1000, "client_closed")
        mutableState.value = mutableState.value.copy(status = TransportStatus.CLOSED)
    }

    /**
     * 清除当前认证会话登记的聊天连接。
     *
     * `knownChats` 不能在普通断线或后台切换时清空，因为这些场景需要在同一
     * 认证会话恢复 WebSocket 后重新发送 Attach。Logout 则不同：认证主体已经
     * 发生变化，旧账号的 chat id 不得被新账号的连接再次 attach，因此由
     * AppViewModel 在注销前显式清理这份会话级登记。
     */
    @Synchronized fun clearAttachments() {
        knownChats.clear()
    }

    @Synchronized fun onBackground() {
        backgroundAt = System.currentTimeMillis()
        backgroundCloseJob?.cancel()
        backgroundCloseJob = scope.launch {
            delay(10_000L)
            synchronized(this@NanobotTransport) {
                if (backgroundAt == null) return@synchronized
                val active = socket
                socket = null
                rejectPendingOnDisconnect(messageTooBig = false)
                active?.cancel()
                mutableState.value = mutableState.value.copy(
                    status = TransportStatus.CLOSED,
                    needsCanonicalRefresh = true,
                )
            }
        }
    }

    @Synchronized fun resume() {
        val awayFor = backgroundAt?.let { System.currentTimeMillis() - it } ?: 0L
        backgroundAt = null
        backgroundCloseJob?.cancel()
        backgroundCloseJob = null
        intentionallyClosed = false
        val stale = mutableState.value.lastActivityAt?.let { System.currentTimeMillis() - it > 45_000L } ?: false
        if ((awayFor > 10_000L || stale) && socket != null) reconnect("resume_stale") else connect()
    }

    @Synchronized fun setNetworkAvailable(available: Boolean) {
        if (networkAvailable == available) return
        networkAvailable = available
        mutableState.value = mutableState.value.copy(networkAvailable = available)
        if (!available) {
            reconnectJob?.cancel()
            reconnectJob = null
            val active = socket
            socket = null
            rejectPendingOnDisconnect(messageTooBig = false)
            active?.cancel()
            mutableState.value = mutableState.value.copy(status = TransportStatus.CLOSED, needsCanonicalRefresh = true)
        } else {
            intentionallyClosed = false
            reconnectAttempt = 0
            connect()
        }
    }

    fun attach(chatId: String) {
        if (chatId.isBlank()) return
        synchronized(this) { knownChats += chatId }
        sendIfOpen(OutboundFrame.Attach(chatId))
    }

    suspend fun newChat(scope: WorkspaceScope? = null, timeoutMs: Long = 20_000): String {
        check(networkAvailable) { "network_unavailable" }
        val request = CompletableDeferred<String>()
        synchronized(this) {
            check(pendingNewChat == null) { "new_chat_pending" }
            pendingNewChat = request
            enqueue("new-chat", OutboundFrame.NewChat(scope))
        }
        return awaitWithTimeout(request, timeoutMs, "new chat timeout") { synchronized(this) { if (pendingNewChat === request) pendingNewChat = null; removeQueued("new-chat") } }
    }

    suspend fun forkChat(
        sourceChatId: String,
        beforeUserIndex: Int,
        title: String? = null,
        timeoutMs: Long = 5_000,
    ): String {
        check(networkAvailable) { "network_unavailable" }
        require(sourceChatId.isNotBlank() && beforeUserIndex >= 0) { "invalid_fork_position" }
        val request = CompletableDeferred<String>()
        synchronized(this) {
            check(pendingNewChat == null) { "new_chat_pending" }
            pendingNewChat = request
            enqueue(
                "new-chat",
                OutboundFrame.ForkChat(
                    sourceChatId = sourceChatId,
                    beforeUserIndex = beforeUserIndex,
                    title = title?.trim()?.takeIf(String::isNotEmpty),
                ),
            )
        }
        return awaitWithTimeout(request, timeoutMs, "fork timeout") {
            synchronized(this) {
                if (pendingNewChat === request) pendingNewChat = null
                removeQueued("new-chat")
            }
        }
    }

    fun sendMessage(
        chatId: String,
        content: String,
        media: List<OutboundMedia> = emptyList(),
        cliApps: List<UiCliAppAttachment> = emptyList(),
        mcpPresets: List<UiMcpPresetAttachment> = emptyList(),
        quotedContext: String? = null,
        workspaceScope: WorkspaceScope? = null,
        startsNewRun: Boolean = true,
        acceptanceTimeoutMs: Long = 20_000,
    ): MessageSendResult {
        val turnId = UUID.randomUUID().toString()
        val frame = OutboundFrame.Message(chatId, content, media.takeIf(List<*>::isNotEmpty), cliApps.takeIf(List<*>::isNotEmpty), mcpPresets.takeIf(List<*>::isNotEmpty), quotedContext, workspaceScope, turnId, webui = true)
        val encodedBytes = json.encodeToString(OutboundFrame.serializer(), frame).toByteArray().size
        val maxBytes = credentials.maxFrameBytes()
        val accepted = CompletableDeferred<Unit>()
        if (maxBytes != null && encodedBytes > maxBytes) {
            accepted.completeExceptionally(IllegalArgumentException("message_too_big"))
            mutableErrors.tryEmit(TransportError.MessageTooBig(chatId, turnId))
            return MessageSendResult(turnId, accepted)
        }
        val key = messageKey(chatId, turnId)
        pendingMessages[key] = PendingMessage(chatId, turnId, accepted, startsNewRun)
        synchronized(this) { knownChats += chatId; enqueue("message:$key", frame) }
        scope.launch {
            delay(acceptanceTimeoutMs)
            val pending = pendingMessages.remove(key) ?: return@launch
            removeQueued("message:$key")
            pending.accepted.completeExceptionally(IllegalStateException("message_accept_timeout"))
        }
        return MessageSendResult(turnId, accepted)
    }

    suspend fun sendSystemCommand(chatId: String, command: String, timeoutMs: Long = 5_000) {
        check(networkAvailable) { "network_unavailable" }
        val turnId = SYSTEM_TURN_PREFIX + UUID.randomUUID()
        val pending = CompletableDeferred<Unit>()
        pendingSystemCommands[turnId] = pending
        synchronized(this) { enqueue("system:$turnId", OutboundFrame.Message(chatId, command.trim(), turnId = turnId, webui = true)) }
        awaitWithTimeout(pending, timeoutMs, "system command timeout") { pendingSystemCommands.remove(turnId); removeQueued("system:$turnId") }
    }

    fun stopTurn(chatId: String) { scope.launch { runCatching { sendSystemCommand(chatId, "/stop", 5_000) } } }

    suspend fun transcribeAudio(dataUrl: String, durationMs: Long? = null, timeoutMs: Long = 30_000): String {
        check(networkAvailable) { "network_unavailable" }
        val requestId = UUID.randomUUID().toString()
        val pending = CompletableDeferred<String>()
        pendingTranscriptions[requestId] = pending
        synchronized(this) { enqueue("transcription:$requestId", OutboundFrame.TranscribeAudio(requestId, dataUrl, durationMs)) }
        return awaitWithTimeout(pending, timeoutMs, "transcription_timeout") { pendingTranscriptions.remove(requestId); removeQueued("transcription:$requestId") }
    }

    fun setWorkspaceScope(chatId: String, scope: WorkspaceScope) { synchronized(this) { knownChats += chatId; enqueue("workspace:" + UUID.randomUUID(), OutboundFrame.SetWorkspaceScope(chatId, scope)) } }
    fun clearCanonicalRefreshFlag() { mutableState.value = mutableState.value.copy(needsCanonicalRefresh = false) }

    @Synchronized private fun open(url: String, reconnecting: Boolean) {
        mutableState.value = mutableState.value.copy(status = if (reconnecting) TransportStatus.RECONNECTING else TransportStatus.CONNECTING, error = null)
        socket = client.newWebSocket(Request.Builder().url(url).build(), Listener())
    }

    @Synchronized private fun enqueue(id: String, frame: OutboundFrame) {
        removeQueued(id)
        if (!sendIfOpen(frame, pendingQueueId = id)) outbound.addLast(QueuedFrame(id, frame))
    }

    @Synchronized private fun removeQueued(id: String) { outbound.removeAll { it.id == id } }

    @Synchronized private fun flushQueue() {
        while (outbound.isNotEmpty()) {
            val next = outbound.first()
            if (!sendIfOpen(next.frame, next.id)) return
            outbound.removeFirst()
        }
    }

    @Synchronized private fun sendIfOpen(frame: OutboundFrame, pendingQueueId: String? = null): Boolean {
        val active = socket ?: return false
        val encoded = json.encodeToString(OutboundFrame.serializer(), frame)
        val sent = active.send(encoded)
        if (sent && frame is OutboundFrame.Message && !frame.turnId.startsWith(SYSTEM_TURN_PREFIX)) pendingMessages[messageKey(frame.chatId, frame.turnId)]?.sent = true
        if (sent && pendingQueueId != null) removeQueued(pendingQueueId)
        return sent
    }

    private fun route(event: InboundEvent) {
        val now = System.currentTimeMillis()
        mutableState.value = mutableState.value.copy(lastActivityAt = now)

        val systemTurnId = event.turnIdOrNull()
            ?.takeIf { it.startsWith(SYSTEM_TURN_PREFIX) }
        if (systemTurnId != null) {
            when (event) {
                is InboundEvent.Error -> pendingSystemCommands.remove(systemTurnId)
                    ?.completeExceptionally(
                        IllegalStateException(
                            listOfNotNull(event.detail, event.reason)
                                .joinToString(": ")
                                .ifBlank { "system_command_failed" },
                        ),
                    )
                is InboundEvent.Message,
                is InboundEvent.TurnEnd,
                -> pendingSystemCommands.remove(systemTurnId)?.complete(Unit)
                else -> Unit
            }
            return
        }

        when (event) {
            is InboundEvent.TranscriptionResult -> pendingTranscriptions.remove(event.requestId)?.complete(event.text)
            is InboundEvent.TranscriptionError -> {
                val error = IllegalStateException(event.detail ?: "transcription_failed")
                if (event.requestId == null) pendingTranscriptions.values.forEach { it.completeExceptionally(error) } else pendingTranscriptions.remove(event.requestId)?.completeExceptionally(error)
            }
            is InboundEvent.MessageAccepted -> acceptMessage(event.chatId, event.turnId)
            // `ready` 表示 WebSocket 握手完成，并携带服务端分配的默认会话；
            // 它不是 `new_chat`/`fork_chat` 的响应。只有 `attached` 才能完成
            // 当前等待中的新会话请求，避免握手期间误返回默认 chat id。
            is InboundEvent.Ready -> mutableEvents.tryEmit(event)
            is InboundEvent.Attached -> resolveNewChat(event.chatId)
            is InboundEvent.Error -> {
                val chatId = event.chatId
                val turnId = event.turnId
                if (chatId != null && turnId != null) {
                    pendingMessages.remove(messageKey(chatId, turnId))?.accepted?.completeExceptionally(IllegalStateException(event.detail ?: event.reason ?: "turn_rejected"))
                    if (event.detail == "workspace_scope_rejected") mutableErrors.tryEmit(TransportError.WorkspaceScopeRejected(chatId, turnId, event.reason))
                    else mutableErrors.tryEmit(TransportError.TurnRejected(chatId, turnId, event.detail, event.reason))
                }
                mutableEvents.tryEmit(event)
            }
            is InboundEvent.Message -> {
                event.turnId?.let { acceptMessage(event.chatId, it) }
                mutableEvents.tryEmit(event)
            }
            is InboundEvent.TurnEnd -> {
                event.turnId?.let { acceptMessage(event.chatId, it) }
                mutableEvents.tryEmit(event)
            }
            is InboundEvent.Delta -> { event.turnId?.let { acceptMessage(event.chatId, it) }; mutableEvents.tryEmit(event) }
            is InboundEvent.ReasoningDelta -> { event.turnId?.let { acceptMessage(event.chatId, it) }; mutableEvents.tryEmit(event) }
            is InboundEvent.StreamEnd -> { event.turnId?.let { acceptMessage(event.chatId, it) }; mutableEvents.tryEmit(event) }
            is InboundEvent.GoalStatus -> {
                if (event.status == "running") {
                    // 新协议带有 turn_id 时必须精确匹配；旧服务端未提供 turn_id 时，
                    // 只有该会话恰好存在一个待确认的新回合，才允许安全回退。
                    val turnId = event.turnId
                    if (turnId != null) {
                        acceptMessage(event.chatId, turnId)
                    } else {
                        acceptUnambiguousForChat(event.chatId, startsNewRun = true)
                    }
                }
                mutableEvents.tryEmit(event)
            }
            else -> mutableEvents.tryEmit(event)
        }
    }

    private fun resolveNewChat(chatId: String) {
        synchronized(this) {
            // 普通 attach 的回执也使用 attached 事件。若当前正在等待 new_chat/fork_chat，
            // 已知会话的回执只能属于普通 attach，不能误完成“创建新会话”的 deferred；
            // 否则真正的新会话回执到达时，调用方已经停止等待并丢失新 chat id。
            if (pendingNewChat != null && chatId in knownChats) return
            knownChats += chatId
            pendingNewChat?.complete(chatId)
            pendingNewChat = null
            removeQueued("new-chat")
        }
    }
    private fun acceptMessage(chatId: String, turnId: String) { pendingMessages.remove(messageKey(chatId, turnId))?.accepted?.complete(Unit); removeQueued("message:" + messageKey(chatId, turnId)) }
    private fun acceptUnambiguousForChat(chatId: String, startsNewRun: Boolean? = null) {
        val candidates = pendingMessages.entries.filter {
            it.value.chatId == chatId && (startsNewRun == null || it.value.startsNewRun == startsNewRun)
        }
        // 没有 turn_id 且同时存在多个候选时，宁可等待带 turn_id 的事件，
        // 也不能把服务端状态错误归属给另一条消息。
        if (candidates.size == 1) {
            candidates.single().let {
                pendingMessages.remove(it.key)?.accepted?.complete(Unit)
                removeQueued("message:" + it.key)
            }
        }
    }

    @Synchronized private fun reconnect(reason: String) {
        val active = socket
        socket = null
        rejectPendingOnDisconnect(messageTooBig = false)
        active?.cancel()
        mutableState.value = mutableState.value.copy(status = TransportStatus.RECONNECTING, needsCanonicalRefresh = true, error = reason)
        scheduleReconnect()
    }

    @Synchronized private fun scheduleReconnect() {
        if (reconnectJob != null || intentionallyClosed || !networkAvailable) return
        val delayMs = min(30_000L, 1_000L shl min(reconnectAttempt, 5))
        reconnectAttempt += 1
        reconnectJob = scope.launch {
            var retryAfterFailure = false
            try {
                delay(delayMs)
                val url = try {
                    // 重连必须重新获取认证后的 WebSocket 地址，不能在刷新失败时
                    // 静默回退到可能已经过期的旧地址。
                    credentials.reauthenticateWebSocketUrl()
                } catch (error: CancellationException) {
                    throw error
                } catch (error: Exception) {
                    retryAfterFailure = true
                    synchronized(this@NanobotTransport) {
                        mutableState.value = mutableState.value.copy(
                            status = TransportStatus.RECONNECTING,
                            error = error.message ?: "reauthentication_failed",
                        )
                    }
                    null
                }
                synchronized(this@NanobotTransport) {
                    if (url != null && socket == null && !intentionallyClosed && networkAvailable) {
                        open(url, reconnecting = true)
                    } else if (url == null && !intentionallyClosed && networkAvailable) {
                        retryAfterFailure = true
                    }
                }
            } finally {
                // 先释放当前 Job 引用，再安排下一次退避重试；否则在 finally 内调用
                // scheduleReconnect() 会被自身的非空引用拦截，认证失败后就不再恢复。
                synchronized(this@NanobotTransport) {
                    reconnectJob = null
                    if (retryAfterFailure && !intentionallyClosed && networkAvailable && socket == null) {
                        scheduleReconnect()
                    }
                }
            }
        }
    }

    private fun rejectPendingOnDisconnect(messageTooBig: Boolean) {
        // 断线时 pending deferred 会立即向调用方报告失败；对应的非幂等 frame
        // 不能留在 outbound 队列，否则重连 flushQueue() 会把“已经失败且无人再等待”
        // 的 new_chat/message/system/transcription 重新发送到服务端，造成无主副作用。
        val queuedPendingIds = buildSet {
            if (pendingNewChat != null) add("new-chat")
            pendingMessages.keys.forEach { add("message:$it") }
            pendingSystemCommands.keys.forEach { add("system:$it") }
            pendingTranscriptions.keys.forEach { add("transcription:$it") }
        }
        outbound.removeAll { it.id in queuedPendingIds }

        pendingNewChat?.completeExceptionally(IllegalStateException(if (messageTooBig) "message_too_big" else "connection_closed")); pendingNewChat = null
        pendingMessages.values.forEach { pending ->
            val message = if (messageTooBig) "message_too_big" else if (pending.sent) "socket_delivery_unknown" else "connection_closed"
            pending.accepted.completeExceptionally(IllegalStateException(message))
            if (pending.sent) mutableErrors.tryEmit(TransportError.DeliveryUnknown(pending.chatId, pending.turnId))
        }
        pendingMessages.clear()
        pendingSystemCommands.values.forEach { it.completeExceptionally(IllegalStateException("connection_closed")) }; pendingSystemCommands.clear()
        pendingTranscriptions.values.forEach { it.completeExceptionally(IllegalStateException("connection_closed")) }; pendingTranscriptions.clear()
        if (messageTooBig) mutableErrors.tryEmit(TransportError.MessageTooBig())
    }

    private inner class Listener : WebSocketListener() {
        override fun onOpen(webSocket: WebSocket, response: Response) {
            synchronized(this@NanobotTransport) {
                if (socket !== webSocket) return
                reconnectAttempt = 0
                val now = System.currentTimeMillis()
                mutableState.value = mutableState.value.copy(status = TransportStatus.OPEN, lastOpenedAt = now, lastActivityAt = now, error = null)
                knownChats.forEach { sendIfOpen(OutboundFrame.Attach(it)) }
                flushQueue()
            }
        }
        override fun onMessage(webSocket: WebSocket, text: String) {
            if (socket !== webSocket) return
            runCatching { json.decodeFromString(InboundEvent.serializer(), text) }.getOrNull()?.let(::route)
        }
        override fun onMessage(webSocket: WebSocket, bytes: ByteString) = onMessage(webSocket, bytes.utf8())
        override fun onClosing(webSocket: WebSocket, code: Int, reason: String) { webSocket.close(code, reason) }
        override fun onClosed(webSocket: WebSocket, code: Int, reason: String) = handleClosed(webSocket, code, null)
        override fun onFailure(webSocket: WebSocket, t: Throwable, response: Response?) = handleClosed(webSocket, response?.code ?: 0, t)
    }

    @Synchronized private fun handleClosed(closedSocket: WebSocket, code: Int, cause: Throwable?) {
        if (socket !== closedSocket) return
        socket = null
        val tooBig = code == 1009
        rejectPendingOnDisconnect(tooBig)
        mutableState.value = mutableState.value.copy(status = if (intentionallyClosed || !networkAvailable) TransportStatus.CLOSED else TransportStatus.RECONNECTING, needsCanonicalRefresh = true, error = if (tooBig) "message_too_big" else cause?.message)
        if (!intentionallyClosed && networkAvailable) scheduleReconnect()
    }

    private data class QueuedFrame(val id: String, val frame: OutboundFrame)
    private data class PendingMessage(val chatId: String, val turnId: String, val accepted: CompletableDeferred<Unit>, val startsNewRun: Boolean, @Volatile var sent: Boolean = false)
    private fun messageKey(chatId: String, turnId: String) = "$chatId::$turnId"
}

private const val SYSTEM_TURN_PREFIX = "webui-system:"

private fun InboundEvent.turnIdOrNull(): String? = when (this) {
    is InboundEvent.MessageAccepted -> turnId
    is InboundEvent.Message -> turnId
    is InboundEvent.FileEdit -> turnId
    is InboundEvent.Delta -> turnId
    is InboundEvent.ReasoningDelta -> turnId
    is InboundEvent.ReasoningEnd -> turnId
    is InboundEvent.StreamEnd -> turnId
    is InboundEvent.TurnEnd -> turnId
    is InboundEvent.GoalStatus -> turnId
    is InboundEvent.Error -> turnId
    else -> null
}

private suspend fun <T> awaitWithTimeout(deferred: CompletableDeferred<T>, timeoutMs: Long, message: String, cleanup: () -> Unit): T {
    val timeout = CoroutineScope(Dispatchers.Default).launch { delay(timeoutMs); if (deferred.completeExceptionally(IllegalStateException(message))) cleanup() }
    return try { deferred.await() } finally { timeout.cancel(); cleanup() }
}


