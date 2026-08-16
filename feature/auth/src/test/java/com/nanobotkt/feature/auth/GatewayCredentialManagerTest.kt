package com.nanobotkt.feature.auth

import com.nanobotkt.core.model.BootstrapResponse
import com.nanobotkt.core.network.GatewayException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test

class GatewayCredentialManagerTest {
    @Test
    fun restoreWithoutCompleteV2ConfigUsesDefaultAddress() = runTest {
        val manager = managerFor(store = FakeGatewayConfigStore())

        assertFalse(manager.restore())
        assertEquals(DEFAULT_URL, manager.baseUrl)
        assertTrue(runCatching { manager.tokenForRequest() }.exceptionOrNull() is GatewayException.AuthenticationRequired)
    }

    @Test
    fun candidateNetworkFailurePreservesActiveConfigTokenAndPersistedPair() = runTest {
        val gateway = FakeBootstrapGateway().apply {
            enqueue(testBootstrap(apiToken = "old-token"))
            enqueue(GatewayException.Network(IllegalStateException("offline")))
        }
        val store = FakeGatewayConfigStore()
        val manager = managerFor(gateway, store)
        assertTrue(manager.configure(config(OLD_URL, "old-secret")) is GatewayConfigurationResult.Success)

        val result = manager.configure(config(NEW_URL, "new-secret"))

        assertEquals(
            GatewayConfigurationResult.Failure(GatewayConfigurationError.NetworkUnavailable),
            result,
        )
        assertEquals(OLD_URL, manager.baseUrl)
        assertEquals("old-token", manager.tokenForRequest())
        assertEquals(config(OLD_URL, "old-secret"), store.config)
    }

    @Test
    fun candidateAuthenticationRejectionDoesNotRejectExistingSession() = runTest {
        val gateway = FakeBootstrapGateway().apply {
            enqueue(testBootstrap(apiToken = "old-token"))
            enqueue(GatewayException.AuthenticationRequired("candidate rejected"))
        }
        val store = FakeGatewayConfigStore()
        val manager = managerFor(gateway, store)
        manager.configure(config(OLD_URL, "old-secret"))

        val result = manager.configure(config(NEW_URL, "new-secret"))

        assertEquals(
            GatewayConfigurationResult.Failure(GatewayConfigurationError.AuthenticationRejected),
            result,
        )
        assertEquals(OLD_URL, manager.baseUrl)
        assertEquals("old-token", manager.tokenForRequest())
        assertEquals(config(OLD_URL, "old-secret"), store.config)
        assertEquals(0, store.clearCount)
    }

    @Test
    fun candidateValidationUsesOnlyCandidateAddressAndSecret() = runTest {
        val gateway = FakeBootstrapGateway().apply {
            enqueue(testBootstrap(apiToken = "old-token"))
            enqueue(testBootstrap(apiToken = "new-token"))
        }
        val manager = managerFor(gateway)
        manager.configure(config(OLD_URL, "old-secret"))

        manager.configure(config("$NEW_URL/", "new-secret"))

        assertEquals(
            listOf(
                BootstrapRequest(OLD_URL, "old-secret"),
                BootstrapRequest(NEW_URL, "new-secret"),
            ),
            gateway.requests,
        )
        // 请求记录直接锁定安全边界：旧 Secret 从未与候选 Host 组合发送。
        assertFalse(gateway.requests.any { it.baseUrl == NEW_URL && it.secret == "old-secret" })
    }

    @Test
    fun storageFailureSkipsCleanupAndLeavesOldSessionUsable() = runTest {
        val gateway = FakeBootstrapGateway().apply {
            enqueue(testBootstrap(apiToken = "old-token"))
            enqueue(testBootstrap(apiToken = "candidate-token"))
        }
        val store = FakeGatewayConfigStore()
        val manager = managerFor(gateway, store)
        manager.configure(config(OLD_URL, "old-secret"))
        store.saveFailure = IllegalStateException("keystore unavailable")
        var cleanupCalled = false

        val result = manager.configure(config(NEW_URL, "new-secret")) { cleanupCalled = true }

        assertEquals(
            GatewayConfigurationResult.Failure(GatewayConfigurationError.StorageFailure),
            result,
        )
        assertFalse(cleanupCalled)
        assertEquals(OLD_URL, manager.baseUrl)
        assertEquals("old-token", manager.tokenForRequest())
        assertEquals(config(OLD_URL, "old-secret"), store.config)
    }

