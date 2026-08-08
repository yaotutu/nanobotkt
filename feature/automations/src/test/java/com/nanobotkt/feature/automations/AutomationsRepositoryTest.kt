package com.nanobotkt.feature.automations

import com.nanobotkt.core.model.AutomationSchedule
import com.nanobotkt.core.model.AutomationUpdatePayload
import com.nanobotkt.core.network.AuthContext
import com.nanobotkt.core.network.GatewayApiClient
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
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
import java.net.URLDecoder
import java.nio.charset.StandardCharsets
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger

class AutomationsRepositoryTest {
    private lateinit var server: MockWebServer

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
    fun everyActionUsesItsEndpointAndEncodedIdQuery() = runBlocking {
        val actions = listOf("enable", "disable", "delete", "run")
        val id = "job 1/2"
        server.dispatcher = object : Dispatcher() {
            override fun dispatch(request: RecordedRequest): MockResponse = when {
                request.requestUrl?.encodedPath in actions.map { "/api/webui/automations/$it" } ->
                    jsonResponse(jobPayload(name = "action"))
                request.requestUrl?.encodedPath == "/api/webui/automations" ->
                    jsonResponse(jobPayload(name = "refresh"))
                else -> MockResponse().setResponseCode(404)
            }
        }

        val repository = repository()
        actions.forEach { action ->
            repository.action(action, id)

            val request = server.takeRequest(2, TimeUnit.SECONDS)
                ?: error("$action request was not received")
            assertEquals("GET", request.method)
            assertEquals("application/json", request.getHeader("Accept"))
            assertEquals("", request.body.readUtf8())
            assertEquals("/api/webui/automations/$action", request.requestUrl?.encodedPath)
            assertEquals(id, request.requestUrl?.queryParameter("id"))
            // id 同时包含空格和斜杠；它们必须留在 query 中并完成 URL 编码，不能改写成路径。
            assertTrue(request.requestUrl?.encodedQuery.orEmpty().contains("job%201%2F2"))

            val refresh = server.takeRequest(2, TimeUnit.SECONDS)
                ?: error("$action refresh request was not received")
            assertEquals("/api/webui/automations", refresh.requestUrl?.encodedPath)
        }
    }

    @Test
    fun cancelledActionClearsPendingAndReleasesAdmissionForRetry() = runBlocking {
        val firstActionStarted = CountDownLatch(1)
        val releaseFirstAction = CountDownLatch(1)
        val retryActionStarted = CountDownLatch(1)
        val actionCalls = AtomicInteger(0)
        server.dispatcher = object : Dispatcher() {
            override fun dispatch(request: RecordedRequest): MockResponse = when {
                request.requestUrl?.encodedPath == "/api/webui/automations/run" -> {
                    if (actionCalls.incrementAndGet() == 1) {
                        firstActionStarted.countDown()
                        assertTrue(releaseFirstAction.await(2, TimeUnit.SECONDS))
                        jsonResponse(jobPayload(name = "cancelled-action"))
                    } else {
                        retryActionStarted.countDown()
                        jsonResponse(jobPayload(name = "retried-action"))
                    }
                }
                request.requestUrl?.encodedPath == "/api/webui/automations" ->
                    jsonResponse(jobPayload(name = "retried-refresh"))
                else -> MockResponse().setResponseCode(404)
            }
        }

        val repository = repository()
        val first = async(Dispatchers.IO) { repository.action("run", "job-1") }
        assertTrue(firstActionStarted.await(2, TimeUnit.SECONDS))
        assertTrue(repository.state.value.pending.contains("job-1"))

        // 取消发生在 action 已进入 pending 且网络请求在途时；释放服务端闸门后等待 finally 完成清理。
        first.cancel()
        releaseFirstAction.countDown()
        try {
            withTimeout(2_000) { first.await() }
        } catch (_: kotlinx.coroutines.CancellationException) {
            // 取消本身是预期结果，关键断言是 pending 和 admission 都已清理。
        }

        assertTrue(repository.state.value.pending.isEmpty())
        val retry = async(Dispatchers.IO) { repository.action("run", "job-1") }
        assertTrue(retryActionStarted.await(2, TimeUnit.SECONDS))
        withTimeout(2_000) { retry.await() }

        // 相同 id 能再次发出请求，证明 inFlight admission 没有被取消的旧 action 卡住。
        assertEquals(2, actionCalls.get())
        assertEquals("retried-refresh", repository.state.value.payload?.jobs?.single()?.name)
        assertTrue(repository.state.value.pending.isEmpty())
    }

