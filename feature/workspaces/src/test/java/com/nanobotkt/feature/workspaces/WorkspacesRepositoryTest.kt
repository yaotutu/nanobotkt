package com.nanobotkt.feature.workspaces

import com.nanobotkt.core.model.DefaultAccessMode
import com.nanobotkt.core.network.AuthContext
import com.nanobotkt.core.network.GatewayApiClient
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.Json
import okhttp3.OkHttpClient
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

class WorkspacesRepositoryTest {
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
    fun refreshSuccessPublishesPayloadAndStopsLoading() = runBlocking {
        server.dispatcher = object : Dispatcher() {
            override fun dispatch(request: RecordedRequest): MockResponse =
                jsonResponse(workspacesJson("/workspace", "Workspace"))
        }

        val repository = DefaultWorkspacesRepository(gateway())

        repository.refresh()

        assertEquals("/workspace", repository.state.value.payload?.defaultScope?.projectPath)
        assertEquals("Workspace", repository.state.value.payload?.defaultScope?.projectName)
        assertFalse(repository.state.value.loading)
        assertNull(repository.state.value.error)
    }

    @Test
    fun updateDefaultAccessModeUsesNetworkSafetyEndpointThenRefreshesWorkspacePayload() = runBlocking {
        val requests = mutableListOf<RecordedRequest>()
        server.dispatcher = object : Dispatcher() {
            override fun dispatch(request: RecordedRequest): MockResponse {
                requests += request
                return when (request.path?.substringBefore('?')) {
                    "/api/settings/network-safety/update" -> jsonResponse("{}")
                    "/api/workspaces" -> jsonResponse(workspacesJson("/workspace", "Workspace", "full"))
                    else -> MockResponse().setResponseCode(404)
                }
            }
        }

        val repository = DefaultWorkspacesRepository(gateway())
        repository.updateDefaultAccessMode(DefaultAccessMode.FULL)

        val updateRequest = requests.first { it.path?.startsWith("/api/settings/network-safety/update") == true }
        assertEquals("full", updateRequest.requestUrl?.queryParameter("webui_default_access_mode"))
        assertEquals("full", repository.state.value.payload?.defaultAccessMode?.name?.lowercase())
        assertFalse(repository.state.value.loading)
        assertNull(repository.state.value.error)
    }

    @Test
    fun resetIgnoresLateRefreshSuccess() = runBlocking {
        val responseStarted = CountDownLatch(1)
        val releaseResponse = CountDownLatch(1)
        server.dispatcher = object : Dispatcher() {
            override fun dispatch(request: RecordedRequest): MockResponse {
                responseStarted.countDown()
                releaseResponse.await(2, TimeUnit.SECONDS)
                return jsonResponse(workspacesJson("/late", "Late"))
            }
        }

        val repository = DefaultWorkspacesRepository(gateway())
        val refresh = async(Dispatchers.IO) { repository.refresh() }
        assertTrue(responseStarted.await(2, TimeUnit.SECONDS))

        repository.reset()
        assertEquals(WorkspacesUiState(), repository.state.value)

        // reset 后旧 refresh 仍可能完成网络和 JSON 解析，但不能把旧 workspace 快照写回新会话。
        releaseResponse.countDown()
        refresh.join()

        assertEquals(WorkspacesUiState(), repository.state.value)
    }

    @Test
    fun resetIgnoresLateRefreshFailure() = runBlocking {
        val responseStarted = CountDownLatch(1)
        val releaseResponse = CountDownLatch(1)
        server.dispatcher = object : Dispatcher() {
            override fun dispatch(request: RecordedRequest): MockResponse {
                responseStarted.countDown()
                releaseResponse.await(2, TimeUnit.SECONDS)
                return MockResponse().setResponseCode(503).setBody("late workspace refresh failure")
            }
        }

        val repository = DefaultWorkspacesRepository(gateway())
        val refresh = async(Dispatchers.IO) { repository.refresh() }
        assertTrue(responseStarted.await(2, TimeUnit.SECONDS))

        repository.reset()
        releaseResponse.countDown()
        refresh.join()

        // Workspaces 没有 action/pending/inFlight；这里重点确认迟到错误不会恢复 error 或 loading。
        assertEquals(WorkspacesUiState(), repository.state.value)
    }

    @Test
    fun refreshFailureExposesErrorAndStopsLoading() = runBlocking {
        server.dispatcher = object : Dispatcher() {
            override fun dispatch(request: RecordedRequest): MockResponse =
                MockResponse().setResponseCode(503).setBody("service unavailable")
        }

        val repository = DefaultWorkspacesRepository(gateway())

        repository.refresh()

        assertNull(repository.state.value.payload)
        assertFalse(repository.state.value.loading)
        assertNotNull(repository.state.value.error)
    }

