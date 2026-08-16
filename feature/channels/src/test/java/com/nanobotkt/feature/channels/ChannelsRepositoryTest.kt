package com.nanobotkt.feature.channels

import com.nanobotkt.core.network.ApiCredentialProvider
import com.nanobotkt.core.network.GatewayEndpointProvider
import com.nanobotkt.core.network.GatewayApiClient
import kotlinx.serialization.json.Json
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import okhttp3.OkHttpClient
import okhttp3.mockwebserver.Dispatcher
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import okhttp3.mockwebserver.RecordedRequest
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import org.junit.Test

class ChannelsRepositoryTest {
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
    fun connectPollCancelPreserveSessionAndInstanceQuery() = runBlocking {
        val paths = mutableListOf<String>()
        server.dispatcher = object : Dispatcher() {
            override fun dispatch(request: RecordedRequest): MockResponse {
                paths += request.path.orEmpty()
                return when (request.path) {
                    "/api/settings/channels/WhatsApp/connect/start?instance_id=phone-2" ->
                        jsonResponse("{\"session_id\":\"session-1\",\"instance_id\":\"phone-2\",\"status\":\"pending\",\"interval_ms\":500}")
                    "/api/settings/channels/WhatsApp/connect/poll?session_id=session-1" ->
                        jsonResponse("{\"session_id\":\"session-1\",\"instance_id\":\"phone-2\",\"status\":\"ready\"}")
                    "/api/settings/channels/WhatsApp/connect/cancel?session_id=session-1" ->
                        jsonResponse("{\"session_id\":\"session-1\",\"instance_id\":\"phone-2\",\"status\":\"cancelled\"}")
                    else -> MockResponse().setResponseCode(404)
                }
            }
        }

        val repository = repository()
        assertEquals("session-1", repository.startConnect("WhatsApp", "phone-2")?.sessionId)
        assertEquals("ready", repository.pollConnect("WhatsApp", "session-1", "phone-2")?.status)
        assertEquals("cancelled", repository.cancelConnect("WhatsApp", "session-1", "phone-2")?.status)

        assertEquals(
            listOf(
                "/api/settings/channels/WhatsApp/connect/start?instance_id=phone-2",
                "/api/settings/channels/WhatsApp/connect/poll?session_id=session-1",
                "/api/settings/channels/WhatsApp/connect/cancel?session_id=session-1",
            ),
            paths,
        )
        assertEquals("cancelled", repository.state.value.connectionFor("WhatsApp", "phone-2")?.status)
        assertTrue(repository.state.value.pending.isEmpty())
    }

    @Test
    fun resetIgnoresLateMutationResponseAndClearsState() = runBlocking {
        val requestStarted = CountDownLatch(1)
        val responseRelease = CountDownLatch(1)
        server.dispatcher = object : Dispatcher() {
            override fun dispatch(request: RecordedRequest): MockResponse =
                if (request.requestUrl?.encodedPath == "/api/settings/nanobot-features/enable") {
                    requestStarted.countDown()
                    assertTrue(responseRelease.await(2, TimeUnit.SECONDS))
                    jsonResponse("""{"features":[{"name":"stale"}],"enabled_count":1}""")
                } else {
                    MockResponse().setResponseCode(404)
                }
        }

        val repository = repository()
        val mutation = async(Dispatchers.IO) {
            repository.setEnabled("Slack", enabled = true, instanceId = "team-a")
        }
        assertTrue(requestStarted.await(2, TimeUnit.SECONDS))

        // logout/reset 发生在请求返回前；旧响应和 finally 都不能恢复旧会话状态。
        repository.reset()
        responseRelease.countDown()
        withTimeout(2_000) { mutation.await() }

        assertEquals(ChannelsUiState(), repository.state.value)
    }

    @Test
    fun resetIgnoresLateRefreshResponseAndClearsLoadingState() = runBlocking {
        val requestStarted = CountDownLatch(1)
        val responseRelease = CountDownLatch(1)
        server.dispatcher = object : Dispatcher() {
            override fun dispatch(request: RecordedRequest): MockResponse {
                requestStarted.countDown()
                assertTrue(responseRelease.await(2, TimeUnit.SECONDS))
                return jsonResponse("""{"features":[{"name":"stale"}],"enabled_count":1}""")
            }
        }

        val repository = repository()
        val refresh = async(Dispatchers.IO) { repository.refresh() }
        assertTrue(requestStarted.await(2, TimeUnit.SECONDS))

        // reset 发生在刷新响应返回前；迟到 payload 不能重新写入新会话，也不能恢复 loading。
        repository.reset()
        responseRelease.countDown()
        withTimeout(2_000) { refresh.await() }

        assertEquals(ChannelsUiState(), repository.state.value)
    }

