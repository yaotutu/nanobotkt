package com.nanobotkt.core.transport

import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.async
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.withTimeoutOrNull
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import okhttp3.OkHttpClient
import okhttp3.Response
import okhttp3.WebSocket
import okhttp3.WebSocketListener
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicReference

class NanobotTransportAcceptanceTest {
    private lateinit var server: MockWebServer
    private lateinit var client: OkHttpClient
    private lateinit var transport: NanobotTransport
    private lateinit var credentials: TestCredentials
    private val receivedFrames = Channel<String>(Channel.UNLIMITED)
    private val serverSocket = AtomicReference<WebSocket?>()
    private val serverSockets = CopyOnWriteArrayList<WebSocket>()
    private val json = Json {
        ignoreUnknownKeys = true
        explicitNulls = false
        encodeDefaults = false
    }

    @Before
    fun setUp() {
        server = MockWebServer()
        server.start()
        client = OkHttpClient()
        credentials = TestCredentials(server.url("/ws").toString())
        transport = NanobotTransport(
            client = client,
            json = json,
            credentials = credentials,
        )
    }

    @After
    fun tearDown() {
        transport.close()
        serverSockets.forEach { it.close(1000, "test_done") }
        serverSockets.clear()
        serverSocket.set(null)
        client.dispatcher.executorService.shutdownNow()
        client.connectionPool.evictAll()
        server.shutdown()
    }

    @Test
    fun `goal running fallback does not accept side channel message`() = runBlocking {
        connectWebSocket()

        val result = transport.sendMessage(
            chatId = "chat-1",
            content = "/status",
            startsNewRun = false,
        )
        val frame = json.parseToJsonElement(withTimeout(2_000) { receivedFrames.receive() }).jsonObject
        val turnId = frame.getValue("turn_id").jsonPrimitive.content
        assertFalse(frame.containsKey("starts_new_run"))

        serverSocket.get()!!.send(
            """{"event":"goal_status","chat_id":"chat-1","status":"running"}""",
        )
        assertNull(withTimeoutOrNull(150) { result.accepted.await() })

        serverSocket.get()!!.send(
            """{"event":"delta","chat_id":"chat-1","text":"ok","turn_id":"$turnId"}""",
        )
        withTimeout(2_000) { result.accepted.await() }
    }

    @Test
    fun `logout attachment reset prevents old chat from being reattached`() = runBlocking {
        connectWebSocket()
        transport.attach("old-account-chat")
        assertEquals("attach", receiveFrame().getValue("type").jsonPrimitive.content)

        // 模拟注销：清除会话级 attachment 后关闭并重新建立连接。
        transport.clearAttachments()
        transport.close()
        connectWebSocket()

        assertNull(withTimeoutOrNull(500) { receivedFrames.receive() })
    }

    @Test
    fun `ordinary reconnect preserves attachment for the same session`() = runBlocking {
        connectWebSocket()
        transport.attach("current-account-chat")
        assertEquals("attach", receiveFrame().getValue("type").jsonPrimitive.content)

        // 普通断线/重连没有更换认证主体，attachment 应继续恢复。
        transport.close()
        connectWebSocket()

        val reattached = receiveFrame()
        assertEquals("attach", reattached.getValue("type").jsonPrimitive.content)
        assertEquals("current-account-chat", reattached.getValue("chat_id").jsonPrimitive.content)
    }