    @Test
    fun refreshFailureKeepsExistingPayload() = runBlocking {
        val requestCount = AtomicInteger(0)
        server.dispatcher = object : Dispatcher() {
            override fun dispatch(request: RecordedRequest): MockResponse =
                if (requestCount.incrementAndGet() == 1) {
                    jsonResponse(workspacesJson("/existing", "Existing"))
                } else {
                    MockResponse().setResponseCode(500).setBody("temporary failure")
                }
        }

        val repository = DefaultWorkspacesRepository(gateway())
        repository.refresh()
        assertEquals("/existing", repository.state.value.payload?.defaultScope?.projectPath)

        repository.refresh()

        // 已有配置时刷新失败只能更新 error，不能丢失上一次成功的 workspace 作用域。
        assertEquals("/existing", repository.state.value.payload?.defaultScope?.projectPath)
        assertEquals("Existing", repository.state.value.payload?.defaultScope?.projectName)
        assertFalse(repository.state.value.loading)
        assertNotNull(repository.state.value.error)
    }

    @Test
    fun cancelledRefreshRestoresPreviousState() = runBlocking {
        val refreshStarted = CountDownLatch(1)
        val requestCount = AtomicInteger(0)
        server.dispatcher = object : Dispatcher() {
            override fun dispatch(request: RecordedRequest): MockResponse =
                if (requestCount.incrementAndGet() == 1) {
                    jsonResponse(workspacesJson("/existing", "Existing"))
                } else {
                    refreshStarted.countDown()
                    jsonResponse(workspacesJson("/slow", "Slow"))
                        .setBodyDelay(500, TimeUnit.MILLISECONDS)
                }
        }

        val repository = DefaultWorkspacesRepository(gateway())
        repository.refresh()
        val stateBeforeRefresh = repository.state.value

        val refresh = async(Dispatchers.IO) { repository.refresh() }
        assertTrue(refreshStarted.await(2, TimeUnit.SECONDS))

        refresh.cancelAndJoin()

        // 取消属于调用方主动终止，不应被当作普通错误；状态应完整回到刷新前的快照。
        assertTrue(refresh.isCancelled)
        assertEquals(stateBeforeRefresh, repository.state.value)
    }

    @Test
    fun clearErrorOnlyClearsErrorAndPreservesPayload() = runBlocking {
        val requestCount = AtomicInteger(0)
        server.dispatcher = object : Dispatcher() {
            override fun dispatch(request: RecordedRequest): MockResponse =
                if (requestCount.incrementAndGet() == 1) {
                    jsonResponse(workspacesJson("/existing", "Existing"))
                } else {
                    MockResponse().setResponseCode(500).setBody("temporary failure")
                }
        }

        val repository = DefaultWorkspacesRepository(gateway())
        repository.refresh()
        repository.refresh()
        assertNotNull(repository.state.value.error)

        repository.clearError()
        repository.clearError()

        // clearError 应是幂等的，并且只清理错误提示，不改变 payload/loading 等其他状态。
        assertNull(repository.state.value.error)
        assertEquals("/existing", repository.state.value.payload?.defaultScope?.projectPath)
        assertFalse(repository.state.value.loading)
    }

    @Test
    fun newerRefreshCannotBeOverwrittenByOlderResponse() = runBlocking {
        val requestCount = AtomicInteger(0)
        val firstStarted = CountDownLatch(1)
        val secondStarted = CountDownLatch(1)

        server.dispatcher = object : Dispatcher() {
            override fun dispatch(request: RecordedRequest): MockResponse {
                if (request.path != "/api/workspaces") return MockResponse().setResponseCode(404)
                return if (requestCount.incrementAndGet() == 1) {
                    firstStarted.countDown()
                    jsonResponse(workspacesJson("/old", "Old"))
                        .setBodyDelay(600, TimeUnit.MILLISECONDS)
                } else {
                    secondStarted.countDown()
                    jsonResponse(workspacesJson("/new", "New"))
                        .setBodyDelay(100, TimeUnit.MILLISECONDS)
                }
            }
        }

        val repository = DefaultWorkspacesRepository(gateway())

        val first = async(Dispatchers.IO) { repository.refresh() }
        assertTrue(firstStarted.await(2, TimeUnit.SECONDS))

        val second = async(Dispatchers.IO) { repository.refresh() }
        second.join()
        first.join()

        assertEquals("/new", repository.state.value.payload?.defaultScope?.projectPath)
        assertEquals("New", repository.state.value.payload?.defaultScope?.projectName)
        assertFalse(repository.state.value.loading)
    }

    private fun gateway(): GatewayApiClient = GatewayApiClient(
        OkHttpClient(),
        Json { ignoreUnknownKeys = true; explicitNulls = false },
        object : AuthContext {
            override val baseUrl: String = server.url("/").toString()
            override val apiToken: String? = null
        },
    )

    private fun workspacesJson(
        projectPath: String,
        projectName: String,
        defaultAccessMode: String = "default",
    ): String = """
        {
          "schema_version": 1,
          "default_access_mode": "$defaultAccessMode",
          "default_scope": {
            "project_path": "$projectPath",
            "project_name": "$projectName",
            "access_mode": "restricted",
            "restrict_to_workspace": true
          },
          "controls": {
            "can_change_project": true,
            "can_use_full_access": false
          }
        }
    """.trimIndent()

    private fun jsonResponse(body: String): MockResponse = MockResponse()
        .setResponseCode(200)
        .setHeader("Content-Type", "application/json; charset=utf-8")
        .setBody(body)
}