    @Test
    fun resetIgnoresLateValidateResponseAndClearsPendingState() = runBlocking {
        val requestStarted = CountDownLatch(1)
        val responseRelease = CountDownLatch(1)
        server.dispatcher = object : Dispatcher() {
            override fun dispatch(request: RecordedRequest): MockResponse {
                requestStarted.countDown()
                assertTrue(responseRelease.await(2, TimeUnit.SECONDS))
                return jsonResponse("""{"name":"Slack","status":"ok","can_enable":true}""")
            }
        }

        val repository = repository()
        val validate = async(Dispatchers.IO) {
            repository.validate("Slack", mapOf("token" to "stale"), instanceId = "team-a")
        }
        assertTrue(requestStarted.await(2, TimeUnit.SECONDS))

        // validate 与 configure 共用 feature pending 键；reset 后两者都不能把旧结果带回新会话。
        repository.reset()
        responseRelease.countDown()
        withTimeout(2_000) { validate.await() }

        assertEquals(ChannelsUiState(), repository.state.value)
    }

    @Test
    fun resetIgnoresLateStartConnectResponseAndClearsConnectionState() = runBlocking {
        val requestStarted = CountDownLatch(1)
        val responseRelease = CountDownLatch(1)
        server.dispatcher = object : Dispatcher() {
            override fun dispatch(request: RecordedRequest): MockResponse {
                requestStarted.countDown()
                assertTrue(responseRelease.await(2, TimeUnit.SECONDS))
                return jsonResponse("""{"session_id":"stale-start","instance_id":"phone-1","status":"pending"}""")
            }
        }

        val repository = repository()
        val start = async(Dispatchers.IO) { repository.startConnect("WhatsApp", instanceId = "phone-1") }
        assertTrue(requestStarted.await(2, TimeUnit.SECONDS))

        // startConnect 的迟到响应不能创建新会话中的旧 connection 快照。
        repository.reset()
        responseRelease.countDown()
        withTimeout(2_000) { start.await() }

        assertEquals(ChannelsUiState(), repository.state.value)
    }

    @Test
    fun resetIgnoresLatePollResponseAndClearsConnectionState() = runBlocking {
        val requestStarted = CountDownLatch(1)
        val responseRelease = CountDownLatch(1)
        server.dispatcher = object : Dispatcher() {
            override fun dispatch(request: RecordedRequest): MockResponse {
                requestStarted.countDown()
                assertTrue(responseRelease.await(2, TimeUnit.SECONDS))
                return jsonResponse("""{"session_id":"stale-poll","instance_id":"phone-1","status":"ready"}""")
            }
        }

        val repository = repository()
        val poll = async(Dispatchers.IO) {
            repository.pollConnect("WhatsApp", sessionId = "stale-poll", instanceId = "phone-1")
        }
        assertTrue(requestStarted.await(2, TimeUnit.SECONDS))

        // poll 返回 ready 也不能绕过 generation 检查，避免旧连接覆盖 reset 后的空状态。
        repository.reset()
        responseRelease.countDown()
        withTimeout(2_000) { poll.await() }

        assertEquals(ChannelsUiState(), repository.state.value)
    }

    @Test
    fun resetIgnoresLateCancelResponseAndClearsConnectionState() = runBlocking {
        val requestStarted = CountDownLatch(1)
        val responseRelease = CountDownLatch(1)
        server.dispatcher = object : Dispatcher() {
            override fun dispatch(request: RecordedRequest): MockResponse {
                requestStarted.countDown()
                assertTrue(responseRelease.await(2, TimeUnit.SECONDS))
                return jsonResponse("""{"session_id":"stale-cancel","instance_id":"phone-1","status":"cancelled"}""")
            }
        }

        val repository = repository()
        val cancel = async(Dispatchers.IO) {
            repository.cancelConnect("WhatsApp", sessionId = "stale-cancel", instanceId = "phone-1")
        }
        assertTrue(requestStarted.await(2, TimeUnit.SECONDS))

        // cancel 的写入同样必须服从 session generation，不能在 logout 后恢复旧账号的 cancelled 状态。
        repository.reset()
        responseRelease.countDown()
        withTimeout(2_000) { cancel.await() }

        assertEquals(ChannelsUiState(), repository.state.value)
    }