    @Test
    fun `disconnect removes queued message after acceptance failure`() = runBlocking {
        // 先在后台激活一个已认证会话，使 Transport 保持“会话有效但暂不领取 WS Token”的状态。
        // 这样可以稳定构造尚未连通时的 outbound 队列，又不会依赖网络恢复去隐式激活已关闭会话。
        transport.onBackground()
        transport.connect()

        // 未连接时发送会把 message 放进 outbound；网络状态切换到不可用时，
        // pending 会失败，重连后不应再把这条已经失败的非幂等消息发出去。
        val result = transport.sendMessage(
            chatId = "queued-chat",
            content = "must-not-replay",
            acceptanceTimeoutMs = 5_000,
        )
        transport.setNetworkAvailable(false)

        try {
            result.accepted.await()
            throw AssertionError("expected connection_closed")
        } catch (error: IllegalStateException) {
            assertEquals("connection_closed", error.message)
        }

        // 回到前台时网络仍不可用，因此只恢复会话状态；直到网络真正恢复后才允许领取
        // 新的一次性 WS Token 并建立连接。
        transport.resume()
        server.enqueue(
            MockResponse().withWebSocketUpgrade(
                object : WebSocketListener() {
                    override fun onOpen(webSocket: WebSocket, response: Response) {
                        serverSocket.set(webSocket)
                        serverSockets += webSocket
                    }

                    override fun onMessage(webSocket: WebSocket, text: String) {
                        receivedFrames.trySend(text)
                    }
                },
            ),
        )
        transport.setNetworkAvailable(true)
        withTimeout(2_000) { transport.state.first { it.status == TransportStatus.OPEN } }
        val first = withTimeoutOrNull(500) { receiveFrame() }
        // knownChats 仍会在同一会话重连时发送 attach，但失败的 message 不能出现。
        assertEquals("attach", first?.getValue("type")?.jsonPrimitive?.content)
        assertNull(withTimeoutOrNull(500) { receiveFrame() })
    }

    @Test
    fun `healthy socket remains open beyond ten seconds in ordinary background`() = runBlocking {
        connectWebSocket()
        val credentialCallsBeforeBackground = credentials.reauthCalls.get()

        transport.onBackground()
        delay(10_500)

        // 客户端不能再用历史 10 秒计时器主动关闭健康 Socket，也不能偷偷领取新凭据。
        assertEquals(TransportStatus.OPEN, transport.state.value.status)
        assertFalse(transport.state.value.appForeground)
        assertEquals(credentialCallsBeforeBackground, credentials.reauthCalls.get())
        assertEquals(1, server.requestCount)
    }

    @Test
    fun `ordinary background disconnect waits for foreground before reconnecting`() = runBlocking {
        connectWebSocket()
        val credentialCallsBeforeDisconnect = credentials.reauthCalls.get()
        transport.onBackground()

        serverSocket.get()!!.close(1000, "background_disconnect")
        withTimeout(2_000) { transport.state.first { it.status == TransportStatus.CLOSED } }
        delay(1_300)

        // 没有 active-turn FGS 时，后台断线只记录 CLOSED，不领取 Bootstrap/WS 凭据。
        assertEquals(credentialCallsBeforeDisconnect, credentials.reauthCalls.get())
        assertEquals(1, server.requestCount)

        server.enqueue(webSocketUpgrade())
        transport.resume()
        withTimeout(2_000) { transport.state.first { it.status == TransportStatus.OPEN } }
        assertEquals(credentialCallsBeforeDisconnect + 1, credentials.reauthCalls.get())
    }

    @Test
    fun `credential failure does not block a later reconnect`() = runBlocking {
        connectWebSocket()
        credentials.failNextReauthentication()

        // 已打开连接断开后，下一次一次性凭据领取故意失败。Transport 必须先释放当前
        // reconnectJob，再安排后续退避；否则非空 Job 引用会永久阻塞下一轮重连。
        serverSocket.get()!!.close(1000, "trigger_reconnect")
        withTimeout(3_000) {
            while (credentials.reauthCalls.get() < 2) delay(10)
        }

        server.enqueue(webSocketUpgrade())
        withTimeout(5_000) {
            transport.state.first {
                it.status == TransportStatus.OPEN && credentials.reauthCalls.get() >= 3
            }
        }
        Unit
    }