    @Test
    fun successOrderIsFetchThenPersistThenCleanupThenActivation() = runTest {
        val events = mutableListOf<String>()
        val gateway = FakeBootstrapGateway(events).apply {
            enqueue(testBootstrap(apiToken = "candidate-token"))
        }
        val store = FakeGatewayConfigStore(events = events)
        val manager = managerFor(gateway, store)

        val result = manager.configure(config(NEW_URL, "new-secret")) {
            events += "cleanup"
            // 清理窗口中活动入口仍是旧值，防止业务请求提前观察到候选 Gateway。
            assertEquals(DEFAULT_URL, manager.baseUrl)
        }
        events += "observed:${manager.baseUrl}"

        assertTrue(result is GatewayConfigurationResult.Success)
        assertEquals(
            listOf("fetch:$NEW_URL", "save:$NEW_URL", "cleanup", "observed:$NEW_URL"),
            events,
        )
        assertEquals("candidate-token", manager.tokenForRequest())
    }

    @Test
    fun cleanupExceptionCannotSplitPersistedAndActiveConfig() = runTest {
        val gateway = FakeBootstrapGateway().apply { enqueue(testBootstrap(apiToken = "candidate-token")) }
        val store = FakeGatewayConfigStore()
        val manager = managerFor(gateway, store)

        val result = manager.configure(config(NEW_URL, "new-secret")) {
            throw IllegalStateException("derived state cleanup failed")
        }

        // 持久化已经提交后，安全选择是让内存也收敛到候选配置；不能留到下次冷启动才突然切换。
        assertEquals(GatewayConfigurationResult.Success(NEW_URL), result)
        assertEquals(config(NEW_URL, "new-secret"), store.config)
        assertEquals(NEW_URL, manager.baseUrl)
        assertEquals("candidate-token", manager.tokenForRequest())
    }

    @Test
    fun sameAddressDifferentSecretIsFullReconfigurationAndFutureRefreshUsesNewPair() = runTest {
        val gateway = FakeBootstrapGateway().apply {
            enqueue(testBootstrap(apiToken = "token-1", expiresIn = 60))
            enqueue(testBootstrap(apiToken = "token-2", expiresIn = 60))
            enqueue(testBootstrap(apiToken = "token-3", expiresIn = 60))
        }
        val clock = FakeClock()
        val store = FakeGatewayConfigStore()
        val manager = managerFor(gateway, store, clock)
        manager.configure(config(OLD_URL, "secret-1"))

        val result = manager.configure(config(OLD_URL, "secret-2"))
        clock.nowMillis = 30_000L
        assertEquals("token-3", manager.tokenForRequest())

        assertEquals(GatewayConfigurationResult.Success(OLD_URL), result)
        assertEquals(config(OLD_URL, "secret-2"), store.config)
        assertEquals(
            listOf("secret-1", "secret-2", "secret-2"),
            gateway.requests.map(BootstrapRequest::secret),
        )
    }

    @Test
    fun logoutInvalidatesDelayedCandidateAsCancelledInsteadOfRejected() = runTest {
        val requestStarted = CompletableDeferred<Unit>()
        val releaseRequest = CompletableDeferred<Unit>()
        val gateway = FakeBootstrapGateway().apply {
            enqueue {
                requestStarted.complete(Unit)
                releaseRequest.await()
                testBootstrap(apiToken = "late-token")
            }
        }
        val store = FakeGatewayConfigStore()
        val manager = managerFor(gateway, store)

        val configuring = async { manager.configure(config(NEW_URL, "new-secret")) }
        requestStarted.await()
        val logout = async { manager.logout() }
        releaseRequest.complete(Unit)

        assertEquals(
            GatewayConfigurationResult.Failure(GatewayConfigurationError.Cancelled),
            configuring.await(),
        )
        logout.await()
        assertNull(store.config)
        assertEquals(DEFAULT_URL, manager.baseUrl)
        assertTrue(runCatching { manager.tokenForRequest() }.exceptionOrNull() is GatewayException.AuthenticationRequired)
    }

