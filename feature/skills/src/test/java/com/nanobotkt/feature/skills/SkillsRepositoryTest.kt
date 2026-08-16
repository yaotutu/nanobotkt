package com.nanobotkt.feature.skills

import com.nanobotkt.core.network.ApiCredentialProvider
import com.nanobotkt.core.network.GatewayEndpointProvider
import com.nanobotkt.core.network.GatewayApiClient
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger
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

class SkillsRepositoryTest {
    private lateinit var server: MockWebServer
    private lateinit var client: OkHttpClient

    @Before
    fun setUp() {
        server = MockWebServer()
        server.start()
        client = OkHttpClient()
    }

    @After
    fun tearDown() {
        client.dispatcher.executorService.shutdownNow()
        client.connectionPool.evictAll()
        server.shutdown()
    }

    @Test
    fun resetIgnoresLateRefreshSuccessAndClearsState() = runBlocking {
        val requestStarted = CountDownLatch(1)
        val responseRelease = CountDownLatch(1)
        server.dispatcher =
            object : Dispatcher() {
                override fun dispatch(request: RecordedRequest): MockResponse {
                    requestStarted.countDown()
                    assertTrue(responseRelease.await(2, TimeUnit.SECONDS))
                    return jsonResponse("""{"skills":[{"name":"stale"}]}""")
                }
            }

        val repository = DefaultSkillsRepository(gateway())
        val refresh = async(Dispatchers.IO) { repository.refresh() }
        assertTrue(requestStarted.await(2, TimeUnit.SECONDS))
        assertTrue(repository.state.value.loading)

        repository.reset()
        val resetGeneration = repository.state.value.sessionGeneration
        responseRelease.countDown()
        withTimeout(2_000) { refresh.await() }

        // 旧请求不能覆盖 reset 产生的新 generation，也不能恢复 payload/loading/error。
        assertEquals(
            SkillsRepositoryState(sessionGeneration = resetGeneration),
            repository.state.value,
        )
    }

    @Test
    fun resetIgnoresLateRefreshFailureAndClearsError() = runBlocking {
        val requestStarted = CountDownLatch(1)
        val responseRelease = CountDownLatch(1)
        server.dispatcher =
            object : Dispatcher() {
                override fun dispatch(request: RecordedRequest): MockResponse {
                    requestStarted.countDown()
                    assertTrue(responseRelease.await(2, TimeUnit.SECONDS))
                    return MockResponse().setResponseCode(503).setBody("stale failure")
                }
            }

        val repository = DefaultSkillsRepository(gateway())
        val refresh = async(Dispatchers.IO) { repository.refresh() }
        assertTrue(requestStarted.await(2, TimeUnit.SECONDS))
        repository.reset()
        val resetGeneration = repository.state.value.sessionGeneration
        responseRelease.countDown()
        withTimeout(2_000) { refresh.await() }

        assertEquals(
            SkillsRepositoryState(sessionGeneration = resetGeneration),
            repository.state.value,
        )
    }

    @Test
    fun refreshSuccessPublishesPayloadAndStopsLoading() = runBlocking {
        server.enqueue(jsonResponse("""{"skills":[{"name":"calendar"}]}"""))
        val repository = DefaultSkillsRepository(gateway())

        repository.refresh()

        assertEquals("calendar", repository.state.value.skills?.skills?.single()?.name)
        assertFalse(repository.state.value.loading)
        assertNull(repository.state.value.error)
    }

    @Test
    fun refreshFailureKeepsExistingPayloadAndExposesError() = runBlocking {
        server.enqueue(jsonResponse("""{"skills":[{"name":"existing"}]}"""))
        server.enqueue(MockResponse().setResponseCode(503).setBody("temporary failure"))
        val repository = DefaultSkillsRepository(gateway())
        repository.refresh()

        repository.refresh()

        // 暂时失败时保留最近一次成功快照，列表不会从可用状态退化为空白。
        assertEquals("existing", repository.state.value.skills?.skills?.single()?.name)
        assertFalse(repository.state.value.loading)
        assertNotNull(repository.state.value.error)
    }

    @Test
    fun newerRefreshCannotBeOverwrittenByOlderResponse() = runBlocking {
        val firstStarted = CountDownLatch(1)
        val secondStarted = CountDownLatch(1)
        val count = AtomicInteger(0)
        server.dispatcher =
            object : Dispatcher() {
                override fun dispatch(request: RecordedRequest): MockResponse =
                    when (count.incrementAndGet()) {
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

        assertEquals("new", repository.state.value.skills?.skills?.single()?.name)
        assertFalse(repository.state.value.loading)
    }

    @Test
    fun cancelledRefreshRestoresPreviousSnapshot() = runBlocking {
        server.enqueue(jsonResponse("""{"skills":[{"name":"existing"}]}"""))
        server.enqueue(
            jsonResponse("""{"skills":[{"name":"cancelled"}]}""").setBodyDelay(2, TimeUnit.SECONDS)
        )
        val repository = DefaultSkillsRepository(gateway())
        repository.refresh()

        val refresh = async(Dispatchers.IO) { repository.refresh() }
        // 等待第二个请求已经到达，确保取消覆盖的是在途刷新而不是尚未调度的 Job。
        withTimeout(2_000) { server.takeRequest() }
        withTimeout(2_000) { server.takeRequest() }
        refresh.cancelAndJoin()

        assertEquals("existing", repository.state.value.skills?.skills?.single()?.name)
        assertFalse(repository.state.value.loading)
        assertNull(repository.state.value.error)
    }

    @Test
    fun loadDetailEncodesSkillNameAsSinglePathSegment() = runBlocking {
        server.enqueue(jsonResponse("""{"name":"skill/a b+中文"}"""))
        val repository = DefaultSkillsRepository(gateway())

        val detail = repository.loadDetail("skill/a b+中文")

        assertEquals("skill/a b+中文", detail.name)
        assertEquals(
            "/api/webui/skills/skill%2Fa%20b%2B%E4%B8%AD%E6%96%87",
            server.takeRequest().path,
        )
    }

    private fun gateway(): GatewayApiClient =
        GatewayApiClient(
            client = client,
            json =
                Json {
                    ignoreUnknownKeys = true
                    explicitNulls = false
                },
            endpointProvider = object : GatewayEndpointProvider {
                    override val baseUrl: String = server.url("/").toString()
                },
                credentialProvider = object : ApiCredentialProvider {
                    override suspend fun tokenForRequest(): String = "test-api-token"
                    override suspend fun tokenAfterUnauthorized(rejectedToken: String): String = "test-api-token"
                },
        )

    private fun jsonResponse(body: String): MockResponse =
        MockResponse()
            .setResponseCode(200)
            .setHeader("Content-Type", "application/json; charset=utf-8")
            .setBody(body)
}