    @Test
    fun channelNameIsEncodedAsPathSegmentForConnectRequests() = runBlocking {
        server.enqueue(jsonResponse("""{"session_id":"encoded","status":"ready"}"""))
        val repository = repository()
        val channelName = "Slack Team/Alpha Beta"

        repository.startConnect(channelName)

        val request = server.takeRequest()
        // 频道名是 path segment，空格和斜杠都必须编码，斜杠不能被解释成额外路径层级。
        assertEquals(
            "/api/settings/channels/Slack%20Team%2FAlpha%20Beta/connect/start",
            request.requestUrl?.encodedPath,
        )
    }

    @Test
    fun malformedPollPayloadStopsThisAttemptAndClearsPending() = runBlocking {
        server.dispatcher = object : Dispatcher() {
            override fun dispatch(request: RecordedRequest): MockResponse = when (request.path) {
                "/api/settings/channels/Slack/connect/start" ->
                    jsonResponse("""{"session_id":"malformed-poll","status":"pending","interval_ms":500}""")
                "/api/settings/channels/Slack/connect/poll?session_id=malformed-poll" ->
                    MockResponse()
                        .setResponseCode(200)
                        .setHeader("Content-Type", "application/json; charset=utf-8")
                        .setBody("{not-json")
                else -> MockResponse().setResponseCode(404)
            }
        }

        val repository = repository()
        assertEquals("malformed-poll", repository.startConnect("Slack")?.sessionId)
        assertNull(repository.pollConnect("Slack", sessionId = "malformed-poll"))

        // malformed payload 必须被 repository 吸收为失败结果：本次 poll 的 pending 要清理，
        // 并暴露可观察错误；上层 ViewModel 收到 null 后即可停止后续轮询。
        assertTrue(repository.state.value.error.orEmpty().contains("invalid_payload"))
        assertTrue(repository.state.value.pending.isEmpty())
        assertEquals("pending", repository.state.value.connectionFor("Slack")?.status)
    }

    @Test
    fun duplicatePollForSameChannelAndInstanceIsDroppedWhileFirstIsInFlight() = runBlocking {
        val requestStarted = CountDownLatch(1)
        val responseRelease = CountDownLatch(1)
        server.dispatcher = object : Dispatcher() {
            override fun dispatch(request: RecordedRequest): MockResponse {
                requestStarted.countDown()
                assertTrue(responseRelease.await(2, TimeUnit.SECONDS))
                return jsonResponse("""{"session_id":"poll-1","status":"ready"}""")
            }
        }

        val repository = repository()
        val first = async(Dispatchers.IO) {
            repository.pollConnect("Slack", sessionId = "poll-1", instanceId = "team-a")
        }
        assertTrue(requestStarted.await(2, TimeUnit.SECONDS))

        // 同一 channel + instance 的第二次 poll 不应重复访问服务端；cancel 使用独立键，仍可单独发起。
        val duplicate = async(Dispatchers.IO) {
            repository.pollConnect("Slack", sessionId = "poll-1", instanceId = "team-a")
        }
        assertNull(withTimeout(2_000) { duplicate.await() })

        responseRelease.countDown()
        withTimeout(2_000) { first.await() }
        assertEquals(1, server.requestCount)
        assertTrue(repository.state.value.pending.isEmpty())
    }

    @Test
    fun setEnabled503PublishesServerErrorAndClearsPending() = runBlocking {
        server.dispatcher = object : Dispatcher() {
            override fun dispatch(request: RecordedRequest): MockResponse =
                MockResponse().setResponseCode(503).setBody("service unavailable")
        }

        val repository = repository()
        repository.setEnabled("Slack", enabled = true, instanceId = "team-a")
        val request = server.takeRequest()

        // 503 由 repository 的 mutate 捕获为 state.error，同时 finally 必须清理本次操作的 pending 标记。
        assertEquals("/api/settings/nanobot-features/enable", request.requestUrl?.encodedPath)
        assertEquals("Slack", request.requestUrl?.queryParameter("name"))
        assertEquals("team-a", request.requestUrl?.queryParameter("instance_id"))
        assertTrue(repository.state.value.error.orEmpty().contains("service unavailable"))
        assertTrue(repository.state.value.pending.isEmpty())
    }