    @Test
    fun actionUpdatesStateThenRefreshesAndClearsPending() = runBlocking {
        val requests = mutableListOf<String>()
        server.dispatcher = object : Dispatcher() {
            override fun dispatch(request: RecordedRequest): MockResponse {
                requests += request.path.orEmpty()
                return when {
                    request.path?.startsWith("/api/webui/automations/run") == true ->
                        jsonResponse(jobPayload(name = "from-action"))
                    request.path == "/api/webui/automations" ->
                        jsonResponse(jobPayload(name = "from-refresh"))
                    else -> MockResponse().setResponseCode(404)
                }
            }
        }

        val repository = repository()
        repository.action("run", "job-1")

        assertEquals(listOf("/api/webui/automations/run?id=job-1", "/api/webui/automations"), requests)
        assertEquals("from-refresh", repository.state.value.payload?.jobs?.single()?.name)
        assertFalse(repository.state.value.pending.contains("job-1"))
        assertEquals(null, repository.state.value.error)
    }

    @Test
    fun resetIgnoresLateRefreshSuccess() = runBlocking {
        val responseStarted = CountDownLatch(1)
        val releaseResponse = CountDownLatch(1)
        server.dispatcher = object : Dispatcher() {
            override fun dispatch(request: RecordedRequest): MockResponse {
                responseStarted.countDown()
                releaseResponse.await(2, TimeUnit.SECONDS)
                return jsonResponse(jobPayload(name = "late-refresh"))
            }
        }

        val repository = repository()
        val refresh = async(Dispatchers.IO) { repository.refresh() }
        assertTrue(responseStarted.await(2, TimeUnit.SECONDS))

        repository.reset()
        assertEquals(AutomationsUiState(), repository.state.value)

        // reset 后旧 refresh 仍可能完成网络和 JSON 解析，但不能把旧账号的 payload 写回新会话。
        releaseResponse.countDown()
        withTimeout(2_000) { refresh.await() }

        assertEquals(AutomationsUiState(), repository.state.value)
    }

    @Test
    fun resetIgnoresLateRefreshFailure() = runBlocking {
        val responseStarted = CountDownLatch(1)
        val releaseResponse = CountDownLatch(1)
        server.dispatcher = object : Dispatcher() {
            override fun dispatch(request: RecordedRequest): MockResponse {
                responseStarted.countDown()
                releaseResponse.await(2, TimeUnit.SECONDS)
                return MockResponse().setResponseCode(503).setBody("late refresh failure")
            }
        }

        val repository = repository()
        val refresh = async(Dispatchers.IO) { repository.refresh() }
        assertTrue(responseStarted.await(2, TimeUnit.SECONDS))

        repository.reset()
        releaseResponse.countDown()
        withTimeout(2_000) { refresh.await() }

        // 迟到的 HTTP 错误同样不能恢复 error 或 loading；reset 后应保持全新会话的空状态。
        assertEquals(AutomationsUiState(), repository.state.value)
    }

