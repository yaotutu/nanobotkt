package com.nanobotkt.feature.apps

import com.nanobotkt.core.network.AuthContext
import com.nanobotkt.core.network.GatewayApiClient
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
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger

class AppsRepositoryTest {
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
    fun cliActionUsesEndpointQueryAndClearsPending() = runBlocking {
        configureMutationResponses(
            actionPath = "/api/settings/cli-apps/enable",
            actionPayload = """{"apps":[{"name":"action-result"}]}""",
        )

        val repository = repository()
        repository.cliAction(action = "enable", name = "demo")

        val request = server.takeRequest(2, TimeUnit.SECONDS)
            ?: error("cliAction request was not received")
        assertGetWithoutHttpBody(request)
        assertEquals("/api/settings/cli-apps/enable", request.requestUrl?.encodedPath)
        assertEquals("demo", request.requestUrl?.queryParameter("name"))
        assertNull(request.getHeader("X-Nanobot-MCP-Values"))
        assertMutationFinished(repository, "cli:demo")
    }

    @Test
    fun mcpActionUsesEndpointQueryAndTrimmedValuesHeader() = runBlocking {
        configureMutationResponses(
            actionPath = "/api/settings/mcp-presets/enable",
            actionPayload = """{"presets":[]}""",
        )

        val repository = repository()
        repository.mcpAction(
            action = "enable",
            name = "demo",
            values = mapOf("token" to " secret-token ", "empty" to "  "),
        )

        val request = server.takeRequest(2, TimeUnit.SECONDS)
            ?: error("mcpAction request was not received")
        assertGetWithoutHttpBody(request)
        assertEquals("/api/settings/mcp-presets/enable", request.requestUrl?.encodedPath)
        assertEquals("demo", request.requestUrl?.queryParameter("name"))
        assertEquals("""{"token":"secret-token"}""", request.getHeader("X-Nanobot-MCP-Values"))
        assertMutationFinished(repository, "mcp:demo")
    }

    @Test
    fun saveCustomUsesEndpointAndValuesHeaderWithoutQuery() = runBlocking {
        configureMutationResponses(
            actionPath = "/api/settings/mcp-presets/custom",
            actionPayload = """{"presets":[]}""",
        )

        val repository = repository()
        repository.saveCustom(mapOf("command" to " npx demo ", "url" to "https://example.test"))

        val request = server.takeRequest(2, TimeUnit.SECONDS)
            ?: error("saveCustom request was not received")
        assertGetWithoutHttpBody(request)
        assertEquals("/api/settings/mcp-presets/custom", request.requestUrl?.encodedPath)
        assertNull(request.requestUrl?.queryParameter("name"))
        assertEquals(
            """{"command":"npx demo","url":"https://example.test"}""",
            request.getHeader("X-Nanobot-MCP-Values"),
        )
        assertMutationFinished(repository, "mcp:custom")
    }

    @Test
    fun importConfigUsesTrimmedConfigInValuesHeader() = runBlocking {
        configureMutationResponses(
            actionPath = "/api/settings/mcp-presets/import",
            actionPayload = """{"presets":[]}""",
        )

        val repository = repository()
        repository.importConfig("  {\"mcpServers\":{}}  ")

        val request = server.takeRequest(2, TimeUnit.SECONDS)
            ?: error("importConfig request was not received")
        assertGetWithoutHttpBody(request)
        assertEquals("/api/settings/mcp-presets/import", request.requestUrl?.encodedPath)
        assertNull(request.requestUrl?.queryParameter("name"))
        assertEquals(
            Json.parseToJsonElement("""{"config":"{\"mcpServers\":{}}"}"""),
            Json.parseToJsonElement(requireNotNull(request.getHeader("X-Nanobot-MCP-Values"))),
        )
        assertMutationFinished(repository, "mcp:import")
    }

