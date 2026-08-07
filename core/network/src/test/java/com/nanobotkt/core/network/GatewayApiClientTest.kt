package com.nanobotkt.core.network

import kotlinx.coroutines.runBlocking
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import okhttp3.OkHttpClient
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import okhttp3.mockwebserver.SocketPolicy
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
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
    fun `get adds bearer accept and encoded nonempty query parameters`() = runBlocking {
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
        assertNull(request.requestUrl?.queryParameter("blank"))
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
