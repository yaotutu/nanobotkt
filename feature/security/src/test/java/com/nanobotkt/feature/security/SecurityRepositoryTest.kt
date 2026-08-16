package com.nanobotkt.feature.security

import com.nanobotkt.core.network.ApiCredentialProvider
import com.nanobotkt.core.network.GatewayEndpointProvider
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
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger

class SecurityRepositoryTest {
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
    fun denyUsesDenyEndpointAndEncodesCodeQueryThenRefreshes() = runBlocking {
        val code = "ABC/123 +x"
        val paths = mutableListOf<String>()
        server.dispatcher = object : Dispatcher() {
            override fun dispatch(request: RecordedRequest): MockResponse {
                paths += request.requestUrl?.encodedPath.orEmpty()
                return when (request.requestUrl?.encodedPath) {
                    "/api/settings/pairing/deny" -> {
                        assertEquals(code, request.requestUrl?.queryParameter("code"))
                        // pairing code 是 query 参数；保留编码可避免斜杠、加号和空格改变语义。
                        val encodedQuery = request.requestUrl?.encodedQuery.orEmpty()
                        assertTrue(encodedQuery.contains("%2F"))
                        assertTrue(encodedQuery.contains("%2B"))
                        jsonResponse("{\"requests\":[],\"last_action\":{\"ok\":true,\"action\":\"deny\",\"message\":\"ok\",\"code\":\"ABC/123 +x\"}}")
                    }
                    "/api/settings/pairing" -> jsonResponse("{\"requests\":[]}")
                    else -> MockResponse().setResponseCode(404)
                }
            }
        }

        val repository = repository()
        repository.action("deny", code)

        assertEquals(
            listOf("/api/settings/pairing/deny", "/api/settings/pairing"),
            paths,
        )
        assertTrue(repository.state.value.pending.isEmpty())
        assertEquals(0, repository.state.value.payload?.requests?.size)
        assertEquals(null, repository.state.value.error)
    }

    @Test
    fun actionFeedbackSurvivesFollowUpRefreshWithoutLastAction() = runBlocking {
        server.dispatcher = object : Dispatcher() {
            override fun dispatch(request: RecordedRequest): MockResponse = when (request.requestUrl?.encodedPath) {
                "/api/settings/pairing/approve" -> jsonResponse(
                    """{"requests":[],"last_action":{"ok":true,"action":"approve","message":"Approved","code":"ABC123"}}""",
                )
                "/api/settings/pairing" -> jsonResponse("""{"requests":[]}""")
                else -> MockResponse().setResponseCode(404)
            }
        }

        val repository = repository()
        repository.action("approve", "ABC123")

        // GET 刷新没有 last_action 时，UI 仍应保留 action 响应的成功提示。
        assertEquals("Approved", repository.state.value.payload?.lastAction?.message)
        assertEquals("ABC123", repository.state.value.payload?.lastAction?.code)
    }

    @Test
    fun refreshLoadsPairingRequestsAndClearsLoading() = runBlocking {
        server.dispatcher = object : Dispatcher() {
            override fun dispatch(request: RecordedRequest): MockResponse =
                jsonResponse("""{"requests":[{"code":"ABC123","channel":"telegram","sender_id":"sender-1"}]}""")
        }

        val repository = repository()
        repository.refresh()

        assertEquals("ABC123", repository.state.value.payload?.requests?.single()?.code)
        assertFalse(repository.state.value.loading)
        assertTrue(repository.state.value.error == null)
    }

    @Test
    fun resetIgnoresLateRefreshSuccess() = runBlocking {
        val responseStarted = CountDownLatch(1)
        val releaseResponse = CountDownLatch(1)
        server.dispatcher = object : Dispatcher() {
            override fun dispatch(request: RecordedRequest): MockResponse {
                responseStarted.countDown()
                releaseResponse.await(2, TimeUnit.SECONDS)
                return jsonResponse("""{"requests":[{"code":"LATE","channel":"telegram","sender_id":"late"}]}""")
            }
        }

        val repository = repository()
        val refresh = async(Dispatchers.IO) { repository.refresh() }
        assertTrue(responseStarted.await(2, TimeUnit.SECONDS))

        repository.reset()
        assertEquals(SecurityUiState(), repository.state.value)

        // reset 后旧 refresh 仍可能完成网络和 JSON 解析，但不能把旧 pairing 列表写回新会话。
        releaseResponse.countDown()
        withTimeout(2_000) { refresh.await() }

        assertEquals(SecurityUiState(), repository.state.value)
    }

