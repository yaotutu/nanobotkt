package com.nanobotkt.feature.auth

import com.nanobotkt.core.model.BootstrapResponse
import com.nanobotkt.core.network.GatewayException
import com.nanobotkt.core.persistence.UserPreferences
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class AuthSessionRepositoryTest {
    @Test
    fun startWithoutStoredSecretShowsAuthenticationAndRestoresServerUrl() = runTest {
        val preferences = FakeAuthPreferencesStore("http://saved-server/")
        val manager = managerFor(
            gateway = FakeBootstrapGateway(),
            secretStore = FakeAuthSecretStore(),
            preferences = preferences,
        )
        val repository = AuthSessionRepository(manager)

        repository.start()
        val state = repository.awaitState { it !is AuthState.Booting }

        assertEquals(AuthState.Authentication(sessionEpoch = 0L), state)
        // 即使没有登录 Secret，服务地址仍属于可恢复配置，后续手工登录必须使用持久化入口。
        assertEquals("http://saved-server", repository.baseUrl)
    }

    @Test
    fun startWithValidStoredSecretEstablishesOneSession() = runTest {
        val gateway = FakeBootstrapGateway().apply { enqueue(testBootstrap(apiToken = "restored-token")) }
        val secretStore = FakeAuthSecretStore(savedSecret = "stored-secret")
        val repository = AuthSessionRepository(managerFor(gateway, secretStore))

        repository.start()
        val state = repository.awaitState { it is AuthState.Ready }

        assertEquals(AuthState.Ready(sessionEpoch = 1L), state)
        assertEquals(listOf("stored-secret"), gateway.requestedSecrets)
    }

    @Test
    fun startNetworkFailureShowsUnreachableInsteadOfAuthenticationFailure() = runTest {
        val gateway = FakeBootstrapGateway().apply {
            enqueue(GatewayException.Network(IllegalStateException("offline")))
        }
        val repository = AuthSessionRepository(
            managerFor(gateway, FakeAuthSecretStore(savedSecret = "stored-secret")),
        )

        repository.start()
        val state = repository.awaitState { it is AuthState.Unreachable }

        assertEquals(AuthState.Unreachable("network_unavailable", sessionEpoch = 0L), state)
    }

    @Test
    fun rejectedStoredSecretReturnsToFailedAuthenticationAndClearsSecret() = runTest {
        val gateway = FakeBootstrapGateway().apply {
            enqueue(GatewayException.AuthenticationRequired("bootstrap rejected"))
        }
        val secretStore = FakeAuthSecretStore(savedSecret = "rejected-secret")
        val repository = AuthSessionRepository(managerFor(gateway, secretStore))

        repository.start()
        val state = repository.awaitState { it is AuthState.Authentication && it.failed }

        assertEquals(AuthState.Authentication(failed = true, sessionEpoch = 0L), state)
        assertNull(secretStore.savedSecret)
        assertEquals(1, secretStore.clearCount)
    }

    @Test
    fun authenticateNetworkFailureKeepsSessionRecoverable() = runTest {
        val gateway = FakeBootstrapGateway().apply {
            enqueue(GatewayException.Http(503, "unavailable"))
        }
        val repository = AuthSessionRepository(managerFor(gateway, FakeAuthSecretStore()))

        repository.authenticate("new-secret")

        assertEquals(AuthState.Unreachable("unavailable", sessionEpoch = 0L), repository.state.value)
    }

    @Test
    fun successfulAuthenticateAdvancesEpochButTokenRotationDoesNot() = runTest {
        val clock = FakeClock()
        val gateway = FakeBootstrapGateway().apply {
            enqueue(testBootstrap(apiToken = "api-token-1", expiresIn = 60))
            enqueue(testBootstrap(apiToken = "api-token-2", expiresIn = 60))
        }
        val manager = managerFor(gateway, FakeAuthSecretStore(), clock = clock)
        val repository = AuthSessionRepository(manager)

        repository.authenticate("new-secret")
        assertEquals(AuthState.Ready(sessionEpoch = 1L), repository.state.value)

        // 60 秒 TTL 的刷新边界是第 30 秒。凭据轮换属于鉴权内部细节，不能让 App Root
        // 误判为新登录会话，也不能触发业务 Repository 重建。
        clock.nowMillis = 30_000L
        assertEquals("api-token-2", manager.tokenForRequest())
        assertEquals(AuthState.Ready(sessionEpoch = 1L), repository.state.value)
        assertEquals(2, gateway.fetchCount)
    }

    @Test
    fun logoutEndsSessionWithoutRewindingEpoch() = runTest {
        val gateway = FakeBootstrapGateway().apply { enqueue(testBootstrap()) }
        val secretStore = FakeAuthSecretStore()
        val repository = AuthSessionRepository(managerFor(gateway, secretStore))

        repository.authenticate("new-secret")
        repository.logout()

        assertEquals(AuthState.Authentication(sessionEpoch = 1L), repository.state.value)
        assertNull(secretStore.savedSecret)
        assertEquals(1, secretStore.clearCount)
    }

    private suspend fun AuthSessionRepository.awaitState(
        predicate: (AuthState) -> Boolean,
    ): AuthState = withContext(Dispatchers.Default.limitedParallelism(1)) {
        // Repository 的生产 Scope 固定使用 Dispatchers.IO，不受 runTest 虚拟时钟控制。
        // 因此这里在真实调度器上设置超时，避免测试调度器瞬间推进 5 秒并抢先判定失败。
        withTimeout(5_000L) { state.first(predicate) }
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
        private val results = mutableListOf<Result<BootstrapResponse>>()
        val requestedSecrets = mutableListOf<String>()
        var fetchCount: Int = 0
            private set

        fun enqueue(response: BootstrapResponse) {
            results += Result.success(response)
        }

        fun enqueue(error: Exception) {
            results += Result.failure(error)
        }

        override suspend fun fetch(baseUrl: String, secret: String): BootstrapResponse {
            fetchCount += 1
            requestedSecrets += secret
            check(results.isNotEmpty()) { "没有为第 $fetchCount 次 Bootstrap 配置结果" }
            return results.removeAt(0).getOrThrow()
        }

        override fun deriveWebSocketUrl(baseUrl: String, payload: BootstrapResponse): String =
            "$baseUrl/ws?token=${payload.token}"
    }

    private class FakeAuthSecretStore(
        var savedSecret: String? = null,
    ) : AuthSecretStore {
        var clearCount: Int = 0
            private set

        override suspend fun save(secret: String) {
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
