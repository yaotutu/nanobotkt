package com.nanobotkt.feature.chat

import com.nanobotkt.core.model.BootstrapResponse
import com.nanobotkt.core.model.DefaultAccessMode
import com.nanobotkt.core.model.BootstrapSnapshotProvider
import com.nanobotkt.core.model.IngressLimitsProvider
import com.nanobotkt.core.model.WorkspacesPayload
import com.nanobotkt.core.network.AuthContext
import com.nanobotkt.core.network.GatewayApiClient
import com.nanobotkt.core.transport.NanobotTransport
import com.nanobotkt.core.transport.TransportCredentials
import com.nanobotkt.core.transport.TransportStatus
import com.nanobotkt.feature.workspaces.WorkspacesRepository
import com.nanobotkt.feature.workspaces.WorkspacesUiState
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import okhttp3.OkHttpClient
import okhttp3.WebSocket
import okhttp3.WebSocketListener
import okhttp3.mockwebserver.Dispatcher
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import okhttp3.mockwebserver.RecordedRequest
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicReference

class DefaultChatRepositoryTest {
    private lateinit var server: MockWebServer
    private lateinit var httpClient: OkHttpClient
    private lateinit var transport: NanobotTransport
    private lateinit var currentRepository: DefaultChatRepository
    private val json = Json {
        ignoreUnknownKeys = true
        explicitNulls = false
        encodeDefaults = false
    }

    @Before
    fun setUp() {
        server = MockWebServer()
        server.start()
        httpClient = OkHttpClient()
        transport = NanobotTransport(
            client = httpClient,
            json = json,
            credentials = TestCredentials(server.url("/ws").toString()),
        )
    }

    @After
    fun tearDown() {
        transport.close()
        httpClient.dispatcher.executorService.shutdownNow()
        httpClient.connectionPool.evictAll()
        server.shutdown()
    }

    @Test
    fun `openSession requests latest thread with encoded path and query and publishes state`() = runBlocking {
        val sessionKey = "webui:folder /中文?"
        val requestRef = AtomicReference<RecordedRequest>()
        server.dispatcher = threadDispatcher { request ->
            requestRef.set(request)
            jsonResponse(
                """
                {
                  "schemaVersion": 1,
                  "sessionKey": "$sessionKey",
                  "messages": [
                    {"id":"latest-1","role":"user","content":"hello","createdAt":1}
                  ],
                  "page": {"before_cursor":"before-1","has_more_before":true,"user_message_offset":1}
                }
                """.trimIndent(),
            )
        }

        val repository = newRepository()
        repository.openSession(sessionKey, "chat-latest")

        val loaded = awaitState {
            it.sessionKey == sessionKey && !it.loading && it.messages.map { message -> message.id } == listOf("latest-1")
        }
        val request = requestRef.get()

        assertEquals("chat-latest", loaded.chatId)
        assertFalse(loaded.loading)
        assertEquals(listOf("latest-1"), loaded.messages.map { it.id })
        assertTrue(loaded.hasMoreBefore)
        assertEquals("before-1", loaded.beforeCursor)
        // sessionKey 里的斜杠、空格、中文和问号都必须留在 path segment 中，不能改变路由边界。
        assertEquals(
            "/api/sessions/webui%3Afolder%20%2F%E4%B8%AD%E6%96%87%3F/webui-thread",
            request.path?.substringBefore('?'),
        )
        assertEquals("160", request.requestUrl?.queryParameter("limit"))
        assertEquals("latest", request.requestUrl?.queryParameter("direction"))
        assertNull(request.requestUrl?.queryParameter("before"))
    }

