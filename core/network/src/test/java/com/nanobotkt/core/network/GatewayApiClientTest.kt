package com.nanobotkt.core.network

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import okhttp3.Interceptor
import okhttp3.OkHttpClient
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import okhttp3.mockwebserver.SocketPolicy
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import java.io.IOException
import java.util.concurrent.TimeUnit

class GatewayApiClientTest {
    private lateinit var server: MockWebServer
    private lateinit var endpoint: MutableEndpointProvider
    private lateinit var credentials: FakeCredentialProvider
    private lateinit var json: Json

    @Before
    fun setUp() {
        server = MockWebServer()
        server.start()
        endpoint = MutableEndpointProvider(server.url("/").toString())
        credentials = FakeCredentialProvider("api-token")
        json = Json {
            ignoreUnknownKeys = true
            explicitNulls = false
            encodeDefaults = false
        }
    }

    @After
    fun tearDown() {
        server.shutdown()
    }

    @Test
    fun `get obtains token and preserves explicit empty query parameters`() = runBlocking {
        server.enqueue(jsonResponse("""{"value":"ok"}"""))

        val result = client().get<TestPayload>(
            "/api/items",
            mapOf("a" to 1, "blank" to "", "none" to null, "query" to "a b&c"),
        )

        assertEquals("ok", result.value)
        assertEquals(1, credentials.requestTokenCalls)
        val request = server.takeRequest()
        assertEquals("Bearer api-token", request.getHeader("Authorization"))
        assertEquals("application/json", request.getHeader("Accept"))
        assertEquals("1", request.requestUrl?.queryParameter("a"))
        assertEquals("a b&c", request.requestUrl?.queryParameter("query"))
        assertEquals("", request.requestUrl?.queryParameter("blank"))
        assertNull(request.requestUrl?.queryParameter("none"))
    }

    @Test
    fun `blank provider token fails before network request`() {
        credentials.currentToken = ""

        assertThrows(GatewayException.AuthenticationRequired::class.java) {
            runBlocking { client().get<TestPayload>("/api/items") }
        }

        assertEquals(0, server.requestCount)
    }

    @Test
    fun `gateway unauthorized refreshes token and retries exactly once`() = runBlocking {
        credentials.refreshedToken = "api-token-2"
        server.enqueue(errorResponse(401, """{"error":"Unauthorized"}"""))
        server.enqueue(jsonResponse("""{"value":"recovered"}"""))

        val result = client().get<TestPayload>("/api/items")

        assertEquals("recovered", result.value)
        assertEquals(listOf("api-token"), credentials.rejectedTokens)
        assertEquals("Bearer api-token", server.takeRequest().getHeader("Authorization"))
        assertEquals("Bearer api-token-2", server.takeRequest().getHeader("Authorization"))
        assertEquals(2, server.requestCount)
    }

    @Test
    fun `second gateway unauthorized stops without infinite retry`() {
        credentials.refreshedToken = "api-token-2"
        repeat(2) {
            server.enqueue(errorResponse(401, """{"error":"Unauthorized"}"""))
        }

        assertThrows(GatewayException.AuthenticationRequired::class.java) {
            runBlocking { client().get<TestPayload>("/api/items") }
        }

        assertEquals(listOf("api-token"), credentials.rejectedTokens)
        assertEquals(2, server.requestCount)
    }

    @Test
    fun `provider oauth 401 remains http error and does not refresh gateway token`() {
        server.enqueue(errorResponse(401, """{"error":"OAuth login failed"}"""))

        val error = assertThrows(GatewayException.Http::class.java) {
            runBlocking { client().get<TestPayload>("/api/providers/oauth") }
        }

        assertEquals(401, error.status)
        assertTrue(error.message.orEmpty().contains("OAuth login failed"))
        assertTrue(credentials.rejectedTokens.isEmpty())
        assertEquals(1, server.requestCount)
    }

    @Test
    fun `forbidden remains business http error`() {
        server.enqueue(errorResponse(403, """{"error":"Forbidden"}"""))

        val error = assertThrows(GatewayException.Http::class.java) {
            runBlocking { client().get<TestPayload>("/api/items") }
        }

        assertEquals(403, error.status)
        assertTrue(credentials.rejectedTokens.isEmpty())
    }

    @Test
    fun `caller headers cannot override authorization selected by credential provider`() = runBlocking {
        server.enqueue(jsonResponse("""{"value":"ok"}"""))

        client().request(
            path = "/api/items",
            deserializer = TestPayload.serializer(),
            headers = mapOf("Authorization" to "Bearer stale-token", "X-Test" to "value"),
        )

        val request = server.takeRequest()
        assertEquals("Bearer api-token", request.getHeader("Authorization"))
        assertEquals("value", request.getHeader("X-Test"))
    }

