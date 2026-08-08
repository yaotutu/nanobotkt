package com.nanobotkt.core.network

import com.nanobotkt.core.model.BootstrapResponse
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import kotlinx.serialization.json.Json
import okhttp3.Interceptor
import okhttp3.OkHttpClient
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.SocketPolicy
import okhttp3.mockwebserver.MockWebServer
import org.junit.After
import org.junit.Before
import org.junit.Test
import java.io.IOException
import java.io.InterruptedIOException
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertThrows

class BootstrapServiceTest {
    private lateinit var server: MockWebServer
    private lateinit var json: Json

    @Before
    fun setUp() {
        server = MockWebServer()
        server.start()
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
    fun `fetch requests webui bootstrap and sends nonblank secret`() = runBlocking {
        server.enqueue(jsonResponse(bootstrapJson()))

        val result = service().fetch(server.url("/gateway/").toString(), "bootstrap-secret")
        val request = server.takeRequest()

        assertEquals("/gateway/webui/bootstrap", request.requestUrl?.encodedPath)
        assertEquals("application/json", request.getHeader("Accept"))
        assertEquals("bootstrap-secret", request.getHeader("X-Nanobot-Auth"))
        assertEquals("session-token", result.token)
        assertEquals("api-token", result.apiToken)
        assertEquals("/ws", result.wsPath)
    }

    @Test
    fun `fetch omits auth header for blank secret`() = runBlocking {
        server.enqueue(jsonResponse(bootstrapJson()))

        service().fetch(server.url("/").toString(), "  ")

        assertNull(server.takeRequest().getHeader("X-Nanobot-Auth"))
    }

    @Test
    fun `forbidden bootstrap maps to authentication required`() {
        // Bootstrap 发生 403 时不能继续沿用旧会话，必须让认证层回到可重试状态。
        server.enqueue(MockResponse().setResponseCode(403).setBody("forbidden"))

        assertThrows(GatewayException.AuthenticationRequired::class.java) {
            runBlocking { service().fetch(server.url("/").toString(), "secret") }
        }
    }

    @Test
    fun `rate limit and server errors preserve bootstrap http status`() {
        // 429/5xx 要保留原始状态码，后续重连策略才有机会区分暂时性故障。
        listOf(429, 500, 503).forEach { status ->
            server.enqueue(MockResponse().setResponseCode(status).setBody("status-$status"))

            val error = assertThrows(GatewayException.Http::class.java) {
                runBlocking { service().fetch(server.url("/").toString(), "secret") }
            }

            assertEquals(status, error.status)
            assertEquals("status-$status", error.message)
        }
    }

    @Test
    fun `interrupted io is classified as timeout`() {
        val timeoutClient = OkHttpClient.Builder()
            .addInterceptor(Interceptor { throw InterruptedIOException("timeout") })
            .build()
        val error = assertThrows(GatewayException.Timeout::class.java) {
            runBlocking { BootstrapService(timeoutClient, json).fetch(server.url("/").toString(), "secret") }
        }

        assertEquals("timeout", error.message)
    }

    @Test
    fun `ordinary io is classified as network`() {
        // Bootstrap 的普通连接失败必须与超时区分，便于上层展示正确的网络错误并决定是否重试。
        val failingClient = OkHttpClient.Builder()
            .addInterceptor(Interceptor { throw IOException("connection reset") })
            .build()

        val error = assertThrows(GatewayException.Network::class.java) {
            runBlocking {
                BootstrapService(failingClient, json).fetch(server.url("/").toString(), "secret")
            }
        }

        assertEquals("network_unavailable", error.message)
    }

    @Test
    fun `coroutine cancellation is not classified as timeout or network`() {
        // withTimeout 取消的是挂起协程，不应被 Bootstrap 的 IOException 分类逻辑改写成网关错误。
        server.enqueue(MockResponse().setSocketPolicy(SocketPolicy.NO_RESPONSE))

        val error = assertThrows(CancellationException::class.java) {
            runBlocking {
                withTimeout(250) {
                    service().fetch(server.url("/").toString(), "secret")
                }
            }
        }

        assertNotEquals(GatewayException.Timeout::class.java, error::class.java)
        assertNotEquals(GatewayException.Network::class.java, error::class.java)
    }

    @Test
    fun `incomplete bootstrap payload is mapped to invalid payload`() {
        // 缺少协议必需字段时属于响应契约损坏，应报告 InvalidPayload，而不是伪造可用会话凭据。
        server.enqueue(jsonResponse("""{"token":"session-token","ws_path":"/ws","expires_in":60}"""))

        val error = assertThrows(GatewayException.InvalidPayload::class.java) {
            runBlocking { service().fetch(server.url("/").toString(), "secret") }
        }

        assertNotNull(error.cause)
    }

    @Test
    fun `blank bootstrap credential is rejected as authentication required`() {
        // 字段存在但为空表示服务端没有下发可用凭据，保持现有认证失败语义。
        server.enqueue(jsonResponse("""{"token":"","api_token":"api-token","ws_path":"/ws","expires_in":60}"""))

        val error = assertThrows(GatewayException.AuthenticationRequired::class.java) {
            runBlocking { service().fetch(server.url("/").toString(), "secret") }
        }

        assertEquals("bootstrap response missing credentials", error.message)
    }

    @Test
    fun `malformed bootstrap json is mapped to invalid payload`() {
        // HTTP 成功但 JSON 语法损坏时，不能把解析失败误报为认证或网络问题。
        server.enqueue(jsonResponse("{not-json"))

        val error = assertThrows(GatewayException.InvalidPayload::class.java) {
            runBlocking { service().fetch(server.url("/").toString(), "secret") }
        }

        assertNotNull(error.cause)
    }

    @Test
    fun `derive websocket url encodes token and maps websocket scheme`() {
        val payload = BootstrapResponse(
            token = "a token&value",
            apiToken = "api",
            wsPath = "/ignored",
            wsUrl = "wss://gateway.example/ws/socket",
            expiresIn = 60,
        )

        val result = service().deriveWebSocketUrl("http://localhost:8765", payload)

        assertEquals("https://gateway.example/ws/socket?token=a%20token%26value", result)
    }

    @Test
    fun `derive websocket url falls back to base url and bootstrap path`() {
        // 旧版或精简 Bootstrap 可能只返回 ws_path；此时必须从网关地址稳定构造可连接 URL。
        val payload = BootstrapResponse(
            token = "session token",
            apiToken = "api",
            wsPath = "socket",
            wsUrl = null,
            expiresIn = 60,
        )

        val result = service().deriveWebSocketUrl("http://gateway.example:8765/gateway/", payload)

        assertEquals("http://gateway.example:8765/socket?token=session%20token", result)
    }

    private fun service(client: OkHttpClient = OkHttpClient()): BootstrapService =
        BootstrapService(client, json)

    private fun jsonResponse(body: String): MockResponse = MockResponse()
        .setResponseCode(200)
        .setHeader("Content-Type", "application/json; charset=utf-8")
        .setBody(body)

    private fun bootstrapJson(): String =
        """{"token":"session-token","api_token":"api-token","ws_path":"/ws","expires_in":60}"""
}