    @Test
    fun `manual reconnect replaces an open socket with fresh credentials`() = runBlocking {
        connectWebSocket()
        val credentialCallsBeforeReconnect = credentials.reauthCalls.get()
        server.enqueue(webSocketUpgrade())

        // Settings 中的 Reconnect 是显式用户操作：即使当前状态仍为 OPEN，也必须关闭旧 Socket，
        // 重新领取一次性 Token 并完成新握手，而不是退化成 connect() 的幂等“确保已连接”。
        transport.reconnect()
        withTimeout(3_000) {
            while (
                transport.state.value.status != TransportStatus.OPEN ||
                credentials.reauthCalls.get() <= credentialCallsBeforeReconnect
            ) {
                delay(10)
            }
        }

        assertEquals(credentialCallsBeforeReconnect + 1, credentials.reauthCalls.get())
        assertEquals(2, server.requestCount)
    }

    @Test
    fun `sent message disconnect reports delivery unknown`() = runBlocking {
        connectWebSocket()

        val result = transport.sendMessage(
            chatId = "chat-1",
            content = "message whose delivery becomes uncertain",
        )
        val frame = receiveFrame()
        val turnId = frame.getValue("turn_id").jsonPrimitive.content
        val error = async(start = CoroutineStart.UNDISPATCHED) {
            transport.errors.first { it is TransportError.DeliveryUnknown }
        }

        // 服务端已经收到客户端发送的 frame，但连接在 acceptance 到达前断开；
        // 此时客户端不能把结果伪装成“未发送”，而应明确报告投递状态未知。
        serverSocket.get()!!.close(1000, "connection_lost_after_send")

        awaitIllegalStateMessage("socket_delivery_unknown") { result.accepted.await() }
        assertEquals(
            TransportError.DeliveryUnknown("chat-1", turnId),
            withTimeout(2_000) { error.await() },
        )
    }

    @Test
    fun `websocket close code 1009 rejects pending message as too big`() = runBlocking {
        connectWebSocket()

        val result = transport.sendMessage(
            chatId = "chat-1",
            content = "message rejected by websocket frame limit",
        )
        receiveFrame()
        val error = async(start = CoroutineStart.UNDISPATCHED) {
            transport.errors.first { it is TransportError.MessageTooBig }
        }

        // 1009 是 WebSocket 的 Message Too Big close code；它应区别于普通断线，
        // 同时让正在等待 acceptance 的调用方得到稳定的 message_too_big 错误。
        serverSocket.get()!!.close(1009, "frame_too_large")

        awaitIllegalStateMessage("message_too_big") { result.accepted.await() }
        assertEquals(TransportError.MessageTooBig(), withTimeout(2_000) { error.await() })
        withTimeout(2_000) {
            transport.state.first { it.error == "message_too_big" && it.needsCanonicalRefresh }
        }
        Unit
    }

    @Test
    fun `network loss cancels reconnect before credential refresh`() = runBlocking {
        connectWebSocket()
        serverSocket.get()!!.close(1000, "trigger_reconnect")
        withTimeout(2_000) {
            transport.state.first { it.status == TransportStatus.RECONNECTING }
        }

        // scheduleReconnect 的退避窗口内网络恢复事件取消重连任务；取消后不应再
        // 调用凭据刷新，也不应在网络仍不可用时发起第二条 WebSocket 连接。
        transport.setNetworkAvailable(false)
        val reauthCallsAtCancellation = credentials.reauthCalls.get()
        delay(1_300)

        assertEquals(reauthCallsAtCancellation, credentials.reauthCalls.get())
        assertEquals(TransportStatus.CLOSED, transport.state.value.status)
        assertFalse(transport.state.value.networkAvailable)
        assertEquals(1, server.requestCount)
    }