    @Test
    fun `resolve url joins relative media paths and preserves complete uri schemes`() {
        val client = client()
        val baseUrl = endpoint.baseUrl.trimEnd('/')

        // Gateway 历史既可能返回根相对路径，也可能返回不带斜杠的相对路径；两者都必须
        // 使用当前 Gateway origin 补齐，且绝不能把 API Token 拼进媒体地址。
        assertEquals("$baseUrl/api/media/a.png", client.resolveUrl("/api/media/a.png"))
        assertEquals("$baseUrl/api/media/b.png", client.resolveUrl("api/media/b.png"))
        assertTrue(client.resolveUrl("/api/media/a.png").contains("api-token").not())

        assertEquals("https://cdn.example/a.png", client.resolveUrl("  https://cdn.example/a.png  "))
        assertEquals("HTTP://cdn.example/b.png", client.resolveUrl("HTTP://cdn.example/b.png"))
        assertEquals("data:image/png;base64,abc", client.resolveUrl("data:image/png;base64,abc"))
        assertEquals("content://media/external/1", client.resolveUrl("content://media/external/1"))
        assertEquals("file:///tmp/a.png", client.resolveUrl("file:///tmp/a.png"))
        assertEquals("", client.resolveUrl("   "))
    }

    @Test
    fun `post sends json content type and request body`() = runBlocking {
        server.enqueue(jsonResponse("""{"value":"saved"}"""))

        val response = client().post<TestPayload, TestPayload>("/api/items", TestPayload("input"))

        assertEquals("saved", response.value)
        val request = server.takeRequest()
        assertEquals("POST", request.method)
        assertTrue(request.getHeader("Content-Type").orEmpty().startsWith("application/json"))
        assertEquals("{\"value\":\"input\"}", request.body.readUtf8())
    }

    @Test
    fun `http error preserves status and response text`() {
        server.enqueue(errorResponse(422, "invalid settings"))

        val error = assertThrows(GatewayException.Http::class.java) {
            runBlocking { client().get<TestPayload>("/api/items") }
        }

        assertEquals(422, error.status)
        assertTrue(error.message.orEmpty().contains("invalid settings"))
    }

    @Test
    fun `html and non json responses are distinguished`() {
        server.enqueue(MockResponse().setResponseCode(200).setHeader("Content-Type", "text/html").setBody("<html>gateway</html>"))
        assertThrows(GatewayException.HtmlResponse::class.java) {
            runBlocking { client().get<TestPayload>("/api/items") }
        }

        server.enqueue(MockResponse().setResponseCode(200).setHeader("Content-Type", "text/plain").setBody("ok"))
        assertThrows(GatewayException.NonJsonResponse::class.java) {
            runBlocking { client().get<TestPayload>("/api/items") }
        }
    }

    @Test
    fun `invalid json payload is mapped`() {
        server.enqueue(jsonResponse("""{"unexpected":true}"""))

        assertThrows(GatewayException.InvalidPayload::class.java) {
            runBlocking { client().get<TestPayload>("/api/items") }
        }
    }

    @Test
    fun `call timeout is mapped`() {
        server.enqueue(MockResponse().setSocketPolicy(SocketPolicy.NO_RESPONSE))
        val shortClient = OkHttpClient.Builder()
            .callTimeout(100, TimeUnit.MILLISECONDS)
            .connectTimeout(100, TimeUnit.MILLISECONDS)
            .readTimeout(100, TimeUnit.MILLISECONDS)
            .writeTimeout(100, TimeUnit.MILLISECONDS)
            .build()

        assertThrows(GatewayException.Timeout::class.java) {
            runBlocking { GatewayApiClient(shortClient, json, endpoint, credentials).get<TestPayload>("/api/slow") }
        }
    }

    @Test
    fun `ordinary io failure maps to network`() {
        val failingClient = OkHttpClient.Builder()
            .addInterceptor(Interceptor { throw IOException("connection reset") })
            .build()

        val error = assertThrows(GatewayException.Network::class.java) {
            runBlocking {
                GatewayApiClient(failingClient, json, endpoint, credentials).get<TestPayload>("/api/items")
            }
        }

        assertEquals("network_unavailable", error.message)
    }

    @Test
    fun `coroutine cancellation is not mapped to gateway timeout or network`() {
        server.enqueue(MockResponse().setSocketPolicy(SocketPolicy.NO_RESPONSE))

        val error = assertThrows(CancellationException::class.java) {
            runBlocking {
                withTimeout(250) {
                    client().get<TestPayload>("/api/slow")
                }
            }
        }

        assertNotEquals(GatewayException.Timeout::class.java, error::class.java)
        assertNotEquals(GatewayException.Network::class.java, error::class.java)
    }

    private fun client(): GatewayApiClient = GatewayApiClient(OkHttpClient(), json, endpoint, credentials)

    private fun jsonResponse(body: String): MockResponse = MockResponse()
        .setResponseCode(200)
        .setHeader("Content-Type", "application/json; charset=utf-8")
        .setBody(body)

    private fun errorResponse(code: Int, body: String): MockResponse = MockResponse()
        .setResponseCode(code)
        .setHeader("Content-Type", "application/json; charset=utf-8")
        .setBody(body)

    @Serializable
    private data class TestPayload(val value: String)

    private data class MutableEndpointProvider(
        override var baseUrl: String,
    ) : GatewayEndpointProvider

    private class FakeCredentialProvider(
        var currentToken: String,
    ) : ApiCredentialProvider {
        var refreshedToken: String = currentToken
        var requestTokenCalls: Int = 0
            private set
        val rejectedTokens = mutableListOf<String>()

        override suspend fun tokenForRequest(): String {
            requestTokenCalls += 1
            return currentToken
        }

        override suspend fun tokenAfterUnauthorized(rejectedToken: String): String {
            rejectedTokens += rejectedToken
            currentToken = refreshedToken
            return currentToken
        }
    }
}