    @Test
    fun importCursorConfigUsesDedicatedEndpointAndValuesHeader() = runBlocking {
        configureMutationResponses(
            actionPath = "/api/settings/mcp-presets/import-cursor",
            actionPayload = """{"presets":[],"requires_restart":true}""",
        )

        val repository = repository()
        repository.importCursorConfig("  {\"mcpServers\":{}}  ")

        val request = server.takeRequest(2, TimeUnit.SECONDS)
            ?: error("importCursorConfig request was not received")
        assertGetWithoutHttpBody(request)
        assertEquals("/api/settings/mcp-presets/import-cursor", request.requestUrl?.encodedPath)
        assertNull(request.requestUrl?.queryParameter("name"))
        assertEquals(
            Json.parseToJsonElement("""{"config":"{\"mcpServers\":{}}"}"""),
            Json.parseToJsonElement(requireNotNull(request.getHeader("X-Nanobot-MCP-Values"))),
        )
        assertMutationFinished(repository, "mcp:import-cursor")
    }

    @Test
    fun updateToolsEncodesNameAndToolsInValuesHeader() = runBlocking {
        configureMutationResponses(
            actionPath = "/api/settings/mcp-presets/tools",
            actionPayload = """{"presets":[]}""",
        )

        val repository = repository()
        repository.updateTools(name = "demo", tools = listOf("read", "write"))

        val request = server.takeRequest(2, TimeUnit.SECONDS)
            ?: error("updateTools request was not received")
        assertGetWithoutHttpBody(request)
        assertEquals("/api/settings/mcp-presets/tools", request.requestUrl?.encodedPath)
        assertNull(request.requestUrl?.queryParameter("name"))
        assertEquals(
            Json.parseToJsonElement("""{"name":"demo","enabled_tools":["read","write"]}"""),
            Json.parseToJsonElement(requireNotNull(request.getHeader("X-Nanobot-MCP-Values"))),
        )
        assertMutationFinished(repository, "mcp:demo")
    }

    @Test
    fun pendingIsSetDuringMutationAndClearedAfterSuccess() = runBlocking {
        val requestStarted = CountDownLatch(1)
        val responseRelease = CountDownLatch(1)
        server.dispatcher = object : Dispatcher() {
            override fun dispatch(request: RecordedRequest): MockResponse =
                when (request.requestUrl?.encodedPath) {
                    "/api/settings/cli-apps/enable" -> {
                        requestStarted.countDown()
                        assertTrue(responseRelease.await(2, TimeUnit.SECONDS))
                        jsonResponse("""{"apps":[]}""")
                    }
                    "/api/settings/cli-apps" -> jsonResponse("""{"apps":[]}""")
                    "/api/settings/mcp-presets" -> jsonResponse("""{"presets":[]}""")
                    "/api/commands" -> jsonResponse("""{"commands":[]}""")
                    else -> MockResponse().setResponseCode(404)
                }
        }

        val repository = repository()
        val mutation = async(Dispatchers.IO) { repository.cliAction("enable", "demo") }
        assertTrue(requestStarted.await(2, TimeUnit.SECONDS))
        // 网络请求尚未返回时，UI 必须能看到精确的 action key，才能禁用对应按钮。
        assertTrue(repository.state.value.pending.contains("cli:demo"))

        responseRelease.countDown()
        withTimeout(2_000) { mutation.await() }
        assertMutationFinished(repository, "cli:demo")
    }

    @Test
    fun failedMutationClearsPendingAndExposesErrorThenSuccessClearsError() = runBlocking {
        val actionCount = AtomicInteger(0)
        server.dispatcher = object : Dispatcher() {
            override fun dispatch(request: RecordedRequest): MockResponse =
                when (request.requestUrl?.encodedPath) {
                    "/api/settings/cli-apps/enable" ->
                        if (actionCount.incrementAndGet() == 1) {
                            MockResponse().setResponseCode(503).setBody("service unavailable")
                        } else {
                            jsonResponse("""{"apps":[]}""")
                        }
                    "/api/settings/cli-apps" -> jsonResponse("""{"apps":[]}""")
                    "/api/settings/mcp-presets" -> jsonResponse("""{"presets":[]}""")
                    "/api/commands" -> jsonResponse("""{"commands":[]}""")
                    else -> MockResponse().setResponseCode(404)
                }
        }

        val repository = repository()
        repository.cliAction("enable", "demo")
        assertFalse(repository.state.value.pending.contains("cli:demo"))
        assertEquals("service unavailable", repository.state.value.error)

        // 下一次 action 开始前会清掉旧错误，成功后 finally 也必须清掉 pending。
        repository.cliAction("enable", "demo")
        assertMutationFinished(repository, "cli:demo")
    }

