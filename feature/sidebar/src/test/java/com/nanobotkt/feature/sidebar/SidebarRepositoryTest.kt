package com.nanobotkt.feature.sidebar

import com.nanobotkt.core.model.InboundEvent
import com.nanobotkt.core.model.SidebarStatePayload
import com.nanobotkt.core.network.ApiCredentialProvider
import com.nanobotkt.core.network.GatewayEndpointProvider
import com.nanobotkt.core.network.GatewayApiClient
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import kotlinx.serialization.json.Json
import okhttp3.OkHttpClient
import okhttp3.mockwebserver.Dispatcher
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import okhttp3.mockwebserver.RecordedRequest
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import java.util.Collections
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger

class SidebarRepositoryTest {
    private lateinit var server: MockWebServer
    private val json = Json { ignoreUnknownKeys = true; explicitNulls = false }

    @Before
    fun setUp() {
        server = MockWebServer()
        server.start()
    }

    @After
    fun tearDown() {
        server.shutdown()
    }

    @Test
    fun newerRefreshCannotBeOverwrittenByOlderResponse() = runBlocking {
        val firstSessionsStarted = CountDownLatch(1)
        val sessionRequestCount = AtomicInteger(0)
        val secondSessionsStarted = CountDownLatch(1)

        server.dispatcher = object : Dispatcher() {
            override fun dispatch(request: RecordedRequest): MockResponse = when (request.path) {
                "/api/sessions" -> {
                    if (sessionRequestCount.incrementAndGet() == 1) {
                        firstSessionsStarted.countDown()
                        jsonResponse("""{"sessions":[{"key":"webui:old","title":"Old"}]}""")
                            .setBodyDelay(500, TimeUnit.MILLISECONDS)
                    } else {
                        secondSessionsStarted.countDown()
                        jsonResponse("""{"sessions":[{"key":"webui:new","title":"New"}]}""")
                    }
                }
                "/api/webui/sidebar-state" -> jsonResponse("""{"schema_version":1}""")
                else -> MockResponse().setResponseCode(404)
            }
        }

        val repository = newRepository()

        val first = async(Dispatchers.IO) { repository.refresh() }
        assertTrue(firstSessionsStarted.await(2, TimeUnit.SECONDS))

        val second = async(Dispatchers.IO) { repository.refresh() }
        assertTrue(secondSessionsStarted.await(2, TimeUnit.SECONDS))
        withTimeout(2_000) { second.await() }

        // 第二次请求已经成功后，延迟的第一次响应才返回；最终状态仍必须来自第二代请求。
        withTimeout(2_000) { first.await() }

        assertEquals(listOf("webui:new"), repository.state.value.sessions.map { it.key })
    }

    @Test
    fun mutationFailureClearsPendingKeyAndExposesError() = runBlocking {
        val key = "webui:folder /中文"
        server.dispatcher = object : Dispatcher() {
            override fun dispatch(request: RecordedRequest): MockResponse = when {
                request.path?.substringBefore('?') == "/api/webui/sidebar-state/update" -> MockResponse()
                    .setResponseCode(500)
                    .setBody("forced sidebar update failure")
                else -> MockResponse().setResponseCode(404)
            }
        }

        val repository = newRepository()
        repository.togglePinned(key)

        // 请求失败时，乐观操作不能把 pending 标记遗留在状态中；同时要把
        // 服务端返回的可诊断错误暴露给 UI，而不是吞掉异常后假装成功。
        assertTrue(repository.state.value.pendingKeys.isEmpty())
        assertEquals("forced sidebar update failure", repository.state.value.error)
        assertEquals(SidebarStatePayload(), repository.state.value.sidebar)

        val request = server.takeRequest(2, TimeUnit.SECONDS)
        assertEquals("/api/webui/sidebar-state/update", request?.path?.substringBefore('?'))
        val encodedQuery = request?.requestUrl?.encodedQuery.orEmpty()
        val proposedState = json.decodeFromString(
            SidebarStatePayload.serializer(),
            request?.requestUrl?.queryParameter("state").orEmpty(),
        )
        assertEquals(listOf(key), proposedState.pinnedKeys)
        // state 是通过 query 发送的 JSON；斜杠不能被当作 query/path 分隔符。
        assertTrue(encodedQuery.contains("%2F"))
    }