    @Test
    fun `loadFilePreview uses encoded session path and file query`() = runBlocking {
        val sessionKey = "webui:folder /中文?"
        val requestRef = AtomicReference<RecordedRequest>()
        server.dispatcher = object : Dispatcher() {
            override fun dispatch(request: RecordedRequest): MockResponse = when {
                request.path?.substringBefore('?')?.endsWith("/webui-thread") == true ->
                    jsonResponse(threadPayload(sessionKey, messageId = "initial", before = null, hasMoreBefore = false))
                request.path?.substringBefore('?')?.endsWith("/file-preview") == true -> {
                    requestRef.set(request)
                    jsonResponse(
                        """{
                          "path":"/workspace/src/main.kt",
                          "display_path":"src/main.kt",
                          "project_path":"/workspace",
                          "language":"kotlin",
                          "content":"fun main() {}",
                          "size":13,
                          "truncated":false
                        }""".trimIndent(),
                    )
                }
                else -> MockResponse().setResponseCode(404)
            }
        }

        val repository = newRepository()
        repository.openSession(sessionKey, "chat-preview")
        awaitState { it.sessionKey == sessionKey && !it.loading }

        repository.loadFilePreview("/workspace/src/main.kt")
        val loaded = awaitState { it.filePreview?.content == "fun main() {}" && !it.filePreviewLoading }
        val request = requestRef.get()

        assertEquals("chat-preview", loaded.chatId)
        assertEquals("src/main.kt", loaded.filePreview?.displayPath)
        assertEquals(
            "/api/sessions/webui%3Afolder%20%2F%E4%B8%AD%E6%96%87%3F/file-preview",
            request.path?.substringBefore('?'),
        )
        assertEquals("/workspace/src/main.kt", request.requestUrl?.queryParameter("path"))
    }

    @Test
    fun `late preview response from an older request cannot overwrite a newer file in the same session`() = runBlocking {
        val sessionKey = "webui:same-session"
        val firstStarted = CountDownLatch(1)
        val secondStarted = CountDownLatch(1)
        val releaseFirst = CountDownLatch(1)
        val requestCount = AtomicInteger(0)
        server.dispatcher = object : Dispatcher() {
            override fun dispatch(request: RecordedRequest): MockResponse = when {
                request.path?.substringBefore('?')?.endsWith("/webui-thread") == true ->
                    jsonResponse(threadPayload(sessionKey, messageId = "initial", before = null, hasMoreBefore = false))
                request.path?.substringBefore('?')?.endsWith("/file-preview") == true -> {
                    if (requestCount.incrementAndGet() == 1) {
                        firstStarted.countDown()
                        assertTrue(releaseFirst.await(2, TimeUnit.SECONDS))
                        jsonResponse(filePreviewJson("old.kt", "old content"))
                    } else {
                        secondStarted.countDown()
                        jsonResponse(filePreviewJson("new.kt", "new content"))
                    }
                }
                else -> MockResponse().setResponseCode(404)
            }
        }

        val repository = newRepository()
        repository.openSession(sessionKey, "chat-same")
        awaitState { it.sessionKey == sessionKey && !it.loading }

        repository.loadFilePreview("/workspace/old.kt")
        assertTrue(firstStarted.await(2, TimeUnit.SECONDS))
        repository.loadFilePreview("/workspace/new.kt")
        assertTrue(secondStarted.await(2, TimeUnit.SECONDS))
        awaitState { it.filePreview?.displayPath == "new.kt" && !it.filePreviewLoading }

        releaseFirst.countDown()
        delay(100)

        assertEquals("new.kt", repository.state.value.filePreview?.displayPath)
        assertEquals("new content", repository.state.value.filePreview?.content)
    }