    @Test
    fun resetIgnoresLateRefreshSuccessAndClearsState() = runBlocking {
        val requestStarted = CountDownLatch(1)
        val responseRelease = CountDownLatch(1)
        server.dispatcher = object : Dispatcher() {
            override fun dispatch(request: RecordedRequest): MockResponse = when (request.path) {
                "/api/settings/cli-apps" -> {
                    requestStarted.countDown()
                    assertTrue(responseRelease.await(2, TimeUnit.SECONDS))
                    jsonResponse("""{"apps":[{"name":"stale"}]}""")
                }
                "/api/settings/mcp-presets" -> jsonResponse("""{"presets":[]}""")
                "/api/commands" -> jsonResponse("""{"commands":[]}""")
                else -> MockResponse().setResponseCode(404)
            }
        }

        val repository = repository()
        val refresh = async(Dispatchers.IO) { repository.refresh() }
        assertTrue(requestStarted.await(2, TimeUnit.SECONDS))

        // reset 发生在 refresh 返回前；旧会话的 payload、error、loading 和 pending 都不能写回新状态。
        repository.reset()
        assertEquals(AppsUiState(), repository.state.value)
        responseRelease.countDown()
        withTimeout(2_000) { refresh.await() }

        assertEquals(AppsUiState(), repository.state.value)
    }

    @Test
    fun resetIgnoresLateRefreshFailureAndClearsError() = runBlocking {
        val requestStarted = CountDownLatch(1)
        val responseRelease = CountDownLatch(1)
        server.dispatcher = object : Dispatcher() {
            override fun dispatch(request: RecordedRequest): MockResponse = when (request.path) {
                "/api/settings/cli-apps" -> {
                    requestStarted.countDown()
                    assertTrue(responseRelease.await(2, TimeUnit.SECONDS))
                    MockResponse().setResponseCode(503).setBody("stale refresh failure")
                }
                "/api/settings/mcp-presets" -> jsonResponse("""{"presets":[]}""")
                "/api/commands" -> jsonResponse("""{"commands":[]}""")
                else -> MockResponse().setResponseCode(404)
            }
        }

        val repository = repository()
        val refresh = async(Dispatchers.IO) { repository.refresh() }
        assertTrue(requestStarted.await(2, TimeUnit.SECONDS))

        // 迟到的 HTTP 错误也属于旧会话结果，reset 后不能重新暴露 error 或 loading。
        repository.reset()
        assertEquals(AppsUiState(), repository.state.value)
        responseRelease.countDown()
        withTimeout(2_000) { refresh.await() }

        assertEquals(AppsUiState(), repository.state.value)
    }

    @Test
    fun resetIgnoresLateMutationSuccessAndClearsPending() = runBlocking {
        val requestStarted = CountDownLatch(1)
        val responseRelease = CountDownLatch(1)
        server.dispatcher = object : Dispatcher() {
            override fun dispatch(request: RecordedRequest): MockResponse =
                if (request.requestUrl?.encodedPath == "/api/settings/cli-apps/enable") {
                    requestStarted.countDown()
                    assertTrue(responseRelease.await(2, TimeUnit.SECONDS))
                    jsonResponse("""{"apps":[{"name":"stale"}]}""")
                } else {
                    MockResponse().setResponseCode(404)
                }
        }

        val repository = repository()
        val mutation = async(Dispatchers.IO) { repository.cliAction("enable", "demo") }
        assertTrue(requestStarted.await(2, TimeUnit.SECONDS))
        assertTrue(repository.state.value.pending.contains("cli:demo"))

        // mutation 成功响应返回的旧 payload 不能跨过 reset，finally 也不能把旧 pending 写回去。
        repository.reset()
        assertEquals(AppsUiState(), repository.state.value)
        responseRelease.countDown()
        withTimeout(2_000) { mutation.await() }

        assertEquals(AppsUiState(), repository.state.value)
    }