    @Test
    fun resetIgnoresLateActionAndReleasesAdmissionForRetry() = runBlocking {
        val firstActionStarted = CountDownLatch(1)
        val releaseFirstAction = CountDownLatch(1)
        val retryActionStarted = CountDownLatch(1)
        val releaseRetryAction = CountDownLatch(1)
        val actionCalls = AtomicInteger(0)
        server.dispatcher = object : Dispatcher() {
            override fun dispatch(request: RecordedRequest): MockResponse = when {
                request.path?.startsWith("/api/webui/automations/run") == true -> {
                    if (actionCalls.incrementAndGet() == 1) {
                        firstActionStarted.countDown()
                        releaseFirstAction.await(2, TimeUnit.SECONDS)
                        jsonResponse(jobPayload(name = "late-action"))
                    } else {
                        retryActionStarted.countDown()
                        releaseRetryAction.await(2, TimeUnit.SECONDS)
                        jsonResponse(jobPayload(name = "retry-action"))
                    }
                }
                request.path == "/api/webui/automations" ->
                    jsonResponse(jobPayload(name = "retry-refresh"))
                else -> MockResponse().setResponseCode(404)
            }
        }

        val repository = repository()
        val first = async(Dispatchers.IO) { repository.action("run", "job-1") }
        assertTrue(firstActionStarted.await(2, TimeUnit.SECONDS))

        repository.reset()
        assertEquals(AutomationsUiState(), repository.state.value)

        // UNDISPATCHED 让重试先完成 inFlight admission，再等待旧请求持有的串行锁；
        // 这样可以验证旧请求 finally 不会删除新会话刚建立的 pending/inFlight。
        val retry = async(Dispatchers.IO, start = CoroutineStart.UNDISPATCHED) {
            repository.action("run", "job-1")
        }
        releaseFirstAction.countDown()
        assertTrue(retryActionStarted.await(2, TimeUnit.SECONDS))

        // 旧 action 的成功响应已经迟到，但不能恢复 payload，也不能清掉新 action 的 pending。
        assertEquals(null, repository.state.value.payload)
        assertTrue(repository.state.value.pending.contains("job-1"))
        assertEquals(null, repository.state.value.error)

        releaseRetryAction.countDown()
        withTimeout(2_000) { first.await() }
        withTimeout(2_000) { retry.await() }

        assertEquals(2, actionCalls.get())
        assertEquals("retry-refresh", repository.state.value.payload?.jobs?.single()?.name)
        assertTrue(repository.state.value.pending.isEmpty())
        assertEquals(null, repository.state.value.error)
    }

    @Test
    fun refreshFailureKeepsExistingPayloadAndExposesError() = runBlocking {
        val requestCount = AtomicInteger(0)
        server.dispatcher = object : Dispatcher() {
            override fun dispatch(request: RecordedRequest): MockResponse =
                if (requestCount.incrementAndGet() == 1) {
                    jsonResponse(jobPayload(name = "existing"))
                } else {
                    MockResponse().setResponseCode(503).setBody("temporarily unavailable")
                }
        }

        val repository = repository()
        repository.refresh()
        repository.refresh()

        // 刷新失败时保留最后一次成功列表，页面仍可显示旧数据，同时把错误交给 UI 展示。
        assertEquals("existing", repository.state.value.payload?.jobs?.single()?.name)
        assertFalse(repository.state.value.loading)
        assertTrue(repository.state.value.error.orEmpty().isNotBlank())
    }

    @Test
    fun actionFailureClearsPendingAndExposesError() = runBlocking {
        server.dispatcher = object : Dispatcher() {
            override fun dispatch(request: RecordedRequest): MockResponse =
                MockResponse().setResponseCode(500).setBody("automation failed")
        }

        val repository = repository()
        repository.action("run", "job-1")

        assertTrue(repository.state.value.pending.isEmpty())
        assertTrue(repository.state.value.error.orEmpty().isNotBlank())
    }

