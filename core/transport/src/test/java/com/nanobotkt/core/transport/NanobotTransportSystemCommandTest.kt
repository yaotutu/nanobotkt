package com.nanobotkt.core.transport

import com.nanobotkt.core.model.InboundEvent
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.async
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import kotlinx.coroutines.withTimeoutOrNull
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.boolean
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import okhttp3.OkHttpClient
import okhttp3.Response
import okhttp3.WebSocket
import okhttp3.WebSocketListener
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import java.util.concurrent.atomic.AtomicReference

class NanobotTransportSystemCommandTest {
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
    fun `system command uses exact message wire frame and message response stays private`() = runBlocking {
        connectWebSocket()
        val emitted = Channel<InboundEvent>(Channel.UNLIMITED)
        val collector = launch(start = CoroutineStart.UNDISPATCHED) {
            transport.events.collect { emitted.send(it) }
        }

        val request = async { transport.sendSystemCommand("chat-id", "/model fast") }
        val payload = json.parseToJsonElement(withTimeout(2_000) { receivedFrames.receive() }).jsonObject
        val turnId = payload.getValue("turn_id").jsonPrimitive.content

        assertEquals("message", payload.getValue("type").jsonPrimitive.content)
        assertEquals("chat-id", payload.getValue("chat_id").jsonPrimitive.content)
        assertEquals("/model fast", payload.getValue("content").jsonPrimitive.content)
        assertTrue(turnId.startsWith("webui-system:"))
        assertTrue(payload.getValue("webui").jsonPrimitive.boolean)

        serverSocket.get()!!.send(
            """{"event":"message","chat_id":"chat-id","text":"ok","turn_id":"$turnId"}""",
        )
        withTimeout(2_000) { request.await() }
        assertNull(withTimeoutOrNull(150) { emitted.receive() })
        collector.cancel()
    }

    @Test
    fun `turn end resolves system command without emitting a chat event`() = runBlocking {
        connectWebSocket()
        val emitted = Channel<InboundEvent>(Channel.UNLIMITED)
        val collector = launch(start = CoroutineStart.UNDISPATCHED) {
            transport.events.collect { emitted.send(it) }
        }

        val request = async { transport.sendSystemCommand("chat-id", "/model fast") }
        val payload = json.parseToJsonElement(withTimeout(2_000) { receivedFrames.receive() }).jsonObject
        val turnId = payload.getValue("turn_id").jsonPrimitive.content
        serverSocket.get()!!.send(
            """{"event":"turn_end","chat_id":"chat-id","turn_id":"$turnId"}""",
        )

        withTimeout(2_000) { request.await() }
        assertNull(withTimeoutOrNull(150) { emitted.receive() })
        collector.cancel()
    }

    @Test
    fun `system error rejects command and is not emitted`() = runBlocking {
        connectWebSocket()
        val emitted = Channel<InboundEvent>(Channel.UNLIMITED)
        val collector = launch(start = CoroutineStart.UNDISPATCHED) {
            transport.events.collect { emitted.send(it) }
        }

        val request = async {
            runCatching { transport.sendSystemCommand("chat-id", "/model fast") }
        }
        val payload = json.parseToJsonElement(withTimeout(2_000) { receivedFrames.receive() }).jsonObject
        val turnId = payload.getValue("turn_id").jsonPrimitive.content
        serverSocket.get()!!.send(
            """{"event":"error","chat_id":"chat-id","detail":"invalid_model","reason":"missing","turn_id":"$turnId"}""",
        )

        val error = withTimeout(2_000) { request.await() }.exceptionOrNull()
        assertEquals("invalid_model: missing", error?.message)
        assertNull(withTimeoutOrNull(150) { emitted.receive() })
        collector.cancel()
    }

    @Test
    fun `network unavailable rejects before queuing a system command`() = runBlocking {
        transport.setNetworkAvailable(false)

        val error = runCatching {
            transport.sendSystemCommand("chat-id", "/model fast")
        }.exceptionOrNull()

        assertEquals("network_unavailable", error?.message)
        assertNull(withTimeoutOrNull(150) { receivedFrames.receive() })
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