    @Test
    fun resetInvalidatesLateMutationSuccessWithoutRefreshingOrRestoringState() = runBlocking {
        val key = "webui:late-mutation"
        val requestStarted = CountDownLatch(1)
        val releaseResponse = CountDownLatch(1)
        server.dispatcher = object : Dispatcher() {
            override fun dispatch(request: RecordedRequest): MockResponse {
                if (request.path?.substringBefore('?') == "/api/webui/sidebar-state/update") {
                    requestStarted.countDown()
                    assertTrue(releaseResponse.await(2, TimeUnit.SECONDS))
                    return jsonResponse("{\"schema_version\":1}")
                }
                return MockResponse().setResponseCode(404)
            }
        }

        val repository = newRepository()
        val mutation = async(Dispatchers.IO) { repository.togglePinned(key) }
        assertTrue(requestStarted.await(2, TimeUnit.SECONDS))

        // reset 代表退出当前账号；旧 mutation 完成后既不能恢复 pending/error，
        // 也不能因为成功而再次触发 refresh 填充旧账号的 Sidebar。
        repository.reset()
        releaseResponse.countDown()
        withTimeout(2_000) { mutation.await() }

        assertEquals(SidebarUiState(), repository.state.value)
        assertEquals("/api/webui/sidebar-state/update", server.takeRequest(2, TimeUnit.SECONDS)?.path?.substringBefore('?'))
        assertEquals(null, server.takeRequest(200, TimeUnit.MILLISECONDS))
    }

    @Test
    fun resetInvalidatesLateDeleteFailureWithoutWritingErrorOrClearingNewPendingState() = runBlocking {
        val key = "webui:late-delete"
        val requestStarted = CountDownLatch(1)
        val releaseResponse = CountDownLatch(1)
        server.dispatcher = object : Dispatcher() {
            override fun dispatch(request: RecordedRequest): MockResponse {
                if (request.path?.substringBefore('?')?.endsWith("/delete") == true) {
                    requestStarted.countDown()
                    assertTrue(releaseResponse.await(2, TimeUnit.SECONDS))
                    return MockResponse().setResponseCode(500).setBody("late delete failure")
                }
                return MockResponse().setResponseCode(404)
            }
        }

        val repository = newRepository()
        val deletion = async(Dispatchers.IO) { repository.deleteSession(key) }
        assertTrue(requestStarted.await(2, TimeUnit.SECONDS))

        repository.reset()
        releaseResponse.countDown()
        withTimeout(2_000) { deletion.await() }

        // 旧 delete 的异常和 finally 都必须被 generation guard 丢弃，不能污染
        // reset 后的新会话状态，也不能误删新会话后来设置的 pending 标记。
        assertEquals(SidebarUiState(), repository.state.value)
        assertTrue(server.takeRequest(2, TimeUnit.SECONDS)?.path?.substringBefore('?')?.endsWith("/delete") == true)
        assertEquals(null, server.takeRequest(200, TimeUnit.MILLISECONDS))
    }

    @Test
    fun deleteSessionEncodesPathAndDeleteAutomationsQuery() = runBlocking {
        val key = "webui:folder /中文?"
        val requests = Collections.synchronizedList(mutableListOf<RecordedRequest>())
        val emptySidebar = SidebarStatePayload()
        server.dispatcher = object : Dispatcher() {
            override fun dispatch(request: RecordedRequest): MockResponse {
                requests += request
                return when (request.path?.substringBefore('?')) {
                    "/api/sessions/webui%3Afolder%20%2F%E4%B8%AD%E6%96%87%3F/delete" ->
                        jsonResponse("""{"deleted":true}""")
                    "/api/webui/sidebar-state/update" -> jsonResponse(
                        json.encodeToString(SidebarStatePayload.serializer(), emptySidebar),
                    )
                    "/api/sessions" -> jsonResponse("""{"sessions":[]}""")
                    "/api/webui/sidebar-state" -> jsonResponse(
                        json.encodeToString(SidebarStatePayload.serializer(), emptySidebar),
                    )
                    else -> MockResponse().setResponseCode(404)
                }
            }
        }

        val repository = newRepository()
        assertTrue(repository.deleteSession(key, deleteAutomations = true))

        val deleteRequest = requests.first {
            it.path?.substringBefore('?')?.endsWith("/delete") == true
        }
        assertEquals(
            "/api/sessions/webui%3Afolder%20%2F%E4%B8%AD%E6%96%87%3F/delete",
            deleteRequest.path?.substringBefore('?'),
        )
        assertEquals("true", deleteRequest.requestUrl?.queryParameter("delete_automations"))
        assertTrue(repository.state.value.pendingKeys.isEmpty())
    }