    @Test
    fun actionHttpFailureClearsAdmissionAndAllowsRetry() = runBlocking {
        val actionCalls = AtomicInteger(0)
        server.dispatcher = object : Dispatcher() {
            override fun dispatch(request: RecordedRequest): MockResponse = when {
                request.path?.startsWith("/api/webui/automations/run") == true ->
                    if (actionCalls.incrementAndGet() == 1) {
                        MockResponse().setResponseCode(500).setBody("first action failed")
                    } else {
                        jsonResponse(jobPayload(name = "retried-action"))
                    }
                request.path == "/api/webui/automations" ->
                    jsonResponse(jobPayload(name = "retried-refresh"))
                else -> MockResponse().setResponseCode(404)
            }
        }

        val repository = repository()
        repository.action("run", "job-1")
        // 第一次 HTTP 失败后，下一次相同操作必须能够重新进入 inFlight 并真正发出请求。
        repository.action("run", "job-1")

        assertEquals(2, actionCalls.get())
        assertEquals("retried-refresh", repository.state.value.payload?.jobs?.single()?.name)
        assertTrue(repository.state.value.pending.isEmpty())
        assertEquals(null, repository.state.value.error)
    }

    @Test
    fun updateHttpFailureClearsAdmissionAndAllowsRetry() = runBlocking {
        val updateCalls = AtomicInteger(0)
        server.dispatcher = object : Dispatcher() {
            override fun dispatch(request: RecordedRequest): MockResponse = when {
                request.path?.startsWith("/api/webui/automations/update") == true ->
                    if (updateCalls.incrementAndGet() == 1) {
                        MockResponse().setResponseCode(500).setBody("first update failed")
                    } else {
                        jsonResponse(jobPayload(name = "retried-update"))
                    }
                request.path == "/api/webui/automations" ->
                    jsonResponse(jobPayload(name = "retried-update-refresh"))
                else -> MockResponse().setResponseCode(404)
            }
        }

        val repository = repository()
        val values = com.nanobotkt.core.model.AutomationUpdatePayload(name = "新名称", message = "hello")
        repository.update("job-1", values)
        // update 失败后也必须释放 admission；否则后续保存会被错误地当成重复操作丢弃。
        repository.update("job-1", values)

        assertEquals(2, updateCalls.get())
        assertEquals("retried-update-refresh", repository.state.value.payload?.jobs?.single()?.name)
        assertTrue(repository.state.value.pending.isEmpty())
        assertEquals(null, repository.state.value.error)
    }

    @Test
    fun duplicateActionForSameJobIsDroppedBeforeMutex() = runBlocking {
        val actionStarted = CountDownLatch(1)
        val releaseAction = CountDownLatch(1)
        val actionCount = AtomicInteger(0)
        server.dispatcher = object : Dispatcher() {
            override fun dispatch(request: RecordedRequest): MockResponse = when {
                request.path?.startsWith("/api/webui/automations/run") == true -> {
                    actionCount.incrementAndGet()
                    actionStarted.countDown()
                    releaseAction.await(2, TimeUnit.SECONDS)
                    jsonResponse(jobPayload())
                }
                request.path == "/api/webui/automations" -> jsonResponse(jobPayload())
                else -> MockResponse().setResponseCode(404)
            }
        }

        val repository = repository()
        val first = async(Dispatchers.IO) { repository.action("run", "job-1") }
        assertTrue(actionStarted.await(2, TimeUnit.SECONDS))
        val second = async(Dispatchers.IO) { repository.action("run", "job-1") }
        withTimeout(2_000) { second.await() }
        releaseAction.countDown()
        withTimeout(2_000) { first.await() }

        assertEquals(1, actionCount.get())
        assertTrue(repository.state.value.pending.isEmpty())
    }

