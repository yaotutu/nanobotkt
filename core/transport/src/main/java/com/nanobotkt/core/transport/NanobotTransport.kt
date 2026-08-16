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
import kotlinx.coroutines.TimeoutCancellationException
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeout
import kotlinx.serialization.json.Json
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.Response
import okhttp3.WebSocket
import okhttp3.WebSocketListener
import okio.ByteString
import java.util.UUID
import javax.inject.Inject
import javax.inject.Singleton
import kotlin.math.min

/**
 * 新建 WebSocket 所需的一次性凭据能力。
 *
 * 服务端会在握手成功时消费 Bootstrap 下发的 WebSocket Token，因此通信层每次真正创建
 * Socket 都必须调用 [freshWebSocketUrl] 领取一个尚未使用的 URL，不能保存并复用旧值。
 */
interface WebSocketCredentialProvider {
    suspend fun freshWebSocketUrl(): String?
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
    private val credentials: WebSocketCredentialProvider,
) {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val mutableState = MutableStateFlow(TransportState())
    private val mutableEvents = MutableSharedFlow<InboundEvent>(extraBufferCapacity = 128)
    private val mutableErrors = MutableSharedFlow<TransportError>(extraBufferCapacity = 32)
    private val knownChats = linkedSetOf<String>()
    private val outbound = ArrayDeque<QueuedFrame>()
    /** pending 请求统一由关联表管理，避免协议路由、超时和断线各自维护一份清理规则。 */
    private val requests = TransportRequestRegistry()
    private var socket: WebSocket? = null
    private var reconnectAttempt = 0
    private var reconnectJob: Job? = null
    private var credentialJob: Job? = null
    private var connectionGeneration = 0L

    /**
     * Transport 是否属于一个已经建立的登录会话。
     *
     * 该状态只由 [connect]（认证成功）和 [close]（退出/认证失效）改变；前后台切换与
     * 网络恢复只能暂停或恢复已有会话，绝不能隐式把已关闭的认证会话重新激活。
     */
    private var sessionActive = false
    private var networkAvailable = true
    private var backgroundAt: Long? = null
    private var backgroundCloseJob: Job? = null

    val state: StateFlow<TransportState> = mutableState.asStateFlow()
    val events: SharedFlow<InboundEvent> = mutableEvents.asSharedFlow()
    val errors: SharedFlow<TransportError> = mutableErrors.asSharedFlow()

    /** 认证成功后激活实时通信；重复调用只会确保当前会话已连接，不会创建并行 Socket。 */
    @Synchronized fun connect() {
        sessionActive = true
        connectIfEligible()
    }

    /**
     * 用户显式要求重连当前认证会话。
     *
     * 与 [connect] 不同，该入口不会激活一个已关闭的认证会话；它只会替换当前会话已有的
     * Socket/连接任务，并重新领取一次性 WS Token。这样 Settings 的 Reconnect 不会在登录页
     * 制造后台重连，同时在连接仍显示 OPEN 时也确实执行一次新的握手。
     */
    @Synchronized fun reconnect() {
        if (!sessionActive || backgroundAt != null || !networkAvailable) return
        restartConnection("manual_reconnect")
    }

    /**
     * 根据认证会话、前后台和网络三个正交条件决定是否真正领取一次性凭据。
     *
     * 这里是所有初次连接入口的唯一闸门。尤其不能在登录页、后台或离线状态下启动
     * Bootstrap，否则生命周期事件会反向制造无凭据重连和一次性 Token 浪费。
     */
    @Synchronized private fun connectIfEligible() {
        if (!sessionActive || backgroundAt != null || !networkAvailable || socket != null || credentialJob != null || reconnectJob != null) return
        val expectedGeneration = connectionGeneration
        val reconnecting = reconnectAttempt > 0
        mutableState.value = mutableState.value.copy(
            status = if (reconnecting) TransportStatus.RECONNECTING else TransportStatus.CONNECTING,
            error = null,
        )
        credentialJob = scope.launch {
            var failure: Throwable? = null
            val url = try {
                // 初次连接和前台恢复同样必须领取一次性 Token；凭据获取是 suspend 操作，
                // 放在 Transport scope 中执行，避免在 Activity/ViewModel 或 OkHttp 线程中阻塞。
                credentials.freshWebSocketUrl()
            } catch (error: CancellationException) {
                throw error
            } catch (error: Exception) {
                failure = error
                null
            }
            synchronized(this@NanobotTransport) {
                if (connectionGeneration != expectedGeneration) return@synchronized
                credentialJob = null
                if (url != null && socket == null && sessionActive && backgroundAt == null && networkAvailable) {
                    open(url, reconnecting = reconnecting)
                } else if (sessionActive && backgroundAt == null && networkAvailable && socket == null) {
                    mutableState.value = mutableState.value.copy(
                        status = TransportStatus.RECONNECTING,
                        error = failure?.message ?: "websocket_credentials_unavailable",
                    )
                    scheduleReconnect()
                }
            }
        }
    }

    @Synchronized fun close() {
        sessionActive = false
        connectionGeneration += 1L
        credentialJob?.cancel()
        credentialJob = null
        reconnectJob?.cancel()
        reconnectJob = null
        backgroundCloseJob?.cancel()
        backgroundCloseJob = null
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

        // 进入后台后保留已经打开的 Socket 最多 10 秒，给短暂系统弹窗或 Activity 切换留出
        // grace；但立即取消尚未完成的凭据领取和退避重连。后台不能新发 Bootstrap，也不能
        // 消费一个尚未用于握手的一次性 WebSocket Token。
        connectionGeneration += 1L
        credentialJob?.cancel()
        credentialJob = null
        reconnectJob?.cancel()
        reconnectJob = null
        if (socket == null) {
            mutableState.value = mutableState.value.copy(status = TransportStatus.CLOSED)
        }

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

        // 前台恢复只恢复“已经激活的认证会话”。如果 AuthState 仍在登录页或已经 logout，
        // 此处必须保持关闭，等待组合根在 Ready 后显式调用 connect()。
        if (!sessionActive) return
        val stale = mutableState.value.lastActivityAt?.let { System.currentTimeMillis() - it > 45_000L } ?: false
        if ((awayFor > 10_000L || stale) && socket != null) restartConnection("resume_stale") else connectIfEligible()
    }

    @Synchronized fun setNetworkAvailable(available: Boolean) {
        if (networkAvailable == available) return
        networkAvailable = available
        mutableState.value = mutableState.value.copy(networkAvailable = available)
        if (!available) {
            connectionGeneration += 1L
            credentialJob?.cancel()
            credentialJob = null
            reconnectJob?.cancel()
            reconnectJob = null
            val active = socket
            socket = null
            rejectPendingOnDisconnect(messageTooBig = false)
            active?.cancel()
            mutableState.value = mutableState.value.copy(status = TransportStatus.CLOSED, needsCanonicalRefresh = true)
        } else {
            reconnectAttempt = 0
            connectIfEligible()
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
        requests.registerNewChat(request)
        synchronized(this) { enqueue("new-chat", OutboundFrame.NewChat(scope)) }
        return awaitWithTimeout(request, timeoutMs, "new chat timeout") {
            if (requests.removeNewChat(request)) removeQueued("new-chat")
        }
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
        requests.registerNewChat(request)
        synchronized(this) {
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
            if (requests.removeNewChat(request)) removeQueued("new-chat")
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
        val pending = PendingTransportMessage(chatId, turnId, accepted, startsNewRun)
        requests.registerMessage(key, pending)
        synchronized(this) { knownChats += chatId; enqueue("message:$key", frame) }
        scope.launch {
            try {
                // 等待 acceptance 本身，成功后计时协程立即结束；不再让每条消息都保留一个完整时长的 delay Job。
                withTimeout(acceptanceTimeoutMs) { accepted.await() }
            } catch (_: TimeoutCancellationException) {
                if (requests.removeMessage(key, pending)) {
                    removeQueued("message:$key")
                    accepted.completeExceptionally(IllegalStateException("message_accept_timeout"))
                }
            } catch (_: CancellationException) {
                // Transport scope 停止时由连接关闭路径统一拒绝 pending，避免重复改写错误原因。
            } catch (_: Throwable) {
                // 该协程只负责 acceptance 超时计时。服务端拒绝等业务异常由调用方持有的
                // accepted Deferred 负责传播；计时协程必须就地结束，不能把同一异常再次作为
                // 未捕获异常泄漏到 Transport scope 或后续测试/生命周期。
            }
        }
        return MessageSendResult(turnId, accepted)
    }

    suspend fun sendSystemCommand(chatId: String, command: String, timeoutMs: Long = 5_000) {
        check(networkAvailable) { "network_unavailable" }
        val turnId = SYSTEM_TURN_PREFIX + UUID.randomUUID()
        val pending = CompletableDeferred<Unit>()
        requests.registerSystemCommand(turnId, pending)
        synchronized(this) { enqueue("system:$turnId", OutboundFrame.Message(chatId, command.trim(), turnId = turnId, webui = true)) }
        awaitWithTimeout(pending, timeoutMs, "system command timeout") {
            if (requests.removeSystemCommand(turnId, pending)) removeQueued("system:$turnId")
        }
    }

    fun stopTurn(chatId: String) { scope.launch { runCatching { sendSystemCommand(chatId, "/stop", 5_000) } } }

    suspend fun transcribeAudio(dataUrl: String, durationMs: Long? = null, timeoutMs: Long = 30_000): String {
        check(networkAvailable) { "network_unavailable" }
        val requestId = UUID.randomUUID().toString()
        val pending = CompletableDeferred<String>()
        requests.registerTranscription(requestId, pending)
        synchronized(this) { enqueue("transcription:$requestId", OutboundFrame.TranscribeAudio(requestId, dataUrl, durationMs)) }
        return awaitWithTimeout(pending, timeoutMs, "transcription_timeout") {
            if (requests.removeTranscription(requestId, pending)) removeQueued("transcription:$requestId")
        }
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
        if (sent && frame is OutboundFrame.Message && !frame.turnId.startsWith(SYSTEM_TURN_PREFIX)) requests.markMessageSent(messageKey(frame.chatId, frame.turnId))
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
                is InboundEvent.Error -> requests.takeSystemCommand(systemTurnId)
                    ?.completeExceptionally(
                        IllegalStateException(
                            listOfNotNull(event.detail, event.reason)
                                .joinToString(": ")
                                .ifBlank { "system_command_failed" },
                        ),
                    )
                is InboundEvent.Message,
                is InboundEvent.TurnEnd,
                -> requests.takeSystemCommand(systemTurnId)?.complete(Unit)
                else -> Unit
            }
            return
        }

        when (event) {
            is InboundEvent.TranscriptionResult -> requests.takeTranscription(event.requestId)?.complete(event.text)
            is InboundEvent.TranscriptionError -> {
                val error = IllegalStateException(event.detail ?: "transcription_failed")
                requests.rejectTranscriptions(error, event.requestId)
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
                    requests.takeMessage(messageKey(chatId, turnId))?.accepted?.completeExceptionally(IllegalStateException(event.detail ?: event.reason ?: "turn_rejected"))
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
            if (requests.hasPendingNewChat() && chatId in knownChats) return
            knownChats += chatId
            if (requests.completeNewChat(chatId)) removeQueued("new-chat")
        }
    }
    private fun acceptMessage(chatId: String, turnId: String) {
        val key = messageKey(chatId, turnId)
        requests.takeMessage(key)?.accepted?.complete(Unit) ?: return
        removeQueued("message:$key")
    }
    private fun acceptUnambiguousForChat(chatId: String, startsNewRun: Boolean? = null) {
        // 没有 turn_id 且同时存在多个候选时，宁可等待带 turn_id 的事件，
        // 也不能把服务端状态错误归属给另一条消息。
        requests.takeUnambiguousMessage(chatId, startsNewRun)?.let { (key, pending) ->
            pending.accepted.complete(Unit)
            removeQueued("message:$key")
        }
    }

    @Synchronized private fun restartConnection(reason: String) {
        connectionGeneration += 1L
        credentialJob?.cancel()
        credentialJob = null
        // 显式重连可能发生在退避或凭据领取阶段。必须同时取消并清空旧任务，否则
        // scheduleReconnect() 会被旧引用挡住，用户点击 Reconnect 后看似无任何动作。
        reconnectJob?.cancel()
        reconnectJob = null
        val active = socket
        socket = null
        rejectPendingOnDisconnect(messageTooBig = false)
        active?.cancel()
        mutableState.value = mutableState.value.copy(status = TransportStatus.RECONNECTING, needsCanonicalRefresh = true, error = reason)
        scheduleReconnect()
    }

    @Synchronized private fun scheduleReconnect() {
        if (reconnectJob != null || !sessionActive || backgroundAt != null || !networkAvailable) return
        val delayMs = min(30_000L, 1_000L shl min(reconnectAttempt, 5))
        reconnectAttempt += 1
        val expectedGeneration = connectionGeneration
        reconnectJob = scope.launch {
            var retryAfterFailure = false
            try {
                delay(delayMs)
                val url = try {
                    // 重连必须重新获取认证后的 WebSocket 地址，不能在刷新失败时
                    // 静默回退到可能已经过期的旧地址。
                    credentials.freshWebSocketUrl()
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
                    // close、切后台、网络断开或显式 reconnect 都会推进代数。凭据请求即使
                    // 无法及时响应协程取消，迟到的一次性 URL 也只能被丢弃，不能打开旧连接。
                    if (connectionGeneration != expectedGeneration) return@synchronized
                    if (url != null && socket == null && sessionActive && backgroundAt == null && networkAvailable) {
                        open(url, reconnecting = true)
                    } else if (url == null && sessionActive && backgroundAt == null && networkAvailable) {
                        retryAfterFailure = true
                    }
                }
            } finally {
                // 先释放当前 Job 引用，再安排下一次退避重试；否则在 finally 内调用
                // scheduleReconnect() 会被自身的非空引用拦截，认证失败后就不再恢复。
                synchronized(this@NanobotTransport) {
                    reconnectJob = null
                    if (
                        retryAfterFailure &&
                        connectionGeneration == expectedGeneration &&
                        sessionActive &&
                        backgroundAt == null &&
                        networkAvailable &&
                        socket == null
                    ) {
                        scheduleReconnect()
                    }
                }
            }
        }
    }

    private fun rejectPendingOnDisconnect(messageTooBig: Boolean) {
        // 关联表先领取并拒绝所有 pending，再用其快照清理对应的非幂等队列帧。
        // 这样断线与服务端响应竞态时，只有真正仍在 pending 的请求会被标记失败。
        val rejected = requests.rejectAll(messageTooBig)
        outbound.removeAll { queued -> queued.id in rejected.queueIds }
        rejected.deliveryUnknown.forEach { pending ->
            mutableErrors.tryEmit(TransportError.DeliveryUnknown(pending.chatId, pending.turnId))
        }
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
        val canReconnect = sessionActive && backgroundAt == null && networkAvailable
        mutableState.value = mutableState.value.copy(status = if (canReconnect) TransportStatus.RECONNECTING else TransportStatus.CLOSED, needsCanonicalRefresh = true, error = if (tooBig) "message_too_big" else cause?.message)
        if (canReconnect) scheduleReconnect()
    }

    private data class QueuedFrame(val id: String, val frame: OutboundFrame)
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

/**
 * 在调用方协程内等待请求，不再为每次调用创建脱离父 Job 的 CoroutineScope。
 * finally 对成功、协议失败、超时和调用方取消执行同一清理；cleanup 必须使用 compare-remove，
 * 因而迟到响应和并发断线不会删除后来登记的请求。
 */
private suspend fun <T> awaitWithTimeout(
    deferred: CompletableDeferred<T>,
    timeoutMs: Long,
    message: String,
    cleanup: () -> Unit,
): T = try {
    withTimeout(timeoutMs) { deferred.await() }
} catch (_: TimeoutCancellationException) {
    throw IllegalStateException(message)
} finally {
    cleanup()
}