    @Test
    fun resetIgnoresLateMutationFailureAndClearsError() = runBlocking {
        val requestStarted = CountDownLatch(1)
        val responseRelease = CountDownLatch(1)
        server.dispatcher = object : Dispatcher() {
            override fun dispatch(request: RecordedRequest): MockResponse =
                if (request.requestUrl?.encodedPath == "/api/settings/cli-apps/enable") {
                    requestStarted.countDown()
                    assertTrue(responseRelease.await(2, TimeUnit.SECONDS))
                    MockResponse().setResponseCode(503).setBody("stale mutation failure")
                } else {
                    MockResponse().setResponseCode(404)
                }
        }

        val repository = repository()
        val mutation = async(Dispatchers.IO) { repository.cliAction("enable", "demo") }
        assertTrue(requestStarted.await(2, TimeUnit.SECONDS))
        assertTrue(repository.state.value.pending.contains("cli:demo"))

        // 迟到的 mutation 异常同样不能恢复 error 或 pending，避免新会话出现旧请求的失败提示。
        repository.reset()
        assertEquals(AppsUiState(), repository.state.value)
        responseRelease.countDown()
        withTimeout(2_000) { mutation.await() }

        assertEquals(AppsUiState(), repository.state.value)
    }

    @Test
    fun refreshPartialFailureKeepsPreviousPayloadsAndExposesFinalError() = runBlocking {
        val cliCalls = AtomicInteger(0)
        val mcpCalls = AtomicInteger(0)
        val commandCalls = AtomicInteger(0)
        server.dispatcher = object : Dispatcher() {
            override fun dispatch(request: RecordedRequest): MockResponse = when (request.path) {
                "/api/settings/cli-apps" -> {
                    if (cliCalls.incrementAndGet() == 1) {
                        jsonResponse("""{"apps":[{"name":"old-cli"}]}""")
                    } else {
                        // 第二轮只有 CLI 失败；另外两路即使已经返回新值，也不应形成“半更新”状态。
                        MockResponse().setResponseCode(503).setBody("cli unavailable")
                    }
                }
                "/api/settings/mcp-presets" ->
                    if (mcpCalls.incrementAndGet() == 1) {
                        jsonResponse("""{"presets":[{"name":"old-mcp"}]}""")
                    } else {
                        jsonResponse("""{"presets":[{"name":"new-mcp"}]}""")
                    }
                "/api/commands" ->
                    if (commandCalls.incrementAndGet() == 1) {
                        jsonResponse("""{"commands":[{"command":"old-command","title":"Old","description":"","icon":"","lifecycle":"stable"}]}""")
                    } else {
                        jsonResponse("""{"commands":[{"command":"new-command","title":"New","description":"","icon":"","lifecycle":"stable"}]}""")
                    }
                else -> MockResponse().setResponseCode(404)
            }
        }

        val repository = repository()
        repository.refresh()
        repository.refresh()

        // 多路 refresh 采用全量提交语义：任一路失败时保留上一份完整快照，
        // 同时结束 loading 并暴露错误；不能把另外两路的新响应拼成半新半旧状态。
        assertEquals("old-cli", repository.state.value.cli?.apps?.single()?.name)
        assertEquals("old-mcp", repository.state.value.mcp?.presets?.single()?.name)
        assertEquals("old-command", repository.state.value.commands?.commands?.single()?.command)
        assertFalse(repository.state.value.loading)
        assertTrue(repository.state.value.error.orEmpty().isNotBlank())
    }

