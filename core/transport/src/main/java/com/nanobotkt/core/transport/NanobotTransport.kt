package com.nanobotkt.core.transport

import com.nanobotkt.core.model.InboundEvent
import com.nanobotkt.core.model.OutboundFrame
import com.nanobotkt.core.model.OutboundMedia
import com.nanobotkt.core.model.UiCliAppAttachment
import com.nanobotkt.core.model.UiMcpPresetAttachment
import com.nanobotkt.core.model.WorkspaceScope
import kotlinx.coroutines.CompletableDeferred
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
            is InboundEvent.Ready -> resolveNewChat(event.chatId)
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
            is InboundEvent.GoalStatus -> { if (event.status == "running") acceptFirstForChat(event.chatId, startsNewRun = true); mutableEvents.tryEmit(event) }
            else -> mutableEvents.tryEmit(event)
        }
    }

    private fun resolveNewChat(chatId: String) { synchronized(this) { knownChats += chatId; pendingNewChat?.complete(chatId); pendingNewChat = null; removeQueued("new-chat") } }
    private fun acceptMessage(chatId: String, turnId: String) { pendingMessages.remove(messageKey(chatId, turnId))?.accepted?.complete(Unit); removeQueued("message:" + messageKey(chatId, turnId)) }
    private fun acceptFirstForChat(chatId: String, startsNewRun: Boolean? = null) { pendingMessages.entries.firstOrNull { it.value.chatId == chatId && (startsNewRun == null || it.value.startsNewRun == startsNewRun) }?.let { pendingMessages.remove(it.key)?.accepted?.complete(Unit); removeQueued("message:" + it.key) } }

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
            delay(delayMs)
            val url = credentials.reauthenticateWebSocketUrl() ?: credentials.currentWebSocketUrl()
            synchronized(this@NanobotTransport) { reconnectJob = null; if (url != null && socket == null && !intentionallyClosed && networkAvailable) open(url, reconnecting = true) }
        }
    }

    private fun rejectPendingOnDisconnect(messageTooBig: Boolean) {
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


