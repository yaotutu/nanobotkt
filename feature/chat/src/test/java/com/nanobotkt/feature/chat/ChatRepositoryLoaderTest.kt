package com.nanobotkt.feature.chat

import com.nanobotkt.core.network.ApiCredentialProvider
import com.nanobotkt.core.network.GatewayEndpointProvider
import com.nanobotkt.core.network.GatewayApiClient
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
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

class ChatRepositoryLoaderTest {
    private lateinit var server: MockWebServer
    private lateinit var client: OkHttpClient
    private lateinit var api: GatewayApiClient

    @Before
    fun setUp() {
        server = MockWebServer()
        server.start()
        client = OkHttpClient()
        api = GatewayApiClient(
            client = client,
            json = Json {
                ignoreUnknownKeys = true
                explicitNulls = false
                encodeDefaults = false
            },
            endpointProvider = object : GatewayEndpointProvider {
                    override val baseUrl: String = server.url("/").toString()
                },
                credentialProvider = object : ApiCredentialProvider {
                    override suspend fun tokenForRequest(): String = "test-api-token"
                    override suspend fun tokenAfterUnauthorized(rejectedToken: String): String = "test-api-token"
                },
        )
    }

    @After
    fun tearDown() {
        client.dispatcher.executorService.shutdownNow()
        client.connectionPool.evictAll()
        server.shutdown()
    }

    @Test
    fun `catalog loader preserves successful partitions when one endpoint fails`() = runBlocking {
        server.dispatcher = object : Dispatcher() {
            override fun dispatch(request: RecordedRequest): MockResponse =
                when (request.requestUrl?.encodedPath) {
                    "/api/commands" -> jsonResponse(
                        """{"commands":[{"command":"help","title":"Help","description":"help","icon":"x","lifecycle":"agent_turn"}]}""",
                    )
                    // 技能端点故意失败；其余目录仍应被解析并交给 Repository 选择性写回。
                    ComposerCatalogLoader.COMPOSER_SKILLS_PATH -> MockResponse().setResponseCode(500)
                    "/api/settings/cli-apps" -> jsonResponse("""{"apps":[]}""")
                    "/api/settings/mcp-presets" -> jsonResponse("""{"presets":[]}""")
                    "/api/settings" -> jsonResponse("{}")
                    else -> MockResponse().setResponseCode(404)
                }
        }

        val result = ComposerCatalogLoader(api).load()

        assertEquals(listOf("help"), result.slashCommands?.map { command -> command.command })
        assertEquals(null, result.skills)
        assertTrue(result.cliApps?.isEmpty() == true)
        assertTrue(result.mcpPresets?.isEmpty() == true)
        assertTrue(result.settings != null)
        assertFalse(result.complete)
    }

    @Test
    fun `file preview generation only accepts latest request`() {
        val loader = ChatFilePreviewLoader(api)

        val first = loader.beginRequest()
        val second = loader.beginRequest()

        assertFalse(loader.isCurrent(first))
        assertTrue(loader.isCurrent(second))
        loader.invalidate()
        assertFalse(loader.isCurrent(second))
    }

    private fun jsonResponse(body: String): MockResponse = MockResponse()
        .setResponseCode(200)
        .setHeader("Content-Type", "application/json")
        .setBody(body)
}
