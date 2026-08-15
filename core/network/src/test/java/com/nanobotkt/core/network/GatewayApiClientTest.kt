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
    private lateinit var auth: MutableAuthContext
    private lateinit var json: Json

    @Before
    fun setUp() {
        server = MockWebServer()
        server.start()
        auth = MutableAuthContext(server.url("/").toString(), "api-token")
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
    fun `get adds bearer accept and preserves explicit empty query parameters`() = runBlocking {
        server.enqueue(jsonResponse("""{"value":"ok"}"""))
        val client = client()

        val result = client.get<TestPayload>(
            "/api/items",
            mapOf("a" to 1, "blank" to "", "none" to null, "query" to "a b&c"),
        )

        assertEquals("ok", result.value)
        val request = server.takeRequest()
        assertEquals("Bearer api-token", request.getHeader("Authorization"))
        assertEquals("application/json", request.getHeader("Accept"))
        assertEquals("1", request.requestUrl?.queryParameter("a"))
        assertEquals("a b&c", request.requestUrl?.queryParameter("query"))
        assertEquals("", request.requestUrl?.queryParameter("blank"))
        assertNull(request.requestUrl?.queryParameter("none"))
    }

    @Test
    fun `empty token omits authorization`() = runBlocking {
        auth.apiToken = ""
        server.enqueue(jsonResponse("""{"value":"ok"}"""))

        client().get<TestPayload>("/api/items")

        assertNull(server.takeRequest().getHeader("Authorization"))
    }

    @Test
    fun `resolve url joins relative media paths and preserves complete uri schemes`() {
        val client = client()
        val baseUrl = auth.baseUrl.trimEnd('/')

        // Gateway 历史既可能返回根相对路径，也可能返回不带斜杠的相对路径；两者都必须
        // 使用当前认证上下文的 origin 补齐，且绝不能把 API Token 拼进媒体地址。
        assertEquals("$baseUrl/api/media/a.png", client.resolveUrl("/api/media/a.png"))
        assertEquals("$baseUrl/api/media/b.png", client.resolveUrl("api/media/b.png"))
        assertTrue(client.resolveUrl("/api/media/a.png").contains("api-token").not())

        // 已经可直接消费的 URI 不属于 Gateway 相对路径。大小写协议和首尾空白统一归一化，
        // 避免系统 Intent 或图片加载器收到无效的前导空格。
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
        server.enqueue(MockResponse().setResponseCode(422).setBody("invalid settings"))

        val error = assertThrows(GatewayException.Http::class.java) {
            runBlocking { client().get<TestPayload>("/api/items") }
        }

        assertEquals(422, error.status)
        assertTrue(error.message.orEmpty().contains("invalid settings"))
    }

    @Test
    fun `authentication statuses map to authentication required`() {
        server.enqueue(MockResponse().setResponseCode(401).setBody("unauthorized"))

        assertThrows(GatewayException.AuthenticationRequired::class.java) {
            runBlocking { client().get<TestPayload>("/api/items") }
        }
    }

    @Test
    fun `forbidden response maps to authentication required`() {
        // 403 与 401 都表示当前凭据不能继续访问，必须保持统一的认证错误语义。
        server.enqueue(MockResponse().setResponseCode(403).setBody("forbidden"))

        assertThrows(GatewayException.AuthenticationRequired::class.java) {
            runBlocking { client().get<TestPayload>("/api/items") }
        }
    }

    @Test
    fun `rate limit and server errors preserve http status`() {
        // 429/5xx 属于服务端或限流错误，不应被误判成认证失败，方便上层决定重试策略。
        listOf(429, 500, 503).forEach { status ->
            server.enqueue(MockResponse().setResponseCode(status).setBody("status-$status"))

            val error = assertThrows(GatewayException.Http::class.java) {
                runBlocking { client().get<TestPayload>("/api/items") }
            }

            assertEquals(status, error.status)
            assertTrue(error.message.orEmpty().contains("status-$status"))
        }
    }

    @Test
    fun `html success response is rejected`() {
        server.enqueue(
            MockResponse()
                .setResponseCode(200)
                .setHeader("Content-Type", "text/html")
                .setBody("<!doctype html><html><body>gateway</body></html>"),
        )

        assertThrows(GatewayException.HtmlResponse::class.java) {
            runBlocking { client().get<TestPayload>("/api/items") }
        }
    }

    @Test
    fun `non json success response is rejected`() {
        server.enqueue(
            MockResponse()
                .setResponseCode(200)
                .setHeader("Content-Type", "text/plain")
                .setBody("ok"),
        )

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
            runBlocking { GatewayApiClient(shortClient, json, auth).get<TestPayload>("/api/slow") }
        }
    }

    @Test
    fun `ordinary io failure maps to network`() {
        // 普通连接失败不是超时；必须保留 Network 语义，避免上层错误地进入超时提示或重试分支。
        val failingClient = OkHttpClient.Builder()
            .addInterceptor(Interceptor { throw IOException("connection reset") })
            .build()

        val error = assertThrows(GatewayException.Network::class.java) {
            runBlocking {
                GatewayApiClient(failingClient, json, auth).get<TestPayload>("/api/items")
            }
        }

        assertEquals("network_unavailable", error.message)
    }

    @Test
    fun `coroutine cancellation is not mapped to gateway timeout or network`() {
        // withTimeout 触发的是协程取消，不是 OkHttp 请求超时；取消必须原样向调用方传播。
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

    private fun client(): GatewayApiClient = GatewayApiClient(OkHttpClient(), json, auth)

    private fun jsonResponse(body: String): MockResponse = MockResponse()
        .setResponseCode(200)
        .setHeader("Content-Type", "application/json; charset=utf-8")
        .setBody(body)

    @Serializable
    private data class TestPayload(val value: String)

    private data class MutableAuthContext(
        override var baseUrl: String,
        override var apiToken: String?,
    ) : AuthContext
}
