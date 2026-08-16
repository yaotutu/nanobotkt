package com.nanobotkt.feature.auth

import com.nanobotkt.core.model.BootstrapResponse
import com.nanobotkt.core.network.GatewayException
import com.nanobotkt.core.persistence.UserPreferences
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test

class GatewayCredentialManagerTest {
    @Test
    fun restoreWithoutSecretStillRestoresNormalizedServerUrl() = runTest {
        val manager = managerFor(
            gateway = FakeBootstrapGateway(),
            secretStore = FakeAuthSecretStore(),
            preferences = FakeAuthPreferencesStore("http://saved-server/"),
        )

        assertFalse(manager.restore())
        assertEquals("http://saved-server", manager.baseUrl)
    }

    @Test
    fun apiTokenUsesFastPathThenRefreshesAtTtlBoundary() = runTest {
        val clock = FakeClock()
        val gateway = FakeBootstrapGateway().apply {
            enqueue(testBootstrap(apiToken = "api-token-1", expiresIn = 60))
            enqueue(testBootstrap(apiToken = "api-token-2", expiresIn = 60))
        }
        val manager = managerFor(gateway, FakeAuthSecretStore(), clock = clock)
        manager.authenticate("secret")

        clock.nowMillis = 29_999L
        assertEquals("api-token-1", manager.tokenForRequest())
        assertEquals(1, gateway.fetchCount)

        clock.nowMillis = 30_000L
        assertEquals("api-token-2", manager.tokenForRequest())
        assertEquals(2, gateway.fetchCount)
    }

    @Test
    fun earlyRefreshFailureFallsBackToStillValidTokenButHardExpiryDoesNot() = runTest {
        val clock = FakeClock()
        val firstFailure = GatewayException.Network(IllegalStateException("temporarily offline"))
        val hardExpiryFailure = GatewayException.Network(IllegalStateException("still offline"))
        val gateway = FakeBootstrapGateway().apply {
            enqueue(testBootstrap(apiToken = "api-token-1", expiresIn = 60))
            enqueue(firstFailure)
            enqueue(hardExpiryFailure)
        }
        val manager = managerFor(gateway, FakeAuthSecretStore(), clock = clock)
        manager.authenticate("secret")

        clock.nowMillis = 30_000L
        // 提前刷新失败时旧 Token 还有 30 秒有效期；继续发送旧值比错误地登出更符合服务端契约。
        assertEquals("api-token-1", manager.tokenForRequest())

        clock.nowMillis = 60_000L
        val thrown = runCatching { manager.tokenForRequest() }.exceptionOrNull()
        assertSame(hardExpiryFailure, thrown)
        assertEquals(3, gateway.fetchCount)
    }

    @Test
    fun unauthorizedReusesConcurrentRotationAndRefreshesOnlyRejectedCurrentToken() = runTest {
        val clock = FakeClock()
        val gateway = FakeBootstrapGateway().apply {
            enqueue(testBootstrap(apiToken = "api-token-1"))
            enqueue(testBootstrap(apiToken = "api-token-2"))
            enqueue(testBootstrap(apiToken = "api-token-3"))
        }
        val manager = managerFor(gateway, FakeAuthSecretStore(), clock = clock)
        manager.authenticate("secret")

        clock.nowMillis = 30_000L
        assertEquals("api-token-2", manager.tokenForRequest())
        // 请求拿到 token-1 后，另一个请求已经轮换到 token-2；此时不应再次 Bootstrap。
        assertEquals("api-token-2", manager.tokenAfterUnauthorized("api-token-1"))
        assertEquals(2, gateway.fetchCount)

        assertEquals("api-token-3", manager.tokenAfterUnauthorized("api-token-2"))
        assertEquals(3, gateway.fetchCount)
    }

    @Test
    fun concurrentRequestsAtRefreshBoundaryShareOneBootstrap() = runTest {
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
        val manager = managerFor(gateway, FakeAuthSecretStore(), clock = clock)
        manager.authenticate("secret")
        clock.nowMillis = 30_000L

        val requests = List(8) { async { manager.tokenForRequest() } }
        refreshStarted.await()
        releaseRefresh.complete(Unit)

        assertEquals(List(8) { "api-token-2" }, requests.awaitAll())
        assertEquals(2, gateway.fetchCount)
    }

    @Test
    fun websocketUrlIsClaimedOnceAndSecondConnectionGetsFreshToken() = runTest {
        val gateway = FakeBootstrapGateway().apply {
            enqueue(testBootstrap(socketToken = "socket-token-1"))
            enqueue(testBootstrap(socketToken = "socket-token-2"))
        }
        val manager = managerFor(gateway, FakeAuthSecretStore())
        manager.authenticate("secret")

        assertEquals("http://test-server/ws?token=socket-token-1", manager.freshWebSocketUrl())
        assertEquals("http://test-server/ws?token=socket-token-2", manager.freshWebSocketUrl())
        assertEquals(2, gateway.fetchCount)
    }