    @Test
    fun `late file preview response cannot write into a newly opened session`() = runBlocking {
        val firstSession = "webui:first"
        val secondSession = "webui:second"
        val previewStarted = CountDownLatch(1)
        val releasePreview = CountDownLatch(1)
        server.dispatcher = object : Dispatcher() {
            override fun dispatch(request: RecordedRequest): MockResponse = when {
                request.path?.substringBefore('?')?.endsWith("/file-preview") == true -> {
                    previewStarted.countDown()
                    releasePreview.await(2, TimeUnit.SECONDS)
                    jsonResponse(
                        """{
                          "path":"/workspace/old.kt",
                          "display_path":"old.kt",
                          "project_path":"/workspace",
                          "language":"kotlin",
                          "content":"old session",
                          "size":11,
                          "truncated":false
                        }""".trimIndent(),
                    )
                }
                request.path?.substringBefore('?')?.endsWith("/webui-thread") == true ->
                    jsonResponse(threadPayload(
                        if (request.requestUrl?.encodedPath?.contains("second") == true) secondSession else firstSession,
                        messageId = "thread",
                        before = null,
                        hasMoreBefore = false,
                    ))
                else -> MockResponse().setResponseCode(404)
            }
        }

        val repository = newRepository()
        repository.openSession(firstSession, "chat-first")
        awaitState { it.sessionKey == firstSession && !it.loading }
        repository.loadFilePreview("/workspace/old.kt")
        assertTrue(previewStarted.await(2, TimeUnit.SECONDS))

        repository.openSession(secondSession, "chat-second")
        releasePreview.countDown()
        val secondState = awaitState { it.sessionKey == secondSession && !it.loading }
        delay(100)

        assertEquals("chat-second", secondState.chatId)
        assertNull(repository.state.value.filePreview)
        assertNull(repository.state.value.filePreviewError)
    }

    @Test
    fun `openSession 404 clears previous thread and loading state`() = runBlocking {
        val firstSession = "webui:first"
        val secondSession = "webui:missing"
        var threadRequestCount = 0
        server.dispatcher = threadDispatcher { request ->
            threadRequestCount += 1
            if (threadRequestCount == 1) {
                jsonResponse(threadPayload(firstSession, messageId = "old-message", before = "old-before"))
            } else {
                MockResponse().setResponseCode(404)
            }
        }

        val repository = newRepository()
        repository.openSession(firstSession, "chat-first")
        awaitState { it.sessionKey == firstSession && !it.loading && it.messages.isNotEmpty() }

        repository.openSession(secondSession, "chat-missing")
        val cleared = awaitState {
            it.sessionKey == secondSession &&
                !it.loading &&
                !it.loadingOlder &&
                it.messages.isEmpty() &&
                !it.hasMoreBefore &&
                it.beforeCursor == null
        }

        assertEquals("chat-missing", cleared.chatId)
        assertTrue(cleared.messages.isEmpty())
        assertFalse(cleared.loading)
        assertFalse(cleared.loadingOlder)
        assertNull(cleared.error)
    }

    @Test
    fun `openSession 5xx clears loading and exposes refresh error without stale messages`() = runBlocking {
        val firstSession = "webui:first"
        val failingSession = "webui:failing"
        server.dispatcher = threadDispatcher { request ->
            // 按 session path 返回响应，避免初始化期间的并发刷新让测试依赖请求到达顺序。
            if (request.path?.contains("webui%3Afailing") == true) {
                MockResponse()
                    .setResponseCode(503)
                    .setBody("thread backend unavailable")
            } else {
                jsonResponse(threadPayload(firstSession, messageId = "stale-message", before = "stale-before"))
            }
        }

        val repository = newRepository()
        repository.openSession(firstSession, "chat-first")
        awaitState { it.sessionKey == firstSession && !it.loading && it.messages.isNotEmpty() }

        repository.openSession(failingSession, "chat-failing")
        val failed = awaitState {
            it.sessionKey == failingSession && !it.loading && it.error != null
        }

        assertEquals("chat-failing", failed.chatId)
        assertTrue(failed.messages.isEmpty())
        assertFalse(failed.loading)
        assertFalse(failed.loadingOlder)
        assertTrue(failed.error.orEmpty().contains("thread backend unavailable"))
    }