    @Test
    fun togglePinnedAndArchivedUsesOptimisticStateAndCanonicalResponseWithoutExtraRefresh() = runBlocking {
        val key = "webui:folder /中文"
        val requestStarted = CountDownLatch(1)
        val releaseResponse = CountDownLatch(1)
        val updateRequests = Collections.synchronizedList(mutableListOf<RecordedRequest>())
        server.dispatcher = object : Dispatcher() {
            override fun dispatch(request: RecordedRequest): MockResponse = when {
                request.path?.substringBefore('?') == "/api/webui/sidebar-state/update" -> {
                    updateRequests += request
                    requestStarted.countDown()
                    releaseResponse.await(2, TimeUnit.SECONDS)
                    // 服务端会返回写入后的规范状态；直接回显 proposal 可以验证 Repository
                    // 不再追加两次 GET，也不会用旧列表覆盖已经生效的置顶/归档结果。
                    val proposal = request.requestUrl?.queryParameter("state").orEmpty()
                    jsonResponse(proposal)
                }
                else -> MockResponse().setResponseCode(404)
            }
        }

        val repository = newRepository()
        val pinJob = async(Dispatchers.IO) { repository.togglePinned(key) }
        assertTrue(requestStarted.await(2, TimeUnit.SECONDS))

        // 网络响应尚未返回时，行已经移动到置顶分组并进入 pending，提供即时反馈且禁止重复操作。
        assertEquals(listOf(key), repository.state.value.sidebar.pinnedKeys)
        assertTrue(key in repository.state.value.pendingKeys)
        releaseResponse.countDown()
        withTimeout(2_000) { pinJob.await() }

        repository.toggleArchived(key)
        assertEquals(listOf(key), repository.state.value.sidebar.archivedKeys)
        assertTrue(repository.state.value.sidebar.pinnedKeys.isEmpty())
        assertFalse(repository.state.value.pendingKeys.contains(key))

        assertEquals(2, updateRequests.size)
        val pinnedProposal = json.decodeFromString(
            SidebarStatePayload.serializer(),
            updateRequests[0].requestUrl?.queryParameter("state").orEmpty(),
        )
        val archivedProposal = json.decodeFromString(
            SidebarStatePayload.serializer(),
            updateRequests[1].requestUrl?.queryParameter("state").orEmpty(),
        )
        assertEquals(listOf(key), pinnedProposal.pinnedKeys)
        assertTrue(pinnedProposal.archivedKeys.isEmpty())
        assertEquals(listOf(key), archivedProposal.archivedKeys)
        assertTrue(archivedProposal.pinnedKeys.isEmpty())
        // 两次 mutation 各自只发一次 update；旧实现额外触发的 sessions/sidebar GET 已被移除。
        assertEquals(2, server.requestCount)
    }

    @Test
    fun realtimeActivityRemainsAuthoritativeAcrossStaleHttpRefreshes() = runBlocking {
        val events = MutableSharedFlow<InboundEvent>(extraBufferCapacity = 8)
        val serverReportsRunning = AtomicBoolean(false)
        server.dispatcher = object : Dispatcher() {
            override fun dispatch(request: RecordedRequest): MockResponse = when (request.path) {
                "/api/sessions" -> {
                    val runStartedAt = if (serverReportsRunning.get()) ",\"run_started_at\":123" else ""
                    jsonResponse(
                        """{"sessions":[{"key":"webui:other-chat","chat_id":"other-chat"$runStartedAt}]}""",
                    )
                }
                "/api/webui/sidebar-state" -> jsonResponse("""{"schema_version":1}""")
                else -> MockResponse().setResponseCode(404)
            }
        }
        val repository = newRepository(events)
        withTimeout(2_000) { events.subscriptionCount.first { it > 0 } }

        events.emit(InboundEvent.MessageAccepted(chatId = "other-chat", turnId = "turn-1"))
        awaitSidebarState(repository) { "other-chat" in it.runningChatIds }
        // 开始事件已经明确 running，即使紧随其后的 sessions 快照尚未写入 run_started_at，
        // refresh 也不能让列表 spinner 闪退。
        repository.refresh()
        assertTrue("other-chat" in repository.state.value.runningChatIds)

        serverReportsRunning.set(true)
        // 服务端追上 running 后，实时覆盖应自动释放；否则同一会话未来由其他客户端启动的新 turn
        // 会被旧覆盖永久压制，手动 refresh 也无法重新显示运行中。
        repository.refresh()
        assertTrue("other-chat" in repository.state.value.runningChatIds)

        events.emit(InboundEvent.TurnEnd(chatId = "other-chat", turnId = "turn-1"))
        awaitSidebarState(repository) {
            "other-chat" !in it.runningChatIds && "other-chat" in it.unreadChatIds
        }
        // 结束事件已经明确 idle；服务端短暂滞留的 run_started_at 不能复活运行状态，未读也必须保留。
        repository.refresh()
        assertFalse("other-chat" in repository.state.value.runningChatIds)
        assertTrue("other-chat" in repository.state.value.unreadChatIds)

        serverReportsRunning.set(false)
        repository.refresh()
        assertFalse("other-chat" in repository.state.value.runningChatIds)

        // idle 快照追上并释放覆盖后，后续仅由 HTTP 暴露的外部运行状态必须可以再次生效。
        serverReportsRunning.set(true)
        repository.refresh()
        assertTrue("other-chat" in repository.state.value.runningChatIds)
    }