    @Test
    fun updateSendsDecodedExactPayloadForEveryCronAndAtAndEncodesId() = runBlocking {
        val id = "job 1/2"
        val expectedPayloads = listOf(
            AutomationUpdatePayload(
                name = "每 12 小时",
                message = "问每日测验",
                schedule = AutomationSchedule(kind = "every", everyMs = 43_200_000L),
            ),
            AutomationUpdatePayload(
                name = "每日九点",
                message = "Ask 今日 quiz",
                schedule = AutomationSchedule(
                    kind = "cron",
                    expr = "0 9 * * *",
                    tz = "Asia/Shanghai",
                ),
            ),
            AutomationUpdatePayload(
                name = "一次性提醒",
                message = "提醒完成发布",
                schedule = AutomationSchedule(kind = "at", atMs = 1_735_689_600_000L),
            ),
        )
        val receivedHeaders = mutableListOf<String>()
        var refreshCount = 0
        server.dispatcher = object : Dispatcher() {
            override fun dispatch(request: RecordedRequest): MockResponse {
                if (request.requestUrl?.encodedPath == "/api/webui/automations/update") {
                    assertEquals(id, request.requestUrl?.queryParameter("id"))
                    // URL 查询参数必须保持编码，尤其不能把 id 中的斜杠当成路径分隔符。
                    assertTrue(request.requestUrl?.encodedQuery.orEmpty().contains("%2F"))
                    receivedHeaders += request.getHeader("X-Nanobot-Automation-Values").orEmpty()
                    return jsonResponse(jobPayload(name = "updated"))
                }
                if (request.requestUrl?.encodedPath == "/api/webui/automations") {
                    refreshCount += 1
                    return jsonResponse(jobPayload(name = "refreshed"))
                }
                return MockResponse().setResponseCode(404)
            }
        }

        val repository = repository()
        expectedPayloads.forEach { values -> repository.update(id, values) }

        assertEquals(expectedPayloads.size, refreshCount)
        assertEquals(expectedPayloads.size, receivedHeaders.size)
        expectedPayloads.zip(receivedHeaders).forEach { (expected, encodedHeader) ->
            // 服务端约定 header 是 encodeURIComponent(JSON.stringify(values))；先 URL 解码，
            // 再按 JSON 结构精确比较，避免只检查包含百分号而漏掉字段、null 或 schedule 类型错误。
            // 普通空格必须是 %20，而不是 URLEncoder 默认的 +；服务端使用 unquote()，
            // 如果这里出现 +，保存后的名称或消息会被错误地持久化为加号。
            assertFalse(encodedHeader.contains("+"))
            val decodedPayload = URLDecoder.decode(encodedHeader, StandardCharsets.UTF_8.name())
            assertEquals(
                Json { explicitNulls = false }.encodeToJsonElement(
                    AutomationUpdatePayload.serializer(),
                    expected,
                ),
                Json.parseToJsonElement(decodedPayload),
            )
        }
        assertEquals("refreshed", repository.state.value.payload?.jobs?.single()?.name)
        assertTrue(repository.state.value.pending.isEmpty())
    }

    private fun repository(): DefaultAutomationsRepository = DefaultAutomationsRepository(
        GatewayApiClient(
            OkHttpClient(),
            Json { ignoreUnknownKeys = true; explicitNulls = false },
            object : AuthContext {
                override val baseUrl: String = server.url("/").toString()
                override val apiToken: String? = null
            },
        ),
    )

    private fun jobPayload(name: String = "job"): String =
        """{"jobs":[{"id":"job-1","name":"$name","enabled":true,"protected":false,"delete_after_run":false,"created_at_ms":1785980000000,"updated_at_ms":1785981000000,"kind":"scheduled","schedule":{"kind":"every","every_ms":60000},"payload":{"message":"hello","kind":"message"},"state":{"next_run_at_ms":1785982000000,"last_run_at_ms":1785980005000,"last_status":"ok","last_error":null,"pending":false,"run_history":[{"run_at_ms":1785980005000,"status":"ok","duration_ms":250}]},"origin":{"session_key":"webui:chat-1","channel":"websocket","chat_id":"chat-1","title":"Chat","preview":"hello"}}]}"""

    private fun jsonResponse(body: String): MockResponse = MockResponse()
        .setResponseCode(200)
        .setHeader("Content-Type", "application/json; charset=utf-8")
        .setBody(body)
}
