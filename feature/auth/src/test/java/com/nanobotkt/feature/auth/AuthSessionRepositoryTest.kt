package com.nanobotkt.feature.auth

import com.nanobotkt.core.model.BootstrapResponse
import com.nanobotkt.core.network.GatewayException
import com.nanobotkt.core.persistence.UserPreferences
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test

class AuthSessionRepositoryTest {
    @Test
    fun `candidate network failure keeps active endpoint state preference and secret unchanged`() = runTest {
        val gateway = RecordingBootstrapGateway { baseUrl, _ ->
            if (baseUrl == OLD_URL) testBootstrap("old")
            else throw GatewayException.Network(IllegalStateException("offline"))
        }
        val secrets = FakeAuthSecretStore()
        val preferences = FakeAuthPreferencesStore()
        val repository = repositoryFor(gateway, secrets, preferences)
        repository.authenticate(OLD_URL, OLD_SECRET)
        val readyBeforeSwitch = repository.state.value

        val result = repository.switchServer(NEW_URL, NEW_SECRET) {
            error("验证失败时绝不能执行清理")
        }

        assertEquals(
            ServerSwitchResult.Failure(ServerConnectionError.NetworkUnavailable),
            result,
        )
        assertEquals(OLD_URL, repository.baseUrl)
        assertEquals(OLD_URL, preferences.current.value.serverUrl)
        assertSame(readyBeforeSwitch, repository.state.value)
        assertEquals(mapOf(OLD_URL to OLD_SECRET), secrets.values)
        assertEquals(Request(NEW_URL, NEW_SECRET), gateway.requests.last())
    }

    @Test
    fun `candidate authentication failure never persists candidate endpoint`() = runTest {
        val gateway = RecordingBootstrapGateway { baseUrl, _ ->
            if (baseUrl == OLD_URL) testBootstrap("old")
            else throw GatewayException.AuthenticationRequired()
        }
        val secrets = FakeAuthSecretStore()
        val preferences = FakeAuthPreferencesStore()
        val repository = repositoryFor(gateway, secrets, preferences)
        repository.authenticate(OLD_URL, OLD_SECRET)
        val readyBeforeSwitch = repository.state.value

        val result = repository.switchServer(NEW_URL, NEW_SECRET, beforeActivate = {})

        assertEquals(
            ServerSwitchResult.Failure(ServerConnectionError.AuthenticationRequired),
            result,
        )
        assertEquals(OLD_URL, repository.baseUrl)
        assertEquals(OLD_URL, preferences.current.value.serverUrl)
        assertSame(readyBeforeSwitch, repository.state.value)
        assertEquals(mapOf(OLD_URL to OLD_SECRET), secrets.values)
    }

    @Test
    fun `successful switch validates with new secret then clears old endpoint state`() = runTest {
        val gateway = RecordingBootstrapGateway { baseUrl, _ ->
            if (baseUrl == OLD_URL) testBootstrap("old") else testBootstrap("new")
        }
        val secrets = FakeAuthSecretStore()
        val preferences = FakeAuthPreferencesStore()
        val repository = repositoryFor(gateway, secrets, preferences)
        repository.authenticate(OLD_URL, OLD_SECRET)
        val events = mutableListOf<String>()

        val result = repository.switchServer(NEW_URL, NEW_SECRET) {
            events += "cleanup:${repository.baseUrl}"
        }

        assertEquals(ServerSwitchResult.Success(NEW_URL), result)
        // 清理回调执行时仍暴露旧端点，确保旧 Repository/Socket 先失效，再发布新 Ready。
        assertEquals(listOf("cleanup:$OLD_URL"), events)
        assertEquals(NEW_URL, repository.baseUrl)
        assertEquals(NEW_URL, preferences.current.value.serverUrl)
        assertEquals(mapOf(NEW_URL to NEW_SECRET), secrets.values)
        assertEquals(Request(NEW_URL, NEW_SECRET), gateway.requests.last())
        val ready = repository.state.value as AuthState.Ready
        assertEquals(2L, ready.sessionEpoch)
        assertEquals(2L, ready.tokenGeneration)
        assertEquals("new-api-token", repository.apiToken)
    }