    @Test
    fun refresh503PublishesErrorAndRestoresIdleState() = runBlocking {
        server.enqueue(
            jsonResponse(
                """{"features":[{"name":"Slack","enabled":true}],"enabled_count":1}""",
            ),
        )
        server.enqueue(MockResponse().setResponseCode(503).setBody("refresh unavailable"))

        val repository = repository()
        repository.refresh()
        repository.refresh()

        // 二次刷新失败时保留最后一次成功 payload，并确保 loading/pending 都回到空闲态，避免界面永久阻塞。
        assertEquals("Slack", repository.state.value.payload?.features?.single()?.name)
        assertEquals(1, repository.state.value.payload?.enabledCount)
        assertTrue(repository.state.value.error.orEmpty().contains("refresh unavailable"))
        assertFalse(repository.state.value.loading)
        assertTrue(repository.state.value.pending.isEmpty())
        assertEquals("/api/settings/nanobot-features", server.takeRequest().path)
        assertEquals("/api/settings/nanobot-features", server.takeRequest().path)
    }

    @Test
    fun configureWithoutNanobotFeaturesRefreshesPayload() = runBlocking {
        server.dispatcher = object : Dispatcher() {
            override fun dispatch(request: RecordedRequest): MockResponse = when (request.path) {
                "/api/settings/channels/configure?name=Slack&enable=true&instance_id=team-a" ->
                    jsonResponse("""{"name":"Slack","saved":true,"saved_keys":["token"]}""")
                "/api/settings/nanobot-features" ->
                    jsonResponse(
                        """{"features":[{"name":"Slack","display_name":"Slack Workspace","enabled":true,"configured":true}],"enabled_count":1}""",
                    )
                else -> MockResponse().setResponseCode(404)
            }
        }

        val repository = repository()
        repository.configure("Slack", mapOf("token" to "secret"), enable = true, instanceId = "team-a")

        val configureRequest = server.takeRequest()
        val refreshRequest = server.takeRequest()
        // configure 响应缺少 nanobot_features 时必须补做 refresh，最终状态以服务端完整快照为准。
        assertEquals("/api/settings/channels/configure", configureRequest.requestUrl?.encodedPath)
        assertEquals("{\"token\":\"secret\"}", configureRequest.getHeader("X-Nanobot-Channel-Values"))
        assertEquals("/api/settings/nanobot-features", refreshRequest.path)
        assertEquals("Slack Workspace", repository.state.value.payload?.features?.single()?.displayName)
        assertEquals(1, repository.state.value.payload?.enabledCount)
        assertFalse(repository.state.value.loading)
        assertTrue(repository.state.value.pending.isEmpty())
        assertEquals(null, repository.state.value.error)
    }

    @Test
    fun connectionsAreIsolatedByChannelAndInstance() = runBlocking {
        server.dispatcher = object : Dispatcher() {
            override fun dispatch(request: RecordedRequest): MockResponse = when (request.path) {
                "/api/settings/channels/WhatsApp/connect/start?instance_id=phone-1" ->
                    jsonResponse("{\"session_id\":\"one\",\"instance_id\":\"phone-1\",\"status\":\"pending\"}")
                "/api/settings/channels/WhatsApp/connect/start?instance_id=phone-2" ->
                    jsonResponse("{\"session_id\":\"two\",\"instance_id\":\"phone-2\",\"status\":\"pending\"}")
                else -> MockResponse().setResponseCode(404)
            }
        }

        val repository = repository()
        repository.startConnect("WhatsApp", "phone-1")
        repository.startConnect("WhatsApp", "phone-2")

        assertEquals("one", repository.state.value.connectionFor("WhatsApp", "phone-1")?.sessionId)
        assertEquals("two", repository.state.value.connectionFor("WhatsApp", "phone-2")?.sessionId)
        assertEquals(2, repository.state.value.connections.size)
    }