    @Test
    fun resetIgnoresLateRefreshFailure() = runBlocking {
        val responseStarted = CountDownLatch(1)
        val releaseResponse = CountDownLatch(1)
        server.dispatcher = object : Dispatcher() {
            override fun dispatch(request: RecordedRequest): MockResponse {
                responseStarted.countDown()
                releaseResponse.await(2, TimeUnit.SECONDS)
                return MockResponse().setResponseCode(503).setBody("late pairing refresh failure")
            }
        }

        val repository = repository()
        val refresh = async(Dispatchers.IO) { repository.refresh() }
        assertTrue(responseStarted.await(2, TimeUnit.SECONDS))

        repository.reset()
        releaseResponse.countDown()
        withTimeout(2_000) { refresh.await() }

        // 迟到的 HTTP 错误不能恢复 error 或 loading；reset 后应保持全新会话的空状态。
        assertEquals(SecurityUiState(), repository.state.value)
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
                request.path == "/api/settings/pairing/approve?code=ABC123" -> {
                    if (actionCalls.incrementAndGet() == 1) {
                        firstActionStarted.countDown()
                        releaseFirstAction.await(2, TimeUnit.SECONDS)
                        jsonResponse("""{"requests":[{"code":"LATE","channel":"telegram","sender_id":"late"}]}""")
                    } else {
                        retryActionStarted.countDown()
                        releaseRetryAction.await(2, TimeUnit.SECONDS)
                        jsonResponse("""{"requests":[{"code":"RETRY","channel":"telegram","sender_id":"retry"}]}""")
                    }
                }
                request.path == "/api/settings/pairing" ->
                    jsonResponse("""{"requests":[]}""")
                else -> MockResponse().setResponseCode(404)
            }
        }

        val repository = repository()
        val first = async(Dispatchers.IO) { repository.action("approve", "ABC123") }
        assertTrue(firstActionStarted.await(2, TimeUnit.SECONDS))

        repository.reset()
        assertEquals(SecurityUiState(), repository.state.value)

        // UNDISPATCHED 让重试先完成 inFlight admission，再等待旧请求持有的串行锁；
        // 这样可以验证旧请求 finally 不会删除新会话刚建立的 pending/inFlight。
        val retry = async(Dispatchers.IO, start = CoroutineStart.UNDISPATCHED) {
            repository.action("approve", "ABC123")
        }
        releaseFirstAction.countDown()
        assertTrue(retryActionStarted.await(2, TimeUnit.SECONDS))

        // 旧 action 的成功响应已经迟到，但不能恢复 payload，也不能清掉新 action 的 pending。
        assertEquals(null, repository.state.value.payload)
        assertTrue(repository.state.value.pending.contains("ABC123"))
        assertEquals(null, repository.state.value.error)

        releaseRetryAction.countDown()
        withTimeout(2_000) { first.await() }
        withTimeout(2_000) { retry.await() }

        assertEquals(2, actionCalls.get())
        assertEquals(0, repository.state.value.payload?.requests?.size)
        assertTrue(repository.state.value.pending.isEmpty())
        assertEquals(null, repository.state.value.error)
    }

    @Test
    fun refreshFailureKeepsExistingPayloadAndExposesError() = runBlocking {
        val requestCount = AtomicInteger(0)
        server.dispatcher = object : Dispatcher() {
            override fun dispatch(request: RecordedRequest): MockResponse =
                if (requestCount.incrementAndGet() == 1) {
                    jsonResponse("""{"requests":[{"code":"ABC123","channel":"telegram","sender_id":"sender-1"}]}""")
                } else {
                    MockResponse().setResponseCode(503).setBody("pairing unavailable")
                }
        }

        val repository = repository()
        repository.refresh()
        repository.refresh()

        // 轮询失败不能清空现有 pairing 列表，便于用户继续查看上一次快照。
        assertEquals("ABC123", repository.state.value.payload?.requests?.single()?.code)
        assertFalse(repository.state.value.loading)
        assertTrue(repository.state.value.error.orEmpty().isNotBlank())
    }

    @Test
    fun duplicatePairingActionForSameCodeIsDropped() = runBlocking {
        val started = CountDownLatch(1)
        val release = CountDownLatch(1)
        val actionCount = AtomicInteger(0)
        server.dispatcher = object : Dispatcher() {
            override fun dispatch(request: RecordedRequest): MockResponse = when {
                request.path == "/api/settings/pairing/deny?code=ABC123" -> {
                    actionCount.incrementAndGet()
                    started.countDown()
                    release.await(2, TimeUnit.SECONDS)
                    jsonResponse("{\"requests\":[]}")
                }
                request.path == "/api/settings/pairing" -> jsonResponse("{\"requests\":[]}")
                else -> MockResponse().setResponseCode(404)
            }
        }

        val repository = repository()
        val first = async(Dispatchers.IO) { repository.action("deny", "ABC123") }
        assertTrue(started.await(2, TimeUnit.SECONDS))
        val second = async(Dispatchers.IO) { repository.action("deny", "ABC123") }
        withTimeout(2_000) { second.await() }
        release.countDown()
        withTimeout(2_000) { first.await() }

        assertEquals(1, actionCount.get())
        assertTrue(repository.state.value.pending.isEmpty())
    }

    @Test
    fun actionFailureClearsPendingAndExposesError() = runBlocking {
        server.dispatcher = object : Dispatcher() {
            override fun dispatch(request: RecordedRequest): MockResponse =
                MockResponse().setResponseCode(500).setBody("pairing unavailable")
        }

        val repository = repository()
        repository.action("approve", "ABC123")

        assertFalse(repository.state.value.pending.contains("ABC123"))
        assertTrue(repository.state.value.error.orEmpty().isNotBlank())
    }

    @Test
    fun actionFailureClearsPendingAndInFlightAndAllowsRetry() = runBlocking {
        val actionStarted = CountDownLatch(1)
        val releaseFailure = CountDownLatch(1)
        val actionCount = AtomicInteger(0)
        server.dispatcher = object : Dispatcher() {
            override fun dispatch(request: RecordedRequest): MockResponse = when {
                request.requestUrl?.encodedPath == "/api/settings/pairing/approve" -> {
                    if (actionCount.incrementAndGet() == 1) {
                        actionStarted.countDown()
                        releaseFailure.await(2, TimeUnit.SECONDS)
                        MockResponse().setResponseCode(500).setBody("pairing unavailable")
                    } else {
                        jsonResponse("{\"requests\":[],\"last_action\":{\"ok\":true,\"action\":\"approve\",\"message\":\"ok\",\"code\":\"ABC123\"}}")
                    }
                }
                request.requestUrl?.encodedPath == "/api/settings/pairing" ->
                    jsonResponse("{\"requests\":[]}")
                else -> MockResponse().setResponseCode(404)
            }
        }

        val repository = repository()
        val firstAttempt = async(Dispatchers.IO) { repository.action("approve", "ABC123") }
        assertTrue(actionStarted.await(2, TimeUnit.SECONDS))
        // 失败响应到达前必须暴露 pending，便于 UI 禁用重复操作；失败后则必须清理它。
        assertTrue(repository.state.value.pending.contains("ABC123"))

        releaseFailure.countDown()
        withTimeout(2_000) { firstAttempt.await() }
        assertTrue(repository.state.value.pending.isEmpty())
        assertTrue(repository.state.value.error.orEmpty().isNotBlank())

        // HTTP 失败后必须同时清理 pending 和 inFlight，否则同一 code 的后续重试会被静默丢弃。
        repository.action("approve", "ABC123")

        assertEquals(2, actionCount.get())
        assertTrue(repository.state.value.pending.isEmpty())
        assertEquals(null, repository.state.value.error)
    }

    private fun repository(): DefaultSecurityRepository = DefaultSecurityRepository(
        GatewayApiClient(
            OkHttpClient(),
            Json { ignoreUnknownKeys = true; explicitNulls = false },
            object : GatewayEndpointProvider {
                override val baseUrl: String = server.url("/").toString()
            },
            object : ApiCredentialProvider {
                override suspend fun tokenForRequest(): String = "test-api-token"
                override suspend fun tokenAfterUnauthorized(rejectedToken: String): String = "test-api-token"
            },
        ),
    )

    private fun jsonResponse(body: String): MockResponse = MockResponse()
        .setResponseCode(200)
        .setHeader("Content-Type", "application/json; charset=utf-8")
        .setBody(body)
}
