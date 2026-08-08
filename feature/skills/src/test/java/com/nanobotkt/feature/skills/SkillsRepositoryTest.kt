package com.nanobotkt.feature.skills

import com.nanobotkt.core.network.AuthContext
import com.nanobotkt.core.network.GatewayApiClient
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.cancelAndJoin
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
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger

class SkillsRepositoryTest {
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
    fun resetIgnoresLateRefreshSuccessAndClearsState() = runBlocking {
        val requestStarted = CountDownLatch(1)
        val responseRelease = CountDownLatch(1)
        server.dispatcher = object : Dispatcher() {
            override fun dispatch(request: RecordedRequest): MockResponse =
                if (request.path == "/api/webui/skills") {
                    requestStarted.countDown()
                    assertTrue(responseRelease.await(2, TimeUnit.SECONDS))
                    jsonResponse("""{"skills":[{"name":"stale"}]}""")
                } else {
                    MockResponse().setResponseCode(404)
                }
        }

        val repository = DefaultSkillsRepository(gateway())
        val refresh = async(Dispatchers.IO) { repository.refresh() }
        assertTrue(requestStarted.await(2, TimeUnit.SECONDS))
        assertTrue(repository.state.value.loading)

        // reset 发生在 refresh 返回前；旧会话的 payload、error 和 loading 都不能写回新状态。
        repository.reset()
        assertEquals(SkillsUiState(), repository.state.value)
        responseRelease.countDown()
        withTimeout(2_000) { refresh.await() }

        assertEquals(SkillsUiState(), repository.state.value)
    }

    @Test
    fun resetIgnoresLateRefreshFailureAndClearsError() = runBlocking {
        val requestStarted = CountDownLatch(1)
        val responseRelease = CountDownLatch(1)
        server.dispatcher = object : Dispatcher() {
            override fun dispatch(request: RecordedRequest): MockResponse =
                if (request.path == "/api/webui/skills") {
                    requestStarted.countDown()
                    assertTrue(responseRelease.await(2, TimeUnit.SECONDS))
                    MockResponse().setResponseCode(503).setBody("stale refresh failure")
                } else {
                    MockResponse().setResponseCode(404)
                }
        }

        val repository = DefaultSkillsRepository(gateway())
        val refresh = async(Dispatchers.IO) { repository.refresh() }
        assertTrue(requestStarted.await(2, TimeUnit.SECONDS))
        assertTrue(repository.state.value.loading)

        // 迟到的 HTTP 错误也属于旧会话结果，reset 后不能重新暴露 error 或 loading。
        repository.reset()
        assertEquals(SkillsUiState(), repository.state.value)
        responseRelease.countDown()
        withTimeout(2_000) { refresh.await() }

        assertEquals(SkillsUiState(), repository.state.value)
    }

    @Test
    fun resetIgnoresLateSelectionSuccessAndClearsSelection() = runBlocking {
        val requestStarted = CountDownLatch(1)
        val responseRelease = CountDownLatch(1)
        server.dispatcher = object : Dispatcher() {
            override fun dispatch(request: RecordedRequest): MockResponse =
                if (request.path == "/api/webui/skills/stale") {
                    requestStarted.countDown()
                    assertTrue(responseRelease.await(2, TimeUnit.SECONDS))
                    jsonResponse("""{"name":"stale"}""")
                } else {
                    MockResponse().setResponseCode(404)
                }
        }

        val repository = DefaultSkillsRepository(gateway())
        val selection = async(Dispatchers.IO) { repository.select("stale") }
        assertTrue(requestStarted.await(2, TimeUnit.SECONDS))
        assertTrue(repository.state.value.detailLoading)

        // reset 必须同时清掉详情选择和 detailLoading，迟到的详情响应不能重新打开旧页面。
        repository.reset()
        assertEquals(SkillsUiState(), repository.state.value)
        responseRelease.countDown()
        withTimeout(2_000) { selection.await() }

        assertEquals(SkillsUiState(), repository.state.value)
    }

    @Test
    fun resetIgnoresLateSelectionFailureAndClearsError() = runBlocking {
        val requestStarted = CountDownLatch(1)
        val responseRelease = CountDownLatch(1)
        server.dispatcher = object : Dispatcher() {
            override fun dispatch(request: RecordedRequest): MockResponse =
                if (request.path == "/api/webui/skills/stale") {
                    requestStarted.countDown()
                    assertTrue(responseRelease.await(2, TimeUnit.SECONDS))
                    MockResponse().setResponseCode(503).setBody("stale selection failure")
                } else {
                    MockResponse().setResponseCode(404)
                }
        }

        val repository = DefaultSkillsRepository(gateway())
        val selection = async(Dispatchers.IO) { repository.select("stale") }
        assertTrue(requestStarted.await(2, TimeUnit.SECONDS))
        assertTrue(repository.state.value.detailLoading)

        // 迟到的详情异常不能跨过 reset 恢复 error、selection 或 detailLoading。
        repository.reset()
        assertEquals(SkillsUiState(), repository.state.value)
        responseRelease.countDown()
        withTimeout(2_000) { selection.await() }

        assertEquals(SkillsUiState(), repository.state.value)
    }