    @Test
    fun `send with stale session guard cannot mutate current session or emit message frame`() = runBlocking {
        val firstSession = "webui:first"
        val secondSession = "webui:second"
        val secondAttachSeen = CountDownLatch(1)
        val sentMessageFrameCount = AtomicInteger(0)
        val socketRef = AtomicReference<WebSocket>()
        server.dispatcher = object : Dispatcher() {
            override fun dispatch(request: RecordedRequest): MockResponse {
                val path = request.path?.substringBefore('?')
                return when {
                    path == "/ws" -> MockResponse().withWebSocketUpgrade(
                        object : WebSocketListener() {
                            override fun onOpen(webSocket: WebSocket, response: okhttp3.Response) {
                                socketRef.set(webSocket)
                            }

                            override fun onMessage(webSocket: WebSocket, text: String) {
                                val frame = json.parseToJsonElement(text).jsonObject
                                when (frame["type"]?.jsonPrimitive?.content) {
                                    "attach" -> if (frame["chat_id"]?.jsonPrimitive?.content == "chat-second") {
                                        secondAttachSeen.countDown()
                                    }
                                    "message" -> sentMessageFrameCount.incrementAndGet()
                                }
                            }
                        },
                    )
                    path?.endsWith("/webui-thread") == true -> when {
                        path.contains("webui%3Afirst") -> jsonResponse(
                            threadPayload(firstSession, messageId = "first-only", before = null),
                        )
                        path.contains("webui%3Asecond") -> jsonResponse(
                            threadPayload(secondSession, messageId = "second-only", before = null),
                        )
                        else -> MockResponse().setResponseCode(404)
                    }
                    else -> MockResponse().setResponseCode(404)
                }
            }
        }
        transport.connect()
        withTimeout(2_000) {
            transport.state.first { transportState -> transportState.status == TransportStatus.OPEN }
        }

        val repository = newRepository()
        repository.openSession(firstSession, "chat-first")
        val firstState = awaitState {
            it.sessionKey == firstSession &&
                !it.loading &&
                it.messages.map { message -> message.id } == listOf("first-only")
        }
        // Repository 的公开状态没有直接暴露 sessionGuard；生产 ViewModel 也是在入队时
        // 从当前 sessionKey/chatId 构造 guard，因此测试按同一真实边界捕获会话 A 身份。
        val staleGuard = ChatSessionGuard(
            sessionKey = firstState.sessionKey,
            chatId = firstState.chatId,
        )

        repository.openSession(secondSession, "chat-second")
        val secondStateBeforeSend = awaitState {
            it.sessionKey == secondSession &&
                !it.loading &&
                it.messages.map { message -> message.id } == listOf("second-only")
        }
        // 等待 B 的 attach 已经真正经过 WebSocket，避免把连接尚未刷新的时间差误判成
        // stale send 没有发帧；从这里开始，任何 message frame 都只能来自下面的 send。
        assertTrue(secondAttachSeen.await(2, TimeUnit.SECONDS))
        val messageFrameCountBeforeSend = sentMessageFrameCount.get()

        val failure = runCatching {
            withTimeout(2_000) {
                repository.send(
                    text = "must stay in session A",
                    options = ChatSendOptions(sessionGuard = staleGuard),
                )
            }
        }.exceptionOrNull()

        assertTrue(failure is IllegalStateException)
        assertEquals("session_changed", failure?.message)
        // guard 必须在 enqueueMessage 之前失败；否则 B 会出现本地乐观消息、活跃 turn
        // 或 sendingTurnIds。这里只比较发送相关字段，避免并发 composer catalog 刷新干扰断言。
        delay(250)
        val secondStateAfterSend = repository.state.value
        assertEquals(secondSession, secondStateAfterSend.sessionKey)
        assertEquals("chat-second", secondStateAfterSend.chatId)
        assertEquals(secondStateBeforeSend.messages, secondStateAfterSend.messages)
        assertEquals(secondStateBeforeSend.sendingTurnIds, secondStateAfterSend.sendingTurnIds)
        assertEquals(secondStateBeforeSend.activeTurnId, secondStateAfterSend.activeTurnId)
        // 传输层断言补足状态断言：即使未来乐观状态实现变化，旧 guard 也绝不能向 B 发 message。
        assertEquals(messageFrameCountBeforeSend, sentMessageFrameCount.get())
        assertEquals(0, sentMessageFrameCount.get())
        // 该测试只验证 stale guard，主动取消 WebSocket 可避免 MockWebServer 在 tearDown 时等待长连接。
        socketRef.get()?.close(1000, "test_done")
        Unit
    }

