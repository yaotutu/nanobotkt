package com.nanobotkt.core.transport

import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import kotlinx.coroutines.withTimeoutOrNull
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import okhttp3.OkHttpClient
import okhttp3.Response
import okhttp3.WebSocket
import okhttp3.WebSocketListener
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import org.junit.After
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Before
import org.junit.Test
import java.util.concurrent.atomic.AtomicReference

class NanobotTransportAcceptanceTest {
    private lateinit var server: MockWebServer
    private lateinit var client: OkHttpClient
    private lateinit var transport: NanobotTransport
    private val receivedFrames = Channel<String>(Channel.UNLIMITED)
    private val serverSocket = AtomicReference<WebSocket?>()
    private val json = Json {
        ignoreUnknownKeys = true
        explicitNulls = false
        encodeDefaults = false
    }

    @Before
    fun setUp() {
        server = MockWebServer()
        server.start()
        client = OkHttpClient()
        transport = NanobotTransport(
            client = client,
            json = json,
            credentials = TestCredentials(server.url("/ws").toString()),
        )
    }

    @After
    fun tearDown() {
        transport.close()
        serverSocket.getAndSet(null)?.close(1000, "test_done")
        client.dispatcher.executorService.shutdownNow()
        client.connectionPool.evictAll()
        server.shutdown()
    }

    @Test
    fun `goal running fallback does not accept side channel message`() = runBlocking {
        connectWebSocket()

        val result = transport.sendMessage(
            chatId = "chat-1",
            content = "/status",
            startsNewRun = false,
        )
        val frame = json.parseToJsonElement(withTimeout(2_000) { receivedFrames.receive() }).jsonObject
        val turnId = frame.getValue("turn_id").jsonPrimitive.content
        assertFalse(frame.containsKey("starts_new_run"))

        serverSocket.get()!!.send(
            """{"event":"goal_status","chat_id":"chat-1","status":"running"}""",
        )
        assertNull(withTimeoutOrNull(150) { result.accepted.await() })

        serverSocket.get()!!.send(
            """{"event":"delta","chat_id":"chat-1","text":"ok","turn_id":"$turnId"}""",
        )
        withTimeout(2_000) { result.accepted.await() }
    }

    @Test
    fun `goal running fallback accepts normal new run message`() = runBlocking {
        connectWebSocket()

        val result = transport.sendMessage(
            chatId = "chat-1",
            content = "hello",
            startsNewRun = true,
        )
        withTimeout(2_000) { receivedFrames.receive() }

        serverSocket.get()!!.send(
            """{"event":"goal_status","chat_id":"chat-1","status":"running"}""",
        )

        withTimeout(2_000) { result.accepted.await() }
    }

    private suspend fun connectWebSocket() {
        server.enqueue(
            MockResponse().withWebSocketUpgrade(
                object : WebSocketListener() {
                    override fun onOpen(webSocket: WebSocket, response: Response) {
                        serverSocket.set(webSocket)
                    }

                    override fun onMessage(webSocket: WebSocket, text: String) {
                        receivedFrames.trySend(text)
                    }
                },
            ),
        )
        transport.connect()
        withTimeout(2_000) {
            transport.state.first { it.status == TransportStatus.OPEN }
        }
    }

    private data class TestCredentials(
        val url: String,
    ) : TransportCredentials {
        override fun currentWebSocketUrl(): String = url
        override suspend fun reauthenticateWebSocketUrl(): String = url
        override fun maxFrameBytes(): Int? = null
    }
}