    @Test
    fun `authentication preserves leading and trailing spaces in an explicit secret`() = runTest {
        val exactSecret = "  generated secret with spaces  "
        val gateway = RecordingBootstrapGateway { _, _ -> testBootstrap("exact") }
        val secrets = FakeAuthSecretStore()
        val repository = repositoryFor(gateway, secrets, FakeAuthPreferencesStore())

        val result = repository.authenticate(NEW_URL, exactSecret)

        assertEquals(ServerSwitchResult.Success(NEW_URL), result)
        // Secret 是不透明凭据：UI 可以用 isBlank 拒绝空输入，但网络与密文存储必须保留原值。
        assertEquals(Request(NEW_URL, exactSecret), gateway.requests.single())
        assertEquals(exactSecret, secrets.values[NEW_URL])
    }

    @Test
    fun `switch never sends old endpoint secret to candidate server`() = runTest {
        val gateway = RecordingBootstrapGateway { baseUrl, secret ->
            when (baseUrl) {
                OLD_URL -> testBootstrap("old")
                NEW_URL -> {
                    assertEquals(NEW_SECRET, secret)
                    testBootstrap("new")
                }
                else -> error("unexpected endpoint")
            }
        }
        val repository = repositoryFor(gateway, FakeAuthSecretStore(), FakeAuthPreferencesStore())
        repository.authenticate(OLD_URL, OLD_SECRET)

        repository.switchServer(NEW_URL, NEW_SECRET, beforeActivate = {})

        assertFalse(gateway.requests.contains(Request(NEW_URL, OLD_SECRET)))
    }

    @Test
    fun `storage failure rolls back candidate secret and leaves active session untouched`() = runTest {
        val gateway = RecordingBootstrapGateway { baseUrl, _ ->
            if (baseUrl == OLD_URL) testBootstrap("old") else testBootstrap("new")
        }
        val secrets = FakeAuthSecretStore()
        val preferences = FakeAuthPreferencesStore()
        val repository = repositoryFor(gateway, secrets, preferences)
        repository.authenticate(OLD_URL, OLD_SECRET)
        val readyBeforeSwitch = repository.state.value
        preferences.failWrites = true

        val result = repository.switchServer(NEW_URL, NEW_SECRET, beforeActivate = {})

        assertEquals(ServerSwitchResult.Failure(ServerConnectionError.StorageFailure), result)
        assertEquals(OLD_URL, repository.baseUrl)
        assertSame(readyBeforeSwitch, repository.state.value)
        assertEquals(mapOf(OLD_URL to OLD_SECRET), secrets.values)
    }

    @Test
    fun `same endpoint storage failure restores the previously active secret`() = runTest {
        val gateway = RecordingBootstrapGateway { _, _ -> testBootstrap("same") }
        val secrets = FakeAuthSecretStore()
        val preferences = FakeAuthPreferencesStore()
        val repository = repositoryFor(gateway, secrets, preferences)
        repository.authenticate(OLD_URL, OLD_SECRET)
        val readyBeforeSwitch = repository.state.value
        preferences.failWrites = true

        val result = repository.switchServer(OLD_URL, NEW_SECRET, beforeActivate = {})

        assertEquals(ServerSwitchResult.Failure(ServerConnectionError.StorageFailure), result)
        assertEquals(OLD_URL, repository.baseUrl)
        assertSame(readyBeforeSwitch, repository.state.value)
        // 候选和当前端点相同时，失败回滚必须恢复旧 Secret，不能直接 clear 同一个槽位。
        assertEquals(OLD_SECRET, secrets.values[OLD_URL])
    }