    @Test
    fun activeAuthenticationRejectionClearsWholePairAndPublishesEndpoint() = runTest {
        val gateway = FakeBootstrapGateway().apply {
            enqueue(testBootstrap(apiToken = "active-token"))
            enqueue(GatewayException.AuthenticationRequired("active pair rejected"))
        }
        val store = FakeGatewayConfigStore()
        val manager = managerFor(gateway, store)
        manager.configure(config(OLD_URL, "active-secret"))
        val event = async(start = CoroutineStart.UNDISPATCHED) { manager.events.first() }

        val thrown = runCatching { manager.tokenAfterUnauthorized("active-token") }.exceptionOrNull()

        assertTrue(thrown is GatewayException.AuthenticationRequired)
        assertEquals(CredentialEvent.AuthenticationRejected(OLD_URL), event.await())
        assertNull(store.config)
        assertEquals(1, store.clearCount)
        assertTrue(runCatching { manager.tokenForRequest() }.exceptionOrNull() is GatewayException.AuthenticationRequired)
    }

    @Test
    fun apiTokenUsesFastPathThenRefreshesAtTtlBoundary() = runTest {
        val clock = FakeClock()
        val gateway = FakeBootstrapGateway().apply {
            enqueue(testBootstrap(apiToken = "api-token-1", expiresIn = 60))
            enqueue(testBootstrap(apiToken = "api-token-2", expiresIn = 60))
        }
        val manager = managerFor(gateway, clock = clock)
        manager.configure(config())

        clock.nowMillis = 29_999L
        assertEquals("api-token-1", manager.tokenForRequest())
        assertEquals(1, gateway.fetchCount)

        clock.nowMillis = 30_000L
        assertEquals("api-token-2", manager.tokenForRequest())
        assertEquals(2, gateway.fetchCount)
    }

    @Test
    fun earlyRefreshFailureFallsBackToValidTokenButHardExpiryDoesNot() = runTest {
        val clock = FakeClock()
        val earlyFailure = GatewayException.Network(IllegalStateException("temporarily offline"))
        val expiryFailure = GatewayException.Network(IllegalStateException("still offline"))
        val gateway = FakeBootstrapGateway().apply {
            enqueue(testBootstrap(apiToken = "api-token-1", expiresIn = 60))
            enqueue(earlyFailure)
            enqueue(expiryFailure)
        }
        val manager = managerFor(gateway, clock = clock)
        manager.configure(config())

        clock.nowMillis = 30_000L
        assertEquals("api-token-1", manager.tokenForRequest())

        clock.nowMillis = 60_000L
        assertSame(expiryFailure, runCatching { manager.tokenForRequest() }.exceptionOrNull())
        assertEquals(3, gateway.fetchCount)
    }

    @Test
    fun concurrentRefreshRequestsShareOneBootstrap() = runTest {
        val clock = FakeClock()
        val refreshStarted = CompletableDeferred<Unit>()
        val releaseRefresh = CompletableDeferred<Unit>()
        val gateway = FakeBootstrapGateway().apply {
            enqueue(testBootstrap(apiToken = "api-token-1"))
            enqueue {
                refreshStarted.complete(Unit)
                releaseRefresh.await()
                testBootstrap(apiToken = "api-token-2")
            }
        }
        val manager = managerFor(gateway, clock = clock)
        manager.configure(config())
        clock.nowMillis = 30_000L

        val requests = List(8) { async { manager.tokenForRequest() } }
        refreshStarted.await()
        releaseRefresh.complete(Unit)

        assertEquals(List(8) { "api-token-2" }, requests.awaitAll())
        assertEquals(2, gateway.fetchCount)
    }