    @Test
    fun refreshSuccessPublishesPayloadAndStopsLoading() = runBlocking {
        server.dispatcher = dispatcherFor { requestCount ->
            assertEquals(1, requestCount)
            jsonResponse("""{"skills":[{"name":"calendar","description":"Calendar tools"}]}""")
        }

        val repository = DefaultSkillsRepository(gateway())

        repository.refresh()

        assertEquals("calendar", repository.state.value.skills?.skills?.single()?.name)
        assertFalse(repository.state.value.loading)
        assertNull(repository.state.value.error)
    }

    @Test
    fun refreshFailureExposesErrorAndStopsLoading() = runBlocking {
        server.dispatcher = object : Dispatcher() {
            override fun dispatch(request: RecordedRequest): MockResponse =
                MockResponse().setResponseCode(503).setBody("service unavailable")
        }

        val repository = DefaultSkillsRepository(gateway())

        repository.refresh()

        assertNull(repository.state.value.skills)
        assertFalse(repository.state.value.loading)
        assertNotNull(repository.state.value.error)
    }

    @Test
    fun refreshFailureKeepsExistingPayload() = runBlocking {
        val requestCount = AtomicInteger(0)
        server.dispatcher = object : Dispatcher() {
            override fun dispatch(request: RecordedRequest): MockResponse =
                if (requestCount.incrementAndGet() == 1) {
                    jsonResponse("""{"skills":[{"name":"existing"}]}""")
                } else {
                    MockResponse().setResponseCode(500).setBody("temporary failure")
                }
        }

        val repository = DefaultSkillsRepository(gateway())
        repository.refresh()
        assertEquals("existing", repository.state.value.skills?.skills?.single()?.name)

        repository.refresh()

        // 刷新失败只暴露错误，不应把上一次成功加载的数据清空，避免页面从可用状态退化为空白。
        assertEquals("existing", repository.state.value.skills?.skills?.single()?.name)
        assertFalse(repository.state.value.loading)
        assertNotNull(repository.state.value.error)
    }

    @Test
    fun newerRefreshCannotBeOverwrittenByOlderResponse() = runBlocking {
        val firstStarted = CountDownLatch(1)
        val secondStarted = CountDownLatch(1)
        val requestCount = AtomicInteger(0)
        server.dispatcher = object : Dispatcher() {
            override fun dispatch(request: RecordedRequest): MockResponse =
                when (requestCount.incrementAndGet()) {
                    1 -> {
                        firstStarted.countDown()
                        jsonResponse("""{"skills":[{"name":"old"}]}""")
                            .setBodyDelay(600, TimeUnit.MILLISECONDS)
                    }
                    2 -> {
                        secondStarted.countDown()
                        jsonResponse("""{"skills":[{"name":"new"}]}""")
                            .setBodyDelay(100, TimeUnit.MILLISECONDS)
                    }
                    else -> MockResponse().setResponseCode(500)
                }
        }

        val repository = DefaultSkillsRepository(gateway())
        val first = async(Dispatchers.IO) { repository.refresh() }
        assertTrue(firstStarted.await(2, TimeUnit.SECONDS))
        val second = async(Dispatchers.IO) { repository.refresh() }
        assertTrue(secondStarted.await(2, TimeUnit.SECONDS))

        second.join()
        first.join()

        // 第二次刷新较快完成后，第一次迟到的旧响应不能把列表改回 old。
        assertEquals("new", repository.state.value.skills?.skills?.single()?.name)
        assertFalse(repository.state.value.loading)
        assertNull(repository.state.value.error)
    }

    @Test
    fun cancelledRefreshRestoresPreviousState() = runBlocking {
        val refreshStarted = CountDownLatch(1)
        val requestCount = AtomicInteger(0)
        server.dispatcher = object : Dispatcher() {
            override fun dispatch(request: RecordedRequest): MockResponse =
                if (requestCount.incrementAndGet() == 1) {
                    jsonResponse("""{"skills":[{"name":"existing"}]}""")
                } else {
                    refreshStarted.countDown()
                    jsonResponse("""{"skills":[{"name":"slow"}]}""")
                        .setBodyDelay(500, TimeUnit.MILLISECONDS)
                }
        }

        val repository = DefaultSkillsRepository(gateway())
        repository.refresh()
        val stateBeforeRefresh = repository.state.value

        val refresh = async(Dispatchers.IO) { repository.refresh() }
        assertTrue(refreshStarted.await(2, TimeUnit.SECONDS))

        refresh.cancelAndJoin()

        // Repository 对 CancellationException 采用回滚语义；取消不能留下 loading=true 或清掉旧 payload。
        assertTrue(refresh.isCancelled)
        assertEquals(stateBeforeRefresh, repository.state.value)
    }