    @Test
    fun `canonical refresh acknowledgement cannot clear a newer dirty generation`() {
        transport.setNetworkAvailable(false)
        val firstDirty = transport.state.value
        assertTrue(firstDirty.needsCanonicalRefresh)

        // 再经过一次独立网络边界，generation 必须推进。旧 HTTP 请求只持有 firstDirty 的代次，
        // 即使随后成功返回也不能清除第二次断线产生的新 dirty 状态。
        transport.setNetworkAvailable(true)
        transport.setNetworkAvailable(false)
        val latestDirty = transport.state.value
        assertTrue(latestDirty.canonicalRefreshGeneration > firstDirty.canonicalRefreshGeneration)

        assertFalse(transport.acknowledgeCanonicalRefresh(firstDirty.canonicalRefreshGeneration))
        assertTrue(transport.state.value.needsCanonicalRefresh)
        assertTrue(transport.acknowledgeCanonicalRefresh(latestDirty.canonicalRefreshGeneration))
        assertFalse(transport.state.value.needsCanonicalRefresh)
    }

    @Test
    fun `closed authentication session is not reactivated by lifecycle or network events`() = runBlocking {
        connectWebSocket()
        transport.close()
        val credentialCallsAfterClose = credentials.reauthCalls.get()

        // close() 表示认证会话已经结束。前台事件和网络恢复只能恢复已有会话，不能自行
        // 重新激活 Transport；否则登录页会产生无凭据退避任务，并阻塞随后真正的登录连接。
        transport.resume()
        transport.reconnect()
        transport.setNetworkAvailable(false)
        transport.setNetworkAvailable(true)
        delay(300)

        assertEquals(credentialCallsAfterClose, credentials.reauthCalls.get())
        assertEquals(TransportStatus.CLOSED, transport.state.value.status)

        // 新认证会话必须通过显式 connect() 激活，此时才允许领取下一枚一次性 Token。
        server.enqueue(webSocketUpgrade())
        transport.connect()
        withTimeout(2_000) { transport.state.first { it.status == TransportStatus.OPEN } }
        assertEquals(credentialCallsAfterClose + 1, credentials.reauthCalls.get())
    }

    @Test
    fun `ordinary turn rejected error fails matching message and emits typed error`() = runBlocking {
        connectWebSocket()

        val result = transport.sendMessage(chatId = "chat-1", content = "rejected turn")
        val turnId = receiveFrame().getValue("turn_id").jsonPrimitive.content
        val error = async(start = CoroutineStart.UNDISPATCHED) {
            transport.errors.first { it is TransportError.TurnRejected }
        }
        serverSocket.get()!!.send(
            """{"event":"error","chat_id":"chat-1","turn_id":"$turnId","detail":"turn_rejected","reason":"agent_busy"}""",
        )

        awaitIllegalStateMessage("turn_rejected") { result.accepted.await() }
        assertEquals(
            TransportError.TurnRejected("chat-1", turnId, "turn_rejected", "agent_busy"),
            withTimeout(2_000) { error.await() },
        )
    }

    @Test
    fun `workspace scope rejected error fails matching message and emits typed error`() = runBlocking {
        connectWebSocket()

        val result = transport.sendMessage(chatId = "chat-1", content = "outside workspace")
        val turnId = receiveFrame().getValue("turn_id").jsonPrimitive.content
        val error = async(start = CoroutineStart.UNDISPATCHED) {
            transport.errors.first { it is TransportError.WorkspaceScopeRejected }
        }
        serverSocket.get()!!.send(
            """{"event":"error","chat_id":"chat-1","turn_id":"$turnId","detail":"workspace_scope_rejected","reason":"path_not_allowed"}""",
        )

        awaitIllegalStateMessage("workspace_scope_rejected") { result.accepted.await() }
        assertEquals(
            TransportError.WorkspaceScopeRejected("chat-1", turnId, "path_not_allowed"),
            withTimeout(2_000) { error.await() },
        )
    }

