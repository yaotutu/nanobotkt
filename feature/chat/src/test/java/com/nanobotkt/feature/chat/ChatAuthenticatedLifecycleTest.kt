package com.nanobotkt.feature.chat

import com.nanobotkt.core.model.BootstrapResponse
import com.nanobotkt.core.model.BootstrapSnapshotProvider
import com.nanobotkt.core.model.IngressLimitsProvider
import com.nanobotkt.core.model.WorkspacesPayload
import com.nanobotkt.core.network.AuthContext
import com.nanobotkt.core.network.GatewayApiClient
import com.nanobotkt.core.transport.NanobotTransport
import com.nanobotkt.core.transport.TransportCredentials
import com.nanobotkt.core.workspace.WorkspaceAccessProvider
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
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
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

/**
 * 锁定 Chat Singleton 与认证会话的边界：构造阶段不得抢跑需要 Token 的请求，logout 后旧响应
 * 也不能恢复目录。这里使用真实 GatewayApiClient + MockWebServer，覆盖 OkHttp 取消不及时的情况。
 */
class ChatAuthenticatedLifecycleTest {
    private lateinit var server: MockWebServer
    private lateinit var httpClient: OkHttpClient
    private lateinit var transport: NanobotTransport
    private lateinit var repository: DefaultChatRepository
    private val workspaceProvider = RecordingWorkspaceProvider()
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
            credentials = TestLifecycleCredentials(server.url("/ws").toString()),
        )
        repository = DefaultChatRepository(
            api = GatewayApiClient(
                client = httpClient,
                json = json,
                authContext = object : AuthContext {
                    override val baseUrl: String = server.url("/").toString()
                    override val apiToken: String? = "test-token"
                },
            ),
            transport = transport,
            limitsProvider = object : IngressLimitsProvider {
                override fun currentIngressLimits() = null
            },
            bootstrapProvider = object : BootstrapSnapshotProvider {
                override fun currentBootstrap(): BootstrapResponse? = null
            },
            workspaceAccessProvider = workspaceProvider,
        )
    }

    @After
    fun tearDown() {
        repository.reset()
        transport.close()
        httpClient.dispatcher.executorService.shutdownNow()
        httpClient.connectionPool.evictAll()
        server.shutdown()
    }

    @Test
    fun `constructor does not start authenticated catalog or workspace requests`() = runBlocking {
        delay(200)

        assertEquals(0, server.requestCount)
        assertEquals(0, workspaceProvider.refreshCount.get())
    }

    @Test
    fun `ready loads catalogs once and repeated ready in same session is deduplicated`() = runBlocking {
        server.dispatcher = catalogDispatcher(skillName = "android")

        repository.onAuthenticated(sessionEpoch = 7)
        val loaded = withTimeout(5_000) {
            repository.state.first { state -> state.skills.singleOrNull()?.name == "android" }
        }

        assertEquals("android", loaded.skills.single().name)
        awaitRequestCount(expected = 5)
        assertEquals(1, workspaceProvider.refreshCount.get())

        repository.onAuthenticated(sessionEpoch = 7)
        delay(200)

        assertEquals(5, server.requestCount)
        assertEquals(1, workspaceProvider.refreshCount.get())
    }

    @Test
    fun `reset invalidates late catalog response and relogin reloads catalogs`() = runBlocking {
        val firstRequestStarted = CountDownLatch(1)
        val releaseFirstResponse = CountDownLatch(1)
        server.dispatcher = object : Dispatcher() {
            override fun dispatch(request: RecordedRequest): MockResponse {
                if (request.requestUrl?.encodedPath == "/api/commands") {
                    firstRequestStarted.countDown()
                    assertTrue(releaseFirstResponse.await(5, TimeUnit.SECONDS))
                    return jsonResponse(
                        """{"commands":[{"command":"stale","title":"Stale","description":"old account","icon":"x","lifecycle":"client"}]}""",
                    )
                }
                return successfulCatalogResponse(request, skillName = "stale-skill")
            }
        }

        repository.onAuthenticated(sessionEpoch = 1)
        assertTrue(firstRequestStarted.await(5, TimeUnit.SECONDS))
        repository.reset()
        releaseFirstResponse.countDown()
        delay(300)

        assertTrue(repository.state.value.slashCommands.isEmpty())
        assertTrue(repository.state.value.skills.isEmpty())

        server.dispatcher = catalogDispatcher(skillName = "new-account-skill")
        repository.onAuthenticated(sessionEpoch = 2)
        val reloaded = withTimeout(5_000) {
            repository.state.first { state -> state.skills.singleOrNull()?.name == "new-account-skill" }
        }

        assertEquals("new-account-skill", reloaded.skills.single().name)
        assertTrue(reloaded.slashCommands.none { command -> command.command == "stale" })
        assertEquals(2, workspaceProvider.refreshCount.get())
    }

    private fun catalogDispatcher(skillName: String): Dispatcher = object : Dispatcher() {
        override fun dispatch(request: RecordedRequest): MockResponse =
            successfulCatalogResponse(request, skillName)
    }

    private fun successfulCatalogResponse(request: RecordedRequest, skillName: String): MockResponse =
        when (request.requestUrl?.encodedPath) {
            "/api/commands" -> jsonResponse("""{"commands":[]}""")
            ComposerCatalogLoader.COMPOSER_SKILLS_PATH ->
                jsonResponse("""{"skills":[{"name":"$skillName","available":true}]}""")
            "/api/settings/cli-apps" -> jsonResponse("""{"apps":[]}""")
            "/api/settings/mcp-presets" -> jsonResponse("""{"presets":[]}""")
            "/api/settings" -> jsonResponse("{}")
            else -> MockResponse().setResponseCode(404)
        }

    private suspend fun awaitRequestCount(expected: Int) {
        withTimeout(5_000) {
            while (server.requestCount < expected) delay(10)
        }
    }

    private fun jsonResponse(body: String): MockResponse = MockResponse()
        .setResponseCode(200)
        .setHeader("Content-Type", "application/json")
        .setBody(body)
}

private class RecordingWorkspaceProvider : WorkspaceAccessProvider {
    override val workspaces = MutableStateFlow<WorkspacesPayload?>(null)
    val refreshCount = AtomicInteger(0)

    override suspend fun refresh() {
        refreshCount.incrementAndGet()
    }
}

private data class TestLifecycleCredentials(
    private val wsUrl: String,
) : TransportCredentials {
    override fun currentWebSocketUrl(): String = wsUrl
    override suspend fun reauthenticateWebSocketUrl(): String = wsUrl
    override fun maxFrameBytes(): Int? = null
}