    @Test
    fun clearSelectionClearsInFlightDetailAndIgnoresLateResponse() = runBlocking {
        val detailStarted = CountDownLatch(1)
        server.dispatcher = object : Dispatcher() {
            override fun dispatch(request: RecordedRequest): MockResponse = when (request.path) {
                "/api/webui/skills/old" -> jsonResponse("""{"name":"old"}""")
                "/api/webui/skills/slow" -> {
                    detailStarted.countDown()
                    jsonResponse("""{"name":"slow"}""")
                        .setBodyDelay(300, TimeUnit.MILLISECONDS)
                }
                else -> MockResponse().setResponseCode(404)
            }
        }

        val repository = DefaultSkillsRepository(gateway())
        repository.select("old")
        assertEquals("old", repository.state.value.selected?.name)

        val detail = async(Dispatchers.IO) { repository.select("slow") }
        assertTrue(detailStarted.await(2, TimeUnit.SECONDS))

        repository.clearSelection()
        assertNull(repository.state.value.selected)
        assertFalse(repository.state.value.detailLoading)

        detail.join()

        // clearSelection 会递增代次，迟到的旧响应即使成功也不能重新打开详情。
        assertNull(repository.state.value.selected)
        assertFalse(repository.state.value.detailLoading)
    }

    @Test
    fun selectEncodesSkillNameAsPathSegment() = runBlocking {
        server.enqueue(jsonResponse("""{"name":"skill/a b+中文"}"""))

        val repository = DefaultSkillsRepository(gateway())
        repository.select("skill/a b+中文")

        val request = server.takeRequest()
        assertEquals("/api/webui/skills/skill%2Fa%20b%2B%E4%B8%AD%E6%96%87", request.path)
        assertEquals("skill/a b+中文", repository.state.value.selected?.name)
    }

    @Test
    fun clearSelectionClearsDetailError() = runBlocking {
        server.enqueue(MockResponse().setResponseCode(503).setBody("detail unavailable"))

        val repository = DefaultSkillsRepository(gateway())
        repository.select("broken")

        assertNotNull(repository.state.value.error)
        repository.clearSelection()

        assertNull(repository.state.value.selected)
        assertFalse(repository.state.value.detailLoading)
        assertNull(repository.state.value.error)
    }

    @Test
    fun newerSelectionCannotBeOverwrittenByOlderResponseAndClearsPreviousDetail() = runBlocking {
        val firstStarted = CountDownLatch(1)
        val secondStarted = CountDownLatch(1)

        server.dispatcher = object : Dispatcher() {
            override fun dispatch(request: RecordedRequest): MockResponse = when (request.path) {
                "/api/webui/skills/old" -> jsonResponse("""{"name":"old"}""")
                "/api/webui/skills/slow" -> {
                    firstStarted.countDown()
                    jsonResponse("""{"name":"slow"}""")
                        .setBodyDelay(600, TimeUnit.MILLISECONDS)
                }
                "/api/webui/skills/new" -> {
                    secondStarted.countDown()
                    jsonResponse("""{"name":"new"}""")
                        .setBodyDelay(100, TimeUnit.MILLISECONDS)
                }
                else -> MockResponse().setResponseCode(404)
            }
        }

        val repository = DefaultSkillsRepository(gateway())

        repository.select("old")
        assertEquals("old", repository.state.value.selected?.name)

        val first = async(Dispatchers.IO) { repository.select("slow") }
        assertTrue(firstStarted.await(2, TimeUnit.SECONDS))

        val second = async(Dispatchers.IO) { repository.select("new") }
        assertTrue(secondStarted.await(2, TimeUnit.SECONDS))

        // 第二次选择开始后，旧详情必须立即清理；此时只能显示新的加载状态。
        assertNull(repository.state.value.selected)
        assertTrue(repository.state.value.detailLoading)

        second.join()
        first.join()

        assertEquals("new", repository.state.value.selected?.name)
        assertFalse(repository.state.value.detailLoading)
    }

    private fun gateway(): GatewayApiClient = GatewayApiClient(
        OkHttpClient(),
        Json { ignoreUnknownKeys = true; explicitNulls = false },
        object : AuthContext {
            override val baseUrl: String = server.url("/").toString()
            override val apiToken: String? = null
        },
    )

    private fun dispatcherFor(response: (requestCount: Int) -> MockResponse): Dispatcher =
        object : Dispatcher() {
            private val requestCount = AtomicInteger(0)

            override fun dispatch(request: RecordedRequest): MockResponse =
                response(requestCount.incrementAndGet())
        }

    private fun jsonResponse(body: String): MockResponse = MockResponse()
        .setResponseCode(200)
        .setHeader("Content-Type", "application/json; charset=utf-8")
        .setBody(body)
}