    @Test
    fun `transcription success preserves request wire format and resolves text`() = runBlocking {
        connectWebSocket()

        val result = async {
            transport.transcribeAudio(
                dataUrl = "data:audio/wav;base64,AA==",
                durationMs = 1_234,
                timeoutMs = 2_000,
            )
        }
        val frame = receiveFrame()
        val requestId = frame.getValue("request_id").jsonPrimitive.content
        assertEquals("transcribe_audio", frame.getValue("type").jsonPrimitive.content)
        assertEquals("data:audio/wav;base64,AA==", frame.getValue("data_url").jsonPrimitive.content)
        assertEquals("1234", frame.getValue("duration_ms").jsonPrimitive.content)

        serverSocket.get()!!.send(
            """{"event":"transcription_result","request_id":"$requestId","text":"hello from speech"}""",
        )
        assertEquals("hello from speech", withTimeout(2_000) { result.await() })
    }

    @Test
    fun `transcription failure completes with provider detail`() = runBlocking {
        connectWebSocket()

        // 使用独立 SupervisorJob 隔离“预期失败”的 async；否则 async 子协程的
        // 异常会沿 runBlocking 的结构化并发树提前取消测试本身。
        val result = async(SupervisorJob()) {
            transport.transcribeAudio(
                dataUrl = "data:audio/wav;base64,QQ==",
                timeoutMs = 2_000,
            )
        }
        val requestId = receiveFrame().getValue("request_id").jsonPrimitive.content
        serverSocket.get()!!.send(
            """{"event":"transcription_error","request_id":"$requestId","detail":"provider_unavailable","provider":"test-provider"}""",
        )

        awaitIllegalStateMessage("provider_unavailable") { result.await() }
    }

    @Test
    fun `transcription cancellation removes local pending request`() = runBlocking {
        connectWebSocket()

        val cancelled = async {
            transport.transcribeAudio(
                dataUrl = "data:audio/wav;base64,YmFk",
                timeoutMs = 5_000,
            )
        }
        val cancelledFrame = receiveFrame()
        val cancelledRequestId = cancelledFrame.getValue("request_id").jsonPrimitive.content
        cancelled.cancel()
        cancelled.join()
        assertTrue(cancelled.isCancelled)

        // 取消只结束本地等待；迟到的服务端响应不能填充已取消的请求，且后续
        // transcription 请求仍应能建立新的 pending 条目并正常完成。
        serverSocket.get()!!.send(
            """{"event":"transcription_result","request_id":"$cancelledRequestId","text":"late result"}""",
        )
        val next = async {
            transport.transcribeAudio(
                dataUrl = "data:audio/wav;base64,Z29vZA==",
                timeoutMs = 2_000,
            )
        }
        val nextRequestId = receiveFrame().getValue("request_id").jsonPrimitive.content
        serverSocket.get()!!.send(
            """{"event":"transcription_result","request_id":"$nextRequestId","text":"next result"}""",
        )
        assertEquals("next result", withTimeout(2_000) { next.await() })
    }

    @Test
    fun `transcription timeout removes pending request`() = runBlocking {
        connectWebSocket()

        // 同样隔离预期的超时异常，确保断言能够拿到 deferred 的失败结果。
        val result = async(SupervisorJob()) {
            transport.transcribeAudio(
                dataUrl = "data:audio/wav;base64,dGltZW91dA==",
                timeoutMs = 100,
            )
        }
        receiveFrame()

        awaitIllegalStateMessage("transcription_timeout") { result.await() }
    }

    @Test
    fun `goal running with turn id accepts only the matching pending message`() = runBlocking {
        connectWebSocket()

        val first = transport.sendMessage(chatId = "chat-1", content = "first", startsNewRun = true)
        val firstFrame = receiveFrame()
        val firstTurnId = firstFrame.getValue("turn_id").jsonPrimitive.content
        val second = transport.sendMessage(chatId = "chat-1", content = "second", startsNewRun = true)
        val secondFrame = receiveFrame()
        val secondTurnId = secondFrame.getValue("turn_id").jsonPrimitive.content

        serverSocket.get()!!.send(
            """{"event":"goal_status","chat_id":"chat-1","status":"running","turn_id":"$secondTurnId"}""",
        )

        assertNull(withTimeoutOrNull(100) { first.accepted.await() })
        withTimeout(2_000) { second.accepted.await() }

        serverSocket.get()!!.send(
            """{"event":"message_accepted","chat_id":"chat-1","turn_id":"$firstTurnId"}""",
        )
        withTimeout(2_000) { first.accepted.await() }
    }