    @Test
    fun logoutInvalidatesInFlightBootstrapBeforeLateResponseCanPublishCredentials() = runTest {
        val requestStarted = CompletableDeferred<Unit>()
        val releaseRequest = CompletableDeferred<Unit>()
        val gateway = FakeBootstrapGateway().apply {
            enqueue {
                requestStarted.complete(Unit)
                releaseRequest.await()
                testBootstrap(apiToken = "late-token")
            }
        }
        val secretStore = FakeAuthSecretStore()
        val manager = managerFor(gateway, secretStore)

        val authentication = async { runCatching { manager.authenticate("old-secret") }.exceptionOrNull() }
        requestStarted.await()
        // UNDISTPATCHED 保证 generation 在释放网络响应前立即失效；logout 随后等待刷新锁，
        // 精确覆盖“请求已发出、用户此时退出”的竞态，而不是普通的串行调用。
        val logout = launch(start = CoroutineStart.UNDISPATCHED) { manager.logout() }
        assertFalse(logout.isCompleted)

        releaseRequest.complete(Unit)
        val error = authentication.await()
        logout.join()

        assertTrue(error is GatewayException.AuthenticationRequired)
        assertNull(secretStore.savedSecret)
        assertEquals(1, secretStore.clearCount)
        assertTrue(runCatching { manager.tokenForRequest() }.exceptionOrNull() is GatewayException.AuthenticationRequired)
    }

    @Test
    fun bootstrapRejectionClearsCredentialsAndPublishesAuthenticationEvent() = runTest {
        val gateway = FakeBootstrapGateway().apply {
            enqueue(testBootstrap())
            enqueue(GatewayException.AuthenticationRequired("secret rejected"))
        }
        val secretStore = FakeAuthSecretStore()
        val manager = managerFor(gateway, secretStore)
        manager.authenticate("secret")
        val event = async(start = CoroutineStart.UNDISPATCHED) { manager.events.first() }

        val thrown = runCatching { manager.tokenAfterUnauthorized("api-token") }.exceptionOrNull()

        assertTrue(thrown is GatewayException.AuthenticationRequired)
        assertEquals(CredentialEvent.AuthenticationRejected, event.await())
        assertNull(secretStore.savedSecret)
        assertEquals(1, secretStore.clearCount)
        assertTrue(runCatching { manager.tokenForRequest() }.exceptionOrNull() is GatewayException.AuthenticationRequired)
    }

    @Test
    fun secretPersistenceFailureDoesNotPublishFetchedToken() = runTest {
        val saveFailure = IllegalStateException("keystore unavailable")
        val gateway = FakeBootstrapGateway().apply { enqueue(testBootstrap(apiToken = "uncommitted-token")) }
        val secretStore = FakeAuthSecretStore(saveFailure = saveFailure)
        val manager = managerFor(gateway, secretStore)

        val thrown = runCatching { manager.authenticate("secret") }.exceptionOrNull()

        assertSame(saveFailure, thrown)
        // Bootstrap 已成功不代表登录事务已完成；Secret 无法持久化时不能留下只在本进程有效的 Token。
        assertTrue(runCatching { manager.tokenForRequest() }.exceptionOrNull() is GatewayException.AuthenticationRequired)
        assertNull(secretStore.savedSecret)
    }

    private fun managerFor(
        gateway: FakeBootstrapGateway,
        secretStore: FakeAuthSecretStore,
        preferences: FakeAuthPreferencesStore = FakeAuthPreferencesStore(),
        clock: FakeClock = FakeClock(),
    ): GatewayCredentialManager = GatewayCredentialManager(
        bootstrapService = gateway,
        secretStore = secretStore,
        preferences = preferences,
        defaultServerUrl = "http://test-server",
        clock = clock,
    )

    private class FakeBootstrapGateway : AuthBootstrapGateway {
        private val handlers = mutableListOf<suspend (String, String) -> BootstrapResponse>()
        var fetchCount: Int = 0
            private set

        fun enqueue(response: BootstrapResponse) {
            enqueue { _, _ -> response }
        }

        fun enqueue(error: Exception) {
            enqueue { _, _ -> throw error }
        }

        fun enqueue(handler: suspend (String, String) -> BootstrapResponse) {
            handlers += handler
        }

        fun enqueue(handler: suspend () -> BootstrapResponse) {
            enqueue { _, _ -> handler() }
        }

        override suspend fun fetch(baseUrl: String, secret: String): BootstrapResponse {
            fetchCount += 1
            check(handlers.isNotEmpty()) { "没有为第 $fetchCount 次 Bootstrap 配置处理器" }
            return handlers.removeAt(0)(baseUrl, secret)
        }

        override fun deriveWebSocketUrl(baseUrl: String, payload: BootstrapResponse): String =
            "$baseUrl/ws?token=${payload.token}"
    }

    private class FakeAuthSecretStore(
        var savedSecret: String? = null,
        private val saveFailure: Exception? = null,
    ) : AuthSecretStore {
        var clearCount: Int = 0
            private set

        override suspend fun save(secret: String) {
            saveFailure?.let { throw it }
            savedSecret = secret
        }

        override suspend fun load(): String? = savedSecret

        override suspend fun clear() {
            clearCount += 1
            savedSecret = null
        }
    }

    private class FakeAuthPreferencesStore(
        initialServerUrl: String? = null,
    ) : AuthPreferencesStore {
        private val current = MutableStateFlow(UserPreferences(serverUrl = initialServerUrl))
        override val preferences: Flow<UserPreferences> = current

        override suspend fun setServerUrl(value: String?) {
            current.value = current.value.copy(serverUrl = value)
        }
    }

    private class FakeClock(
        var nowMillis: Long = 0L,
    ) : MonotonicClock {
        override fun elapsedRealtimeMillis(): Long = nowMillis
    }

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
}