    @Test
    fun `cleanup exception rolls persistence back without publishing candidate ready state`() = runTest {
        val gateway = RecordingBootstrapGateway { baseUrl, _ ->
            if (baseUrl == OLD_URL) testBootstrap("old") else testBootstrap("candidate")
        }
        val secrets = FakeAuthSecretStore()
        val preferences = FakeAuthPreferencesStore()
        val repository = repositoryFor(gateway, secrets, preferences)
        repository.authenticate(OLD_URL, OLD_SECRET)
        val readyBeforeSwitch = repository.state.value

        val result = repository.switchServer(NEW_URL, NEW_SECRET) {
            error("local cleanup failed")
        }

        assertEquals(ServerSwitchResult.Failure(ServerConnectionError.StorageFailure), result)
        assertEquals(OLD_URL, repository.baseUrl)
        assertEquals(OLD_URL, preferences.current.value.serverUrl)
        assertEquals(mapOf(OLD_URL to OLD_SECRET), secrets.values)
        assertSame(readyBeforeSwitch, repository.state.value)
    }

    @Test
    fun `old endpoint secret cleanup failure aborts switch before local state cleanup`() = runTest {
        val gateway = RecordingBootstrapGateway { baseUrl, _ ->
            if (baseUrl == OLD_URL) testBootstrap("old") else testBootstrap("candidate")
        }
        val secrets = FakeAuthSecretStore()
        val preferences = FakeAuthPreferencesStore()
        val repository = repositoryFor(gateway, secrets, preferences)
        repository.authenticate(OLD_URL, OLD_SECRET)
        val readyBeforeSwitch = repository.state.value
        secrets.failClearFor += OLD_URL
        var cleanupCalled = false

        val result = repository.switchServer(NEW_URL, NEW_SECRET) {
            cleanupCalled = true
        }

        assertEquals(ServerSwitchResult.Failure(ServerConnectionError.StorageFailure), result)
        assertFalse(cleanupCalled)
        assertEquals(OLD_URL, repository.baseUrl)
        assertEquals(OLD_URL, preferences.current.value.serverUrl)
        assertEquals(mapOf(OLD_URL to OLD_SECRET), secrets.values)
        assertSame(readyBeforeSwitch, repository.state.value)
    }

    @Test
    fun `logout invalidates in flight authentication before candidate can be activated`() = runTest {
        val requestStarted = CompletableDeferred<Unit>()
        val releaseRequest = CompletableDeferred<Unit>()
        val gateway = RecordingBootstrapGateway { _, _ ->
            requestStarted.complete(Unit)
            releaseRequest.await()
            testBootstrap("candidate")
        }
        val secrets = FakeAuthSecretStore()
        val repository = repositoryFor(gateway, secrets, FakeAuthPreferencesStore())

        var authenticationResult: ServerSwitchResult? = null
        val authentication = launch {
            authenticationResult = repository.authenticate(NEW_URL, NEW_SECRET)
        }
        requestStarted.await()
        val logout = launch(start = CoroutineStart.UNDISPATCHED) { repository.logout() }
        logout.join()
        releaseRequest.complete(Unit)
        authentication.join()

        assertEquals(
            ServerSwitchResult.Failure(ServerConnectionError.Cancelled),
            authenticationResult,
        )
        assertTrue(repository.state.value is AuthState.Authentication)
        assertNull(repository.currentBootstrap())
        assertNull(repository.apiToken)
        assertTrue(secrets.values.isEmpty())
    }

    @Test
    fun `refreshForSocket propagates cancellation`() = runTest {
        val expected = CancellationException("cancel refresh")
        val secrets = FakeAuthSecretStore().also { it.values[DEFAULT_URL] = "test-secret" }
        val repository = repositoryFor(
            RecordingBootstrapGateway { _, _ -> throw expected },
            secrets,
            FakeAuthPreferencesStore(),
        )

        val actual = try {
            repository.refreshForSocket()
            error("CancellationException should be propagated")
        } catch (error: CancellationException) {
            error
        }

        assertSame(expected, actual)
    }