    @Test
    fun invalidValidationStopsBeforeConfigure() = runBlocking {
        val paths = mutableListOf<String>()
        server.dispatcher = object : Dispatcher() {
            override fun dispatch(request: RecordedRequest): MockResponse {
                paths += request.path.orEmpty()
                return when (request.path) {
                    "/api/settings/channels/validate?name=Slack&instance_id=team-a" ->
                        jsonResponse("""{"name":"Slack","status":"invalid","can_enable":false,"missing_fields":["token"]}""")
                    else -> MockResponse().setResponseCode(404)
                }
            }
        }

        val repository = repository()
        val validation = repository.validateAndConfigure(
            "Slack",
            mapOf("token" to "secret"),
            instanceId = "team-a",
        )

        assertFalse(validation?.canEnable == true)
        assertEquals(listOf("/api/settings/channels/validate?name=Slack&instance_id=team-a"), paths)
        assertEquals("team-a", repository.state.value.validationKey?.substringAfter("::"))
        assertTrue(repository.state.value.pending.isEmpty())
    }

    @Test
    fun validValidationThenConfigurePreservesOrderHeaderAndQuery() = runBlocking {
        val requests = mutableListOf<RecordedRequest>()
        server.dispatcher = object : Dispatcher() {
            override fun dispatch(request: RecordedRequest): MockResponse {
                requests += request
                return when (request.path) {
                    "/api/settings/channels/validate?name=Slack&instance_id=team-a" ->
                        jsonResponse("""{"name":"Slack","status":"ok","can_enable":true,"requires_restart":true}""")
                    "/api/settings/channels/configure?name=Slack&enable=true&instance_id=team-a" ->
                        jsonResponse("""{"name":"Slack","saved":true,"saved_keys":["token"],"nanobot_features":{"features":[],"enabled_count":0}}""")
                    else -> MockResponse().setResponseCode(404)
                }
            }
        }

        val repository = repository()
        val validation = repository.validateAndConfigure(
            "Slack",
            mapOf("token" to "secret"),
            instanceId = "team-a",
        )

        assertTrue(validation?.canEnable == true)
        assertEquals(
            listOf(
                "/api/settings/channels/validate?name=Slack&instance_id=team-a",
                "/api/settings/channels/configure?name=Slack&enable=true&instance_id=team-a",
            ),
            requests.map { it.path },
        )
        assertEquals("{\"token\":\"secret\"}", requests[0].getHeader("X-Nanobot-Channel-Values"))
        assertEquals("{\"token\":\"secret\"}", requests[1].getHeader("X-Nanobot-Channel-Values"))
        assertTrue(repository.state.value.validation?.requiresRestart == true)
    }

    @Test
    fun configureAndValidateSendInstanceAndValues() = runBlocking {
        val requests = mutableListOf<RecordedRequest>()
        server.dispatcher = object : Dispatcher() {
            override fun dispatch(request: RecordedRequest): MockResponse {
                requests += request
                return when (request.path) {
                    "/api/settings/channels/configure?name=Slack&enable=true&instance_id=team-a" ->
                        jsonResponse("{\"name\":\"Slack\",\"saved\":true,\"saved_keys\":[\"token\"],\"nanobot_features\":{\"features\":[],\"enabled_count\":0}}")
                    "/api/settings/channels/validate?name=Slack&instance_id=team-a" ->
                        jsonResponse("{\"name\":\"Slack\",\"status\":\"ok\",\"can_enable\":true}")
                    else -> MockResponse().setResponseCode(404)
                }
            }
        }

        val repository = repository()
        repository.configure("Slack", mapOf("token" to "secret"), enable = true, instanceId = "team-a")
        repository.validate("Slack", mapOf("token" to "secret"), instanceId = "team-a")

        assertEquals("{\"token\":\"secret\"}", requests[0].getHeader("X-Nanobot-Channel-Values"))
        assertEquals("{\"token\":\"secret\"}", requests[1].getHeader("X-Nanobot-Channel-Values"))
        assertEquals("ok", repository.state.value.validation?.status)
        assertTrue(repository.state.value.pending.isEmpty())
    }

    private fun repository(): DefaultChannelsRepository = DefaultChannelsRepository(
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
        Json { ignoreUnknownKeys = true; explicitNulls = false },
    )

    private fun jsonResponse(body: String): MockResponse = MockResponse()
        .setResponseCode(200)
        .setHeader("Content-Type", "application/json; charset=utf-8")
        .setBody(body)
}