    @Test
    fun `goal running without turn id does not choose an arbitrary pending message`() = runBlocking {
        connectWebSocket()

        val first = transport.sendMessage(chatId = "chat-1", content = "first", startsNewRun = true)
        val firstFrame = receiveFrame()
        val firstTurnId = firstFrame.getValue("turn_id").jsonPrimitive.content
        val second = transport.sendMessage(chatId = "chat-1", content = "second", startsNewRun = true)
        val secondFrame = receiveFrame()
        val secondTurnId = secondFrame.getValue("turn_id").jsonPrimitive.content

        serverSocket.get()!!.send(
            """{"event":"goal_status","chat_id":"chat-1","status":"running"}""",
        )

        assertNull(withTimeoutOrNull(100) { first.accepted.await() })
        assertNull(withTimeoutOrNull(100) { second.accepted.await() })

        serverSocket.get()!!.send(
            """{"event":"message_accepted","chat_id":"chat-1","turn_id":"$firstTurnId"}""",
        )
        serverSocket.get()!!.send(
            """{"event":"message_accepted","chat_id":"chat-1","turn_id":"$secondTurnId"}""",
        )
        withTimeout(2_000) { first.accepted.await() }
        withTimeout(2_000) { second.accepted.await() }
    }

    @Test
    fun `goal running fallback accepts normal new run message`() = runBlocking {
        connectWebSocket()

        val result = transport.sendMessage(
            chatId = "chat-1",
            content = "hello",
            startsNewRun = true,
        )
        withTimeout(2_000) { receivedFrames.receive() }

        serverSocket.get()!!.send(
            """{"event":"goal_status","chat_id":"chat-1","status":"running"}""",
        )

        withTimeout(2_000) { result.accepted.await() }
    }

    private suspend fun receiveFrame() =
        json.parseToJsonElement(withTimeout(2_000) { receivedFrames.receive() }).jsonObject

    private suspend fun awaitIllegalStateMessage(
        expected: String,
        block: suspend () -> Unit,
    ) {
        val failure = runCatching { block() }.exceptionOrNull()
        assertEquals(expected, (failure as? IllegalStateException)?.message)
    }

    private suspend fun connectWebSocket() {
        server.enqueue(webSocketUpgrade())
        transport.connect()
        withTimeout(2_000) {
            transport.state.first { it.status == TransportStatus.OPEN }
        }
    }

    private fun webSocketUpgrade() = MockResponse().withWebSocketUpgrade(
        object : WebSocketListener() {
            override fun onOpen(webSocket: WebSocket, response: Response) {
                serverSocket.set(webSocket)
                serverSockets += webSocket
            }

            override fun onMessage(webSocket: WebSocket, text: String) {
                receivedFrames.trySend(text)
            }
        },
    )

    private data class TestCredentials(
        val url: String,
    ) : WebSocketCredentialProvider {
        val reauthCalls = AtomicInteger()
        private val failuresRemaining = AtomicInteger()

        fun failNextReauthentication() {
            failuresRemaining.incrementAndGet()
        }

        override suspend fun freshWebSocketUrl(): String {
            reauthCalls.incrementAndGet()
            // 失败次数必须在零处饱和。若直接 getAndDecrement()，每次成功调用都会把计数减成
            // 负数，随后 failNextReauthentication() 只会加回零，导致“下一次失败”测试失真。
            val shouldFail = failuresRemaining.getAndUpdate { remaining ->
                if (remaining > 0) remaining - 1 else 0
            } > 0
            if (shouldFail) {
                throw IllegalStateException("reauthentication_failed")
            }
            return url
        }

        override fun maxFrameBytes(): Int? = null
    }
}