    @Test
    fun `refreshForSocket returns null for authentication and network failures`() = runTest {
        val errors = listOf(
            GatewayException.AuthenticationRequired(),
            GatewayException.Http(429, "rate limited"),
            GatewayException.Http(503, "unavailable"),
            GatewayException.Network(IllegalStateException("offline")),
        )

        errors.forEach { error ->
            val secrets = FakeAuthSecretStore().also { it.values[DEFAULT_URL] = "test-secret" }
            val repository = repositoryFor(
                RecordingBootstrapGateway { _, _ -> throw error },
                secrets,
                FakeAuthPreferencesStore(),
            )

            assertNull(repository.refreshForSocket())
        }
    }

    @Test
    fun `authentication normalizes and persists user selected endpoint only after validation`() = runTest {
        val gateway = RecordingBootstrapGateway { _, _ -> testBootstrap("login") }
        val secrets = FakeAuthSecretStore()
        val preferences = FakeAuthPreferencesStore()
        val repository = repositoryFor(gateway, secrets, preferences)

        val result = repository.authenticate("  $NEW_URL/  ", NEW_SECRET)

        assertEquals(ServerSwitchResult.Success(NEW_URL), result)
        assertEquals(NEW_URL, repository.baseUrl)
        assertEquals(NEW_URL, preferences.current.value.serverUrl)
        assertEquals(NEW_SECRET, secrets.values[NEW_URL])
        assertTrue(repository.state.value is AuthState.Ready)
    }

    private fun repositoryFor(
        bootstrapGateway: AuthBootstrapGateway,
        secretStore: FakeAuthSecretStore,
        preferences: FakeAuthPreferencesStore,
    ): AuthSessionRepository = AuthSessionRepository(
        bootstrapService = bootstrapGateway,
        secretStore = secretStore,
        preferences = preferences,
        defaultServerUrl = DEFAULT_URL,
    )

    private data class Request(val baseUrl: String, val secret: String)

    private class RecordingBootstrapGateway(
        private val handler: suspend (String, String) -> BootstrapResponse,
    ) : AuthBootstrapGateway {
        val requests = mutableListOf<Request>()

        override suspend fun fetch(baseUrl: String, secret: String): BootstrapResponse {
            requests += Request(baseUrl, secret)
            return handler(baseUrl, secret)
        }

        override fun deriveWebSocketUrl(baseUrl: String, payload: BootstrapResponse): String =
            "$baseUrl/ws?token=${payload.token}"
    }

    private class FakeAuthSecretStore : AuthSecretStore {
        val values = linkedMapOf<String, String>()
        val failClearFor = mutableSetOf<String>()

        override suspend fun save(serverUrl: String, secret: String) {
            values[serverUrl] = secret
        }

        override suspend fun load(serverUrl: String): String? = values[serverUrl]

        override suspend fun clear(serverUrl: String) {
            if (serverUrl in failClearFor) error("failed to clear $serverUrl")
            values.remove(serverUrl)
        }
    }

    private class FakeAuthPreferencesStore(
        initialServerUrl: String? = null,
    ) : AuthPreferencesStore {
        val current = MutableStateFlow(UserPreferences(serverUrl = initialServerUrl))
        var failWrites: Boolean = false

        override val preferences: Flow<UserPreferences> = current

        override suspend fun setServerUrl(value: String?) {
            if (failWrites) error("disk full")
            current.value = current.value.copy(serverUrl = value)
        }
    }

    private fun testBootstrap(prefix: String) = BootstrapResponse(
        token = "$prefix-socket-token",
        apiToken = "$prefix-api-token",
        wsPath = "/ws",
        expiresIn = 3600,
    )

    private companion object {
        const val DEFAULT_URL = "http://test-server"
        const val OLD_URL = "http://old-server"
        const val NEW_URL = "https://new-server.example/gateway"
        const val OLD_SECRET = "old-secret"
        const val NEW_SECRET = "new-secret"
    }
}