    @Test
    fun `loadOlder sends before cursor and page limit and prepends older messages`() = runBlocking {
        val sessionKey = "webui:paged"
        val olderRequestRef = AtomicReference<RecordedRequest>()
        server.dispatcher = threadDispatcher { request ->
            if (request.requestUrl?.queryParameter("before") == "before-1") {
                olderRequestRef.set(request)
                jsonResponse(
                    threadPayload(
                        sessionKey = sessionKey,
                        messageId = "older-1",
                        before = null,
                        hasMoreBefore = false,
                    ),
                )
            } else {
                jsonResponse(
                    threadPayload(
                        sessionKey = sessionKey,
                        messageId = "latest-1",
                        before = "before-1",
                        hasMoreBefore = true,
                    ),
                )
            }
        }

        val repository = newRepository()
        repository.openSession(sessionKey, "chat-paged")
        awaitState { it.sessionKey == sessionKey && !it.loading && it.hasMoreBefore }

        repository.loadOlder()
        val loaded = awaitState {
            it.sessionKey == sessionKey &&
                !it.loadingOlder &&
                it.messages.map { message -> message.id } == listOf("older-1", "latest-1")
        }
        val request = olderRequestRef.get()

        assertEquals("120", request.requestUrl?.queryParameter("limit"))
        assertEquals("before-1", request.requestUrl?.queryParameter("before"))
        assertNull(request.requestUrl?.queryParameter("direction"))
        assertFalse(loaded.loadingOlder)
        assertFalse(loaded.hasMoreBefore)
        assertNull(loaded.beforeCursor)
    }

    @Test
    fun `late loadOlder response from previous session cannot overwrite current session`() = runBlocking {
        val firstSession = "webui:first"
        val secondSession = "webui:second"
        val olderStarted = CountDownLatch(1)
        val releaseOlderResponse = CountDownLatch(1)
        val olderReturned = CountDownLatch(1)
        var threadRequestCount = 0

        server.dispatcher = threadDispatcher { request ->
            threadRequestCount += 1
            when {
                threadRequestCount == 1 -> jsonResponse(
                    threadPayload(firstSession, messageId = "first-latest", before = "first-before"),
                )
                request.requestUrl?.queryParameter("before") == "first-before" -> {
                    olderStarted.countDown()
                    releaseOlderResponse.await(2, TimeUnit.SECONDS)
                    olderReturned.countDown()
                    jsonResponse(threadPayload(firstSession, messageId = "first-older", before = null, hasMoreBefore = false))
                }
                else -> jsonResponse(threadPayload(secondSession, messageId = "second-only", before = null, hasMoreBefore = false))
            }
        }

        val repository = newRepository()
        repository.openSession(firstSession, "chat-first")
        awaitState { it.sessionKey == firstSession && !it.loading && it.hasMoreBefore }

        repository.loadOlder()
        assertTrue(olderStarted.await(2, TimeUnit.SECONDS))

        repository.openSession(secondSession, "chat-second")
        val current = awaitState {
            it.sessionKey == secondSession &&
                !it.loading &&
                it.messages.map { message -> message.id } == listOf("second-only")
        }

        releaseOlderResponse.countDown()
        assertTrue(olderReturned.await(2, TimeUnit.SECONDS))
        // 旧请求返回后，当前会话的消息、分页游标和 loading 状态都必须保持不变。
        assertEquals("chat-second", current.chatId)
        assertEquals(listOf("second-only"), repository.state.value.messages.map { it.id })
        assertFalse(repository.state.value.loading)
        assertFalse(repository.state.value.loadingOlder)
        assertEquals(secondSession, repository.state.value.sessionKey)
    }

