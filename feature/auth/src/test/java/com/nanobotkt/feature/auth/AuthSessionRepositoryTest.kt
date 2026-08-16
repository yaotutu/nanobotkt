package com.nanobotkt.feature.auth

import com.nanobotkt.core.model.BootstrapResponse
import com.nanobotkt.core.network.GatewayException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.async
import kotlinx.coroutines.delay
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.withTimeout
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class AuthSessionRepositoryTest {
    @Test
    fun noStoredV2ConfigShowsEditableConfigurationWithDefaultAddress() = runTest {
        val repository = AuthSessionRepository(managerFor())

        repository.start()
        val state = repository.awaitState { it is AuthState.Configuration }

        assertEquals(AuthState.Configuration(DEFAULT_URL), state)
        assertEquals(DEFAULT_URL, repository.baseUrl)
    }

    @Test
    fun validStoredConfigRestoresOneReadySession() = runTest {
        val gateway = FakeBootstrapGateway().apply { enqueue(testBootstrap(apiToken = "restored-token")) }
        val store = FakeGatewayConfigStore(config(OLD_URL, "stored-secret"))
        val repository = AuthSessionRepository(managerFor(gateway, store))

        repository.start()
        val state = repository.awaitState { it is AuthState.Ready }

        assertEquals(AuthState.Ready(sessionEpoch = 1L), state)
        assertEquals(listOf(BootstrapRequest(OLD_URL, "stored-secret")), gateway.requests)
    }

    @Test
    fun startupNetworkFailureIsRetryableAndPreservesCompleteConfig() = runTest {
        val gateway = FakeBootstrapGateway().apply {
            enqueue(GatewayException.Network(IllegalStateException("offline")))
        }
        val stored = config(OLD_URL, "stored-secret")
        val store = FakeGatewayConfigStore(stored)
        val repository = AuthSessionRepository(managerFor(gateway, store))

        repository.start()
        val state = repository.awaitState { it is AuthState.Unreachable }

        assertEquals(
            AuthState.Unreachable(
                error = GatewayConfigurationError.NetworkUnavailable,
                serverUrl = OLD_URL,
                sessionEpoch = 0L,
            ),
            state,
        )
        assertEquals(stored, store.config)
        assertEquals(OLD_URL, repository.baseUrl)
    }

    @Test
    fun rejectedStoredPairReturnsToCompleteConfigurationAndClearsPair() = runTest {
        val gateway = FakeBootstrapGateway().apply {
            enqueue(GatewayException.AuthenticationRequired("stored pair rejected"))
        }
        val store = FakeGatewayConfigStore(config(OLD_URL, "rejected-secret"))
        val repository = AuthSessionRepository(managerFor(gateway, store))

        repository.start()
        val state = repository.awaitState {
            it is AuthState.Configuration && it.error == GatewayConfigurationError.AuthenticationRejected
        }

        assertEquals(
            AuthState.Configuration(
                serverUrl = OLD_URL,
                error = GatewayConfigurationError.AuthenticationRejected,
            ),
            state,
        )
        assertNull(store.config)
        assertEquals(1, store.clearCount)
    }

    @Test
    fun initialCandidateFailureStaysOnEditableCompleteConfiguration() = runTest {
        val gateway = FakeBootstrapGateway().apply {
            enqueue(GatewayException.Http(503, "unavailable"))
        }
        val repository = AuthSessionRepository(managerFor(gateway))

        repository.connect(config(NEW_URL, "new-secret"))

        assertEquals(
            AuthState.Configuration(
                serverUrl = NEW_URL,
                error = GatewayConfigurationError.Http(503, "unavailable"),
            ),
            repository.state.value,
        )
    }

    @Test
    fun failedCandidateWhileReadyKeepsOldReadySessionAndSkipsCleanup() = runTest {
        val gateway = FakeBootstrapGateway().apply {
            enqueue(testBootstrap(apiToken = "old-token"))
            enqueue(GatewayException.AuthenticationRequired("candidate rejected"))
        }
        val store = FakeGatewayConfigStore(config(OLD_URL, "old-secret"))
        val repository = AuthSessionRepository(managerFor(gateway, store))
        repository.start()
        repository.awaitState { it == AuthState.Ready(1L) }
        var cleanupCalled = false

        val result = repository.reconfigure(config(NEW_URL, "new-secret")) { cleanupCalled = true }

        assertEquals(
            GatewayConfigurationResult.Failure(GatewayConfigurationError.AuthenticationRejected),
            result,
        )
        assertFalse(cleanupCalled)
        assertEquals(AuthState.Ready(1L), repository.state.value)
        assertEquals(config(OLD_URL, "old-secret"), store.config)
    }

    @Test
    fun successfulReconfigurationIncrementsEpochExactlyOnce() = runTest {
        val gateway = FakeBootstrapGateway().apply {
            enqueue(testBootstrap(apiToken = "old-token"))
            enqueue(testBootstrap(apiToken = "new-token"))
        }
        val repository = AuthSessionRepository(
            managerFor(gateway, FakeGatewayConfigStore(config(OLD_URL, "old-secret"))),
        )
        repository.start()
        repository.awaitState { it == AuthState.Ready(1L) }
        var cleanupCount = 0

        val result = repository.reconfigure(config(NEW_URL, "new-secret")) { cleanupCount += 1 }

        assertEquals(GatewayConfigurationResult.Success(NEW_URL), result)
        assertEquals(1, cleanupCount)
        assertEquals(AuthState.Ready(2L), repository.state.value)
        assertEquals(NEW_URL, repository.baseUrl)
    }

    @Test
    fun tokenRefreshDoesNotCreateNewSessionEpoch() = runTest {
        val clock = FakeClock()
        val gateway = FakeBootstrapGateway().apply {
            enqueue(testBootstrap(apiToken = "token-1", expiresIn = 60))
            enqueue(testBootstrap(apiToken = "token-2", expiresIn = 60))
        }
        val manager = managerFor(gateway, FakeGatewayConfigStore(), clock)
        val repository = AuthSessionRepository(manager)
        repository.connect(config())
        assertEquals(AuthState.Ready(1L), repository.state.value)

        clock.nowMillis = 30_000L
        assertEquals("token-2", manager.tokenForRequest())

        assertEquals(AuthState.Ready(1L), repository.state.value)
        assertEquals(2, gateway.fetchCount)
    }

    @Test
    fun logoutClearsCompleteConfigWithoutRewindingEpoch() = runTest {
        val gateway = FakeBootstrapGateway().apply { enqueue(testBootstrap()) }
        val store = FakeGatewayConfigStore()
        val repository = AuthSessionRepository(managerFor(gateway, store))
        repository.connect(config(OLD_URL, "secret"))
        assertEquals(AuthState.Ready(1L), repository.state.value)

        repository.logout()

        assertEquals(AuthState.Configuration(DEFAULT_URL, sessionEpoch = 1L), repository.state.value)
        assertNull(store.config)
        assertEquals(DEFAULT_URL, repository.baseUrl)
    }

    @Test
    fun logoutRaceCannotPublishLateReconfigurationReady() = runTest {
        val candidateStarted = CompletableDeferred<Unit>()
        val releaseCandidate = CompletableDeferred<Unit>()
        val gateway = FakeBootstrapGateway().apply {
            enqueue(testBootstrap(apiToken = "old-token"))
            enqueue {
                candidateStarted.complete(Unit)
                releaseCandidate.await()
                testBootstrap(apiToken = "late-token")
            }
        }
        val store = FakeGatewayConfigStore(config(OLD_URL, "old-secret"))
        val repository = AuthSessionRepository(managerFor(gateway, store))
        repository.start()
        repository.awaitState { it == AuthState.Ready(1L) }

        val reconfiguration = async {
            repository.reconfigure(config(NEW_URL, "new-secret")) {}
        }
        candidateStarted.await()
        val logout = async { repository.logout() }
        releaseCandidate.complete(Unit)

        assertEquals(
            GatewayConfigurationResult.Failure(GatewayConfigurationError.Cancelled),
            reconfiguration.await(),
        )
        logout.await()
        assertEquals(AuthState.Configuration(DEFAULT_URL, sessionEpoch = 1L), repository.state.value)
        assertNull(store.config)
    }

    private suspend fun AuthSessionRepository.awaitState(
        predicate: (AuthState) -> Boolean,
    ): AuthState = withTimeout(5_000L) {
        while (!predicate(state.value)) delay(10L)
        state.value
    }

    private fun managerFor(
        gateway: FakeBootstrapGateway = FakeBootstrapGateway(),
        store: FakeGatewayConfigStore = FakeGatewayConfigStore(),
        clock: FakeClock = FakeClock(),
    ) = GatewayCredentialManager(
        bootstrapService = gateway,
        configStore = store,
        defaultServerUrl = DEFAULT_URL,
        clock = clock,
    )

    private data class BootstrapRequest(val baseUrl: String, val secret: String)

    private class FakeBootstrapGateway : AuthBootstrapGateway {
        private val handlers = ArrayDeque<suspend (String, String) -> BootstrapResponse>()
        val requests = mutableListOf<BootstrapRequest>()
        val fetchCount: Int get() = requests.size

        fun enqueue(response: BootstrapResponse) {
            enqueue { _, _ -> response }
        }

        fun enqueue(error: Exception) {
            enqueue { _, _ -> throw error }
        }

        fun enqueue(handler: suspend () -> BootstrapResponse) {
            enqueue { _, _ -> handler() }
        }

        private fun enqueue(handler: suspend (String, String) -> BootstrapResponse) {
            handlers.addLast(handler)
        }

        override suspend fun fetch(baseUrl: String, secret: String): BootstrapResponse {
            requests += BootstrapRequest(baseUrl, secret)
            check(handlers.isNotEmpty()) { "没有为第 $fetchCount 次 Bootstrap 配置处理器" }
            return handlers.removeFirst()(baseUrl, secret)
        }

        override fun deriveWebSocketUrl(baseUrl: String, payload: BootstrapResponse): String =
            "$baseUrl/ws?token=${payload.token}"
    }

    private class FakeGatewayConfigStore(
        var config: GatewayConnectionConfig? = null,
    ) : AuthGatewayConfigStore {
        var clearCount = 0
            private set

        override suspend fun save(config: GatewayConnectionConfig) {
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