    @Test
    fun broadcastSessionUpdatedRefreshesOtherChatsAndMarksThreadActivityUnread() = runBlocking {
        val events = MutableSharedFlow<InboundEvent>(extraBufferCapacity = 8)
        server.dispatcher = object : Dispatcher() {
            override fun dispatch(request: RecordedRequest): MockResponse = when (request.path) {
                "/api/sessions" -> jsonResponse(
                    """{"sessions":[{"key":"webui:remote-chat","chat_id":"remote-chat","updated_at":"2026-08-18T12:00:00"}]}""",
                )
                "/api/webui/sidebar-state" -> jsonResponse("""{"schema_version":1}""")
                else -> MockResponse().setResponseCode(404)
            }
        }
        val repository = newRepository(events)
        withTimeout(2_000) { events.subscriptionCount.first { it > 0 } }

        // session_updated 是服务端面向所有 WebUI 连接的广播；即使当前 socket 没有 attach 目标会话，
        // thread 更新也必须触发列表刷新并为非当前会话点亮未读。
        events.emit(InboundEvent.SessionUpdated(chatId = "remote-chat", scope = "thread"))
        awaitSidebarState(repository) {
            it.sessions.any { session -> session.chatId == "remote-chat" } &&
                "remote-chat" in it.unreadChatIds
        }

        repository.markRead("remote-chat")
        events.emit(InboundEvent.SessionUpdated(chatId = "remote-chat", scope = "metadata"))
        // metadata 只要求刷新标题/配置，不能把当前会话错误地重新标成未读。
        kotlinx.coroutines.delay(300)
        assertFalse("remote-chat" in repository.state.value.unreadChatIds)
    }

    @Test
    fun activityEventsDriveRunningAndUnreadAndMarkReadClearsUnread() = runBlocking {
        val events = MutableSharedFlow<InboundEvent>(extraBufferCapacity = 8)
        server.dispatcher = object : Dispatcher() {
            override fun dispatch(request: RecordedRequest): MockResponse = when (request.path) {
                "/api/sessions" -> jsonResponse("""{"sessions":[]}""")
                "/api/webui/sidebar-state" -> jsonResponse("""{"schema_version":1}""")
                else -> MockResponse().setResponseCode(404)
            }
        }
        val repository = newRepository(events)
        withTimeout(2_000) { events.subscriptionCount.first { it > 0 } }

        events.emit(InboundEvent.MessageAccepted(chatId = "other-chat", turnId = "turn-1"))
        awaitSidebarState(repository) { "other-chat" in it.runningChatIds }
        assertFalse("other-chat" in repository.state.value.unreadChatIds)

        events.emit(InboundEvent.TurnEnd(chatId = "other-chat", turnId = "turn-1"))
        awaitSidebarState(repository) {
            "other-chat" !in it.runningChatIds && "other-chat" in it.unreadChatIds
        }

        repository.markRead("other-chat")
        assertFalse("other-chat" in repository.state.value.unreadChatIds)

        // 当前选中的会话即使完成 turn 也不能重新点亮未读；选择判定与事件更新在同一锁内完成。
        events.emit(InboundEvent.GoalStatus(chatId = "other-chat", status = "running", turnId = "turn-2"))
        awaitSidebarState(repository) { "other-chat" in it.runningChatIds }
        events.emit(InboundEvent.GoalStatus(chatId = "other-chat", status = "idle", turnId = "turn-2"))
        awaitSidebarState(repository) { "other-chat" !in it.runningChatIds }
        assertFalse("other-chat" in repository.state.value.unreadChatIds)

        repository.reset()
        assertTrue(repository.state.value.runningChatIds.isEmpty())
        assertTrue(repository.state.value.unreadChatIds.isEmpty())
    }

    private suspend fun awaitSidebarState(
        repository: DefaultSidebarRepository,
        predicate: (SidebarUiState) -> Boolean,
    ): SidebarUiState = withTimeout(2_000) { repository.state.first(predicate) }

    private fun newRepository(
        activityEvents: Flow<InboundEvent> = kotlinx.coroutines.flow.emptyFlow(),
    ): DefaultSidebarRepository = DefaultSidebarRepository(
        GatewayApiClient(
            OkHttpClient(),
            json,
            object : GatewayEndpointProvider {
                override val baseUrl: String = server.url("/").toString()
            },
            object : ApiCredentialProvider {
                override suspend fun tokenForRequest(): String = "test-api-token"
                override suspend fun tokenAfterUnauthorized(rejectedToken: String): String = "test-api-token"
            },
        ),
        activityEvents,
    )

    private fun jsonResponse(body: String): MockResponse = MockResponse()
        .setResponseCode(200)
        .setHeader("Content-Type", "application/json; charset=utf-8")
        .setBody(body)
}
