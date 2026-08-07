package com.nanobotkt.core.transport

import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.async
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import kotlinx.coroutines.withTimeoutOrNull
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.int
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
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertThrows
import org.junit.Before
import org.junit.Test
import java.util.concurrent.atomic.AtomicReference

class NanobotTransportForkTest {
    private lateinit var server: MockWebServer
    private lateinit var client: OkHttpClient
    private lateinit var transport: NanobotTransport
    private lateinit var credentials: TestCredentials
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
        credentials = TestCredentials(server.url("/ws").toString())
        transport = NanobotTransport(client, json, credentials)
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
    fun `fork frame preserves wire fields and trims optional title`() = runBlocking {
        connectWebSocket()

        val request = async {
            transport.forkChat(
                sourceChatId = "source-chat",
                beforeUserIndex = 7,
                title = "  Fork title  ",
            )
        }
        val payload = json.parseToJsonElement(withTimeout(2_000) { receivedFrames.receive() }).jsonObject

        assertEquals("fork_chat", payload.getValue("type").jsonPrimitive.content)
        assertEquals("source-chat", payload.getValue("source_chat_id").jsonPrimitive.content)
        assertEquals(7, payload.getValue("before_user_index").jsonPrimitive.int)
        assertEquals("Fork title", payload.getValue("title").jsonPrimitive.content)

        serverSocket.get()!!.send(
            """{"event":"ready","chat_id":"forked-chat","client_id":"server"}""",
        )
        assertEquals("forked-chat", withTimeout(2_000) { request.await() })
    }

    @Test
    fun `attached event also completes fork request`() = runBlocking {
        connectWebSocket()

        val request = async { transport.forkChat("source-chat", beforeUserIndex = 0) }
        val payload = json.parseToJsonElement(withTimeout(2_000) { receivedFrames.receive() }).jsonObject
        assertFalse(payload.containsKey("title"))

        serverSocket.get()!!.send("""{"event":"attached","chat_id":"forked-chat"}""")
        assertEquals("forked-chat", withTimeout(2_000) { request.await() })
    }

    @Test
    fun `invalid fork position is rejected before a request is queued`() {
        val negative = assertThrows(IllegalArgumentException::class.java) {
            runBlocking { transport.forkChat("source-chat", beforeUserIndex = -1) }
        }
        assertEquals("invalid_fork_position", negative.message)

        assertThrows(IllegalArgumentException::class.java) {
            runBlocking { transport.forkChat("   ", beforeUserIndex = 0) }
        }
    }

    @Test
    fun `fork timeout removes an unsent queued frame`() = runBlocking {
        val error = runCatching {
            transport.forkChat("source-chat", beforeUserIndex = 1, timeoutMs = 75)
        }.exceptionOrNull()
        assertEquals("fork timeout", error?.message)

        connectWebSocket()
        assertNull(withTimeoutOrNull(250) { receivedFrames.receive() })
    }

    @Test
    fun `new chat and fork share one pending request slot`() = runBlocking {
        val fork = async(start = CoroutineStart.UNDISPATCHED) {
            transport.forkChat("source-chat", beforeUserIndex = 1, timeoutMs = 5_000)
        }

        val error = runCatching { transport.newChat(timeoutMs = 50) }.exceptionOrNull()
        assertEquals("new_chat_pending", error?.message)
        fork.cancelAndJoin()
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