    @Test
    fun newerRefreshCannotBeOverwrittenByOlderResponse() = runBlocking {
        val firstCliStarted = CountDownLatch(1)
        val cliRequestCount = AtomicInteger(0)
        server.dispatcher = object : Dispatcher() {
            override fun dispatch(request: RecordedRequest): MockResponse = when (request.path) {
                "/api/settings/cli-apps" -> {
                    if (cliRequestCount.incrementAndGet() == 1) {
                        firstCliStarted.countDown()
                        jsonResponse("""{"apps":[{"name":"old"}]}""")
                            .setBodyDelay(500, TimeUnit.MILLISECONDS)
                    } else {
                        jsonResponse("""{"apps":[{"name":"new"}]}""")
                    }
                }
                "/api/settings/mcp-presets" -> jsonResponse("""{"presets":[]}""")
                "/api/commands" -> jsonResponse("""{"commands":[]}""")
                else -> MockResponse().setResponseCode(404)
            }
        }

        val repository = DefaultAppsRepository(
            api = GatewayApiClient(
                OkHttpClient(),
                Json { ignoreUnknownKeys = true; explicitNulls = false },
                object : AuthContext {
                    override val baseUrl: String = server.url("/").toString()
                    override val apiToken: String? = null
                },
            ),
            json = Json { ignoreUnknownKeys = true; explicitNulls = false },
        )

        val first = async(Dispatchers.IO) { repository.refresh() }
        assertTrue(firstCliStarted.await(2, TimeUnit.SECONDS))

        val second = async(Dispatchers.IO) { repository.refresh() }
        withTimeout(2_000) { second.await() }
        withTimeout(2_000) { first.await() }

        assertEquals("new", repository.state.value.cli?.apps?.single()?.name)
    }

    /**
     * action 成功后会自动触发 refresh；这里为三条 refresh 请求提供最小合法响应，
     * 同时让每个契约测试只需要关注自己发出的第一条 mutation 请求。
     */
    private fun configureMutationResponses(actionPath: String, actionPayload: String) {
        server.dispatcher = object : Dispatcher() {
            override fun dispatch(request: RecordedRequest): MockResponse =
                when (request.requestUrl?.encodedPath) {
                    actionPath -> jsonResponse(actionPayload)
                    "/api/settings/cli-apps" -> jsonResponse("""{"apps":[]}""")
                    "/api/settings/mcp-presets" -> jsonResponse("""{"presets":[]}""")
                    "/api/commands" -> jsonResponse("""{"commands":[]}""")
                    else -> MockResponse().setResponseCode(404)
                }
        }
    }

    /**
     * AppsRepository 的 mutation 当前使用 GET，将 MCP 参数放在自定义 header 中，
     * 因此契约上既不能误发 HTTP body，也不能漏掉通用 Accept header。
     */
    private fun assertGetWithoutHttpBody(request: RecordedRequest) {
        assertEquals("GET", request.method)
        assertEquals("application/json", request.getHeader("Accept"))
        assertNull(request.getHeader("Content-Type"))
        assertEquals("", request.body.readUtf8())
    }

    private fun assertMutationFinished(repository: DefaultAppsRepository, key: String) {
        assertFalse(repository.state.value.pending.contains(key))
        assertTrue(repository.state.value.pending.isEmpty())
        assertNull(repository.state.value.error)
    }

    private fun repository(): DefaultAppsRepository = DefaultAppsRepository(
        api = GatewayApiClient(
            OkHttpClient(),
            Json { ignoreUnknownKeys = true; explicitNulls = false },
            object : AuthContext {
                override val baseUrl: String = server.url("/").toString()
                override val apiToken: String? = null
            },
        ),
        json = Json { ignoreUnknownKeys = true; explicitNulls = false },
    )

    private fun jsonResponse(body: String): MockResponse = MockResponse()
        .setResponseCode(200)
        .setHeader("Content-Type", "application/json; charset=utf-8")
        .setBody(body)
}