    @Test
    fun websocketUrlIsClaimedOnceAndRefreshUsesActiveFullConfig() = runTest {
        val gateway = FakeBootstrapGateway().apply {
            enqueue(testBootstrap(socketToken = "socket-1"))
            enqueue(testBootstrap(socketToken = "socket-2"))
        }
        val manager = managerFor(gateway)
        manager.configure(config(OLD_URL, "active-secret"))

        assertEquals("$OLD_URL/ws?token=socket-1", manager.freshWebSocketUrl())
        assertEquals("$OLD_URL/ws?token=socket-2", manager.freshWebSocketUrl())
        assertEquals(
            listOf("active-secret", "active-secret"),
            gateway.requests.map(BootstrapRequest::secret),
        )
    }

    private fun managerFor(
        gateway: FakeBootstrapGateway = FakeBootstrapGateway(),
        store: FakeGatewayConfigStore = FakeGatewayConfigStore(),
        clock: FakeClock = FakeClock(),
    ): GatewayCredentialManager = GatewayCredentialManager(
        bootstrapService = gateway,
        configStore = store,
        defaultServerUrl = DEFAULT_URL,
        clock = clock,
    )

    private data class BootstrapRequest(val baseUrl: String, val secret: String)

    private class FakeBootstrapGateway(
        private val events: MutableList<String>? = null,
    ) : AuthBootstrapGateway {
        private val handlers = ArrayDeque<suspend (String, String) -> BootstrapResponse>()
        val requests = mutableListOf<BootstrapRequest>()
        val fetchCount: Int get() = requests.size

        fun enqueue(response: BootstrapResponse) {
            enqueue { _, _ -> response }
        }

        fun enqueue(error: Exception) {
            enqueue { _, _ -> throw error }
        }

        fun enqueue(handler: suspend (String, String) -> BootstrapResponse) {
            handlers.addLast(handler)
        }

        fun enqueue(handler: suspend () -> BootstrapResponse) {
            enqueue { _, _ -> handler() }
        }

        override suspend fun fetch(baseUrl: String, secret: String): BootstrapResponse {
            requests += BootstrapRequest(baseUrl, secret)
            events?.add("fetch:$baseUrl")
            check(handlers.isNotEmpty()) { "没有为第 $fetchCount 次 Bootstrap 配置处理器" }
            return handlers.removeFirst()(baseUrl, secret)
        }

        override fun deriveWebSocketUrl(baseUrl: String, payload: BootstrapResponse): String =
            "$baseUrl/ws?token=${payload.token}"
    }

    private class FakeGatewayConfigStore(
        var config: GatewayConnectionConfig? = null,
        var saveFailure: Exception? = null,
        private val events: MutableList<String>? = null,
    ) : AuthGatewayConfigStore {
        var clearCount: Int = 0
            private set

        override suspend fun save(config: GatewayConnectionConfig) {
            saveFailure?.let { throw it }
            events?.add("save:${config.serverUrl}")
            this.config = config
        }

        override suspend fun load(): GatewayConnectionConfig? = config

        override suspend fun clear() {
            clearCount += 1
            config = null
        }
    }

    private class FakeClock(var nowMillis: Long = 0L) : MonotonicClock {
        override fun elapsedRealtimeMillis(): Long = nowMillis
    }

    private fun config(
        serverUrl: String = DEFAULT_URL,
        secret: String = "secret",
    ) = GatewayConnectionConfig(serverUrl, secret)

    private fun testBootstrap(
        apiToken: String = "api-token",
        socketToken: String = "socket-token",
        expiresIn: Long = 60L,
    ) = BootstrapResponse(
        token = socketToken,
        apiToken = apiToken,
        wsPath = "/ws",
        expiresIn = expiresIn,
    )

    private companion object {
        const val DEFAULT_URL = "http://test-server"
        const val OLD_URL = "http://old-server"
        const val NEW_URL = "https://new-server.example/gateway"
    }
}