    @Test
    fun `websocket events are isolated by chat and current turn converges after turn end`() = runBlocking {
        val socketRef = AtomicReference<WebSocket>()
        val socketOpened = CountDownLatch(1)
        val socketClosed = CountDownLatch(1)
        val webSocketListener = object : WebSocketListener() {
            override fun onOpen(webSocket: WebSocket, response: okhttp3.Response) {
                socketRef.set(webSocket)
                socketOpened.countDown()
            }

            override fun onMessage(webSocket: WebSocket, text: String) {
                val frame = json.parseToJsonElement(text).jsonObject
                if (frame.getValue("type").jsonPrimitive.content == "message") {
                    val chatId = frame.getValue("chat_id").jsonPrimitive.content
                    val turnId = frame.getValue("turn_id").jsonPrimitive.content
                    webSocket.send(
                        """{"event":"message_accepted","chat_id":"$chatId","turn_id":"$turnId"}""",
                    )
                }
            }

            override fun onClosed(webSocket: WebSocket, code: Int, reason: String) {
                socketClosed.countDown()
            }
        }
        server.dispatcher = object : Dispatcher() {
            override fun dispatch(request: RecordedRequest): MockResponse = when {
                request.path?.substringBefore('?') == "/ws" -> MockResponse().withWebSocketUpgrade(webSocketListener)
                request.path?.substringBefore('?')?.endsWith("/webui-thread") == true ->
                    jsonResponse(threadPayload("webui:current", messageId = "initial", before = null, hasMoreBefore = false))
                else -> MockResponse().setResponseCode(404)
            }
        }

        val repository = newRepository()
        transport.connect()
        // 先确认传输层真正完成握手，再打开会话，避免把 WebSocket 连接竞态误判为 Repository 事件过滤失败。
        withTimeout(2_000) {
            transport.state.first { it.status == TransportStatus.OPEN }
        }
        repository.openSession("webui:current", "current-chat")
        awaitState { it.sessionKey == "webui:current" && !it.loading }
        assertTrue(socketOpened.await(2, TimeUnit.SECONDS))
        val socket = socketRef.get()
        assertNotNull(socket)

        // 同一 WebSocket 上的其他 chat 事件必须完全不能改变当前会话。
        socket.send("""{"event":"delta","chat_id":"other-chat","text":"wrong","turn_id":"other-turn"}""")
        socket.send("""{"event":"turn_end","chat_id":"other-chat","turn_id":"other-turn"}""")
        socket.send(
            """{"event":"session_updated","chat_id":"other-chat","workspace_scope":{"project_path":"/other","access_mode":"restricted"}}""",
        )
        delay(100)
        val afterOtherChatEvents = repository.state.value
        assertEquals("current-chat", afterOtherChatEvents.chatId)
        assertTrue(afterOtherChatEvents.messages.none { it.content == "wrong" })
        assertNull(afterOtherChatEvents.activeTurnId)
        assertNull(afterOtherChatEvents.error)
        assertNull(afterOtherChatEvents.workspaceScope)

        // 真实发送先建立 optimistic turn，再由当前 chat 的 delta 和 turn_end 收敛。
        repository.send("hello")
        val sending = repository.state.value
        val turnId = sending.activeTurnId
        assertNotNull(turnId)
        // `send()` 会等待服务端的 message_accepted；测试服务端在收到消息后立即确认，
        // 因此返回时后台清理协程可能已经移除了 sendingTurnIds。activeTurnId 仍应保持到 turn_end，
        // 并且乐观用户消息必须已经写入当前会话。
        assertEquals(turnId, sending.activeTurnId)
        assertTrue(sending.messages.any { it.turnId == turnId && it.content == "hello" })

        socket.send("""{"event":"delta","chat_id":"current-chat","text":"answer","turn_id":"$turnId"}""")
        val streamed = awaitState { it.messages.any { message -> message.content.contains("answer") } }
        assertEquals("current-chat", streamed.chatId)

        socket.send("""{"event":"turn_end","chat_id":"current-chat","turn_id":"$turnId"}""")
        val ended = awaitState {
            it.activeTurnId == null &&
                it.sendingTurnIds.isEmpty() &&
                it.error == null &&
                // turn_end 先发布本地状态，随后才会异步刷新服务端 canonical history；等待刷新完成后再断言乐观消息已收敛。
                it.messages.none { message -> message.content == "hello" }
        }
        assertTrue(ended.messages.none { it.content == "hello" })

        // 迟到的旧 chat 事件不能重新打开 active turn、写入错误或污染消息。
        socket.send("""{"event":"turn_end","chat_id":"other-chat","turn_id":"other-turn"}""")
        socket.send("""{"event":"error","chat_id":"other-chat","detail":"old_error","turn_id":"other-turn"}""")
        delay(100)
        val afterLateOldEvents = repository.state.value
        assertEquals("current-chat", afterLateOldEvents.chatId)
        assertNull(afterLateOldEvents.activeTurnId)
        assertTrue(afterLateOldEvents.sendingTurnIds.isEmpty())
        assertNull(afterLateOldEvents.error)
        assertEquals(ended.messages, afterLateOldEvents.messages)

        // 主动关闭测试 WebSocket，避免 MockWebServer 在 tearDown 时仍等待连接队列退出。
        assertTrue(socket.close(1000, "test_done"))
        assertTrue(socketClosed.await(2, TimeUnit.SECONDS))
    }

