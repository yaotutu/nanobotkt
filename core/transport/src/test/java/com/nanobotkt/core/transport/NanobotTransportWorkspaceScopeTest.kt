package com.nanobotkt.core.transport

import com.nanobotkt.core.model.WorkspaceAccessMode
import com.nanobotkt.core.model.WorkspaceScope
import kotlinx.coroutines.async
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.first
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
import org.junit.Before
import org.junit.Test
import java.util.concurrent.atomic.AtomicReference

class NanobotTransportWorkspaceScopeTest {
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
    fun `new chat message and scope update preserve workspace wire format`() = runBlocking {
        connectWebSocket()
        val scope = WorkspaceScope(
            projectPath = "C:/dev/nanobot",
            projectName = "nanobot",
            accessMode = WorkspaceAccessMode.RESTRICTED,
            restrictToWorkspace = true,
        )

        val newChat = async { transport.newChat(scope) }
        assertWorkspaceScope(receiveFrame("new_chat").getValue("workspace_scope").jsonObject, scope)
        // 握手 ready 携带的是默认会话，不能提前完成 new_chat 请求。
        serverSocket.get()!!.send("""{"event":"ready","chat_id":"default-chat","client_id":"server"}""")
        assertNull(withTimeoutOrNull(100) { newChat.await() })

        serverSocket.get()!!.send("""{"event":"attached","chat_id":"chat-1"}""")
        assertEquals("chat-1", withTimeout(2_000) { newChat.await() })

        val message = transport.sendMessage(
            chatId = "chat-1",
            content = "hello",
            workspaceScope = scope,
        )
        val messageFrame = receiveFrame("message")
        assertWorkspaceScope(messageFrame.getValue("workspace_scope").jsonObject, scope)
        val turnId = messageFrame.getValue("turn_id").jsonPrimitive.content
        serverSocket.get()!!.send("""{"event":"message_accepted","chat_id":"chat-1","turn_id":"$turnId"}""")
        withTimeout(2_000) { message.accepted.await() }

        transport.setWorkspaceScope("chat-1", scope.copy(accessMode = WorkspaceAccessMode.FULL, restrictToWorkspace = false))
        val updateFrame = receiveFrame("set_workspace_scope")
        assertEquals("chat-1", updateFrame.getValue("chat_id").jsonPrimitive.content)
        assertWorkspaceScope(
            updateFrame.getValue("workspace_scope").jsonObject,
            scope.copy(accessMode = WorkspaceAccessMode.FULL, restrictToWorkspace = false),
        )
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
        withTimeout(2_000) { transport.state.first { it.status == TransportStatus.OPEN } }
    }

    private suspend fun receiveFrame(expectedType: String) =
        json.parseToJsonElement(withTimeout(2_000) { receivedFrames.receive() }).jsonObject.also {
            assertEquals(expectedType, it.getValue("type").jsonPrimitive.content)
        }

    private fun assertWorkspaceScope(
        payload: kotlinx.serialization.json.JsonObject,
        expected: WorkspaceScope,
    ) {
        assertEquals(expected.projectPath, payload.getValue("project_path").jsonPrimitive.content)
        assertEquals(expected.projectName, payload.getValue("project_name").jsonPrimitive.content)
        assertEquals(expected.accessMode.name.lowercase(), payload.getValue("access_mode").jsonPrimitive.content)
        assertEquals(expected.restrictToWorkspace, payload.getValue("restrict_to_workspace").jsonPrimitive.boolean)
    }

    private data class TestCredentials(val url: String) : WebSocketCredentialProvider {
        override suspend fun freshWebSocketUrl(): String = url
        override fun maxFrameBytes(): Int? = null
    }
}
