package com.nanobotkt.feature.sidebar

import com.nanobotkt.core.model.SidebarStatePayload
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
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import java.util.Collections
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
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
    fun togglePinnedAndArchivedUpdatePayloadAndRefreshState() = runBlocking {
        val key = "webui:folder /中文"
        val phase = AtomicInteger(0)
        val updateRequests = Collections.synchronizedList(mutableListOf<RecordedRequest>())
        server.dispatcher = object : Dispatcher() {
            override fun dispatch(request: RecordedRequest): MockResponse = when {
                request.path?.substringBefore('?') == "/api/webui/sidebar-state/update" -> {
                    updateRequests += request
                    phase.incrementAndGet()
                    jsonResponse("""{"schema_version":1}""")
                }
                request.path == "/api/sessions" -> jsonResponse("""{"sessions":[]}""")
                request.path == "/api/webui/sidebar-state" -> {
                    val state = when (phase.get()) {
                        0 -> SidebarStatePayload()
                        1 -> SidebarStatePayload(pinnedKeys = listOf(key))
                        else -> SidebarStatePayload(archivedKeys = listOf(key))
                    }
                    jsonResponse(json.encodeToString(SidebarStatePayload.serializer(), state))
                }
                else -> MockResponse().setResponseCode(404)
            }
        }

        val repository = newRepository()
        repository.refresh()
        repository.togglePinned(key)
        assertEquals(listOf(key), repository.state.value.sidebar.pinnedKeys)
        assertTrue(repository.state.value.sidebar.archivedKeys.isEmpty())

        repository.toggleArchived(key)
        assertEquals(listOf(key), repository.state.value.sidebar.archivedKeys)
        assertTrue(repository.state.value.sidebar.pinnedKeys.isEmpty())

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
        assertFalse(repository.state.value.pendingKeys.contains(key))
    }

    private fun newRepository(): DefaultSidebarRepository = DefaultSidebarRepository(
        GatewayApiClient(
            OkHttpClient(),
            json,
            object : AuthContext {
                override val baseUrl: String = server.url("/").toString()
                override val apiToken: String? = null
            },
        ),
    )

    private fun jsonResponse(body: String): MockResponse = MockResponse()
        .setResponseCode(200)
        .setHeader("Content-Type", "application/json; charset=utf-8")
        .setBody(body)
}