    private fun newRepository(): DefaultChatRepository {
        currentRepository = DefaultChatRepository(
        api = GatewayApiClient(
            client = httpClient,
            json = json,
            authContext = object : AuthContext {
                override val baseUrl: String = server.url("/").toString()
                override val apiToken: String? = null
            },
        ),
        transport = transport,
        limitsProvider = object : IngressLimitsProvider {
            override fun currentIngressLimits() = null
        },
        bootstrapProvider = object : BootstrapSnapshotProvider {
            override fun currentBootstrap(): BootstrapResponse? = null
        },
            workspacesRepository = object : WorkspacesRepository {
                private val mutableState = MutableStateFlow(WorkspacesUiState())
                override val state = mutableState
                override fun reset() = Unit
                override suspend fun refresh() = Unit
                // 该 fake 只服务于聊天仓储测试，工作区默认权限更新不属于本测试范围。
                override suspend fun updateDefaultAccessMode(mode: DefaultAccessMode) = Unit
                override fun clearError() = Unit
            },
        )
        return currentRepository
    }

    private suspend fun awaitState(predicate: (ChatUiState) -> Boolean): ChatUiState =
        withTimeout(3_000) { currentRepository.state.first(predicate) }

    private fun threadDispatcher(response: (RecordedRequest) -> MockResponse): Dispatcher =
        object : Dispatcher() {
            override fun dispatch(request: RecordedRequest): MockResponse =
                if (request.path?.substringBefore('?')?.endsWith("/webui-thread") == true) {
                    response(request)
                } else {
                    // ChatRepository 初始化时会并发刷新 composer catalog；这些测试只关心 thread 路由。
                    MockResponse().setResponseCode(404)
                }
        }

    private fun jsonResponse(body: String): MockResponse = MockResponse()
        .setResponseCode(200)
        .setHeader("Content-Type", "application/json; charset=utf-8")
        .setBody(body)

    private fun filePreviewJson(displayPath: String, content: String): String =
        """
        {
          "path":"/workspace/$displayPath",
          "display_path":"$displayPath",
          "project_path":"/workspace",
          "language":"kotlin",
          "content":"$content",
          "size":${content.length},
          "truncated":false
        }
        """.trimIndent()

    private fun threadPayload(
        sessionKey: String,
        messageId: String,
        before: String?,
        hasMoreBefore: Boolean = before != null,
    ): String = """
        {
          "schemaVersion": 1,
          "sessionKey": "$sessionKey",
          "messages": [
            {"id":"$messageId","role":"user","content":"$messageId","createdAt":1}
          ],
          "page": {
            "before_cursor": ${before?.let { "\"$it\"" } ?: "null"},
            "has_more_before": $hasMoreBefore,
            "user_message_offset": 0
          }
        }
    """.trimIndent()

    private data class TestCredentials(private val url: String) : TransportCredentials {
        override fun currentWebSocketUrl(): String = url
        override suspend fun reauthenticateWebSocketUrl(): String = url
        override fun maxFrameBytes(): Int? = null
    }
}
