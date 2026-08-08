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
import org.junit.Assert.assertSame
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class AuthSessionRepositoryTest {
    @Test
    fun refreshForSocketRethrowsCancellationException() = runTest {
        val expected = CancellationException("socket refresh cancelled")
        val secretStore = FakeAuthSecretStore().also { it.savedSecret = "test-secret" }
        val repository = repositoryFor(ThrowingBootstrapGateway(expected), secretStore)

        val actual = try {
            repository.refreshForSocket()
            null
        } catch (error: CancellationException) {
            error
        }

        // CancellationException 必须保持对象和语义原样向上传递，不能被转换成 null。
        assertSame(expected, actual)
    }

    @Test
    fun refreshForSocketReturnsNullForAuthenticationOrNetworkException() = runTest {
        val errors = listOf(
            GatewayException.AuthenticationRequired(),
            GatewayException.Http(429, "rate limited"),
            GatewayException.Http(503, "unavailable"),
            GatewayException.Network(IllegalStateException("offline")),
        )

        errors.forEach { error ->
            val secretStore = FakeAuthSecretStore().also { it.savedSecret = "test-secret" }
            val repository = repositoryFor(ThrowingBootstrapGateway(error), secretStore)

            // 认证、限流、服务端故障和网络异常都按容错契约返回 null，
            // 不影响调用方处理已有连接；CancellationException 则由上一个测试单独保证透传。
            assertNull(repository.refreshForSocket())
        }
    }

    @Test
    fun logoutInvalidatesAnInFlightAuthenticateBeforeItsBootstrapCanBeApplied() = runTest {
        val requestStarted = CompletableDeferred<Unit>()
        val releaseRequest = CompletableDeferred<Unit>()
        val bootstrap = testBootstrap()
        val bootstrapGateway = BlockingBootstrapGateway(
            response = bootstrap,
            requestStarted = requestStarted,
            releaseRequest = releaseRequest,
        )
        val secretStore = FakeAuthSecretStore()
        val repository = AuthSessionRepository(
            bootstrapService = bootstrapGateway,
            secretStore = secretStore,
            preferences = FakeAuthPreferencesStore(),
            defaultServerUrl = "http://test-server",
        )

        val authenticate = launch {
            repository.authenticate("old-secret")
        }
        requestStarted.await()

        // 使用 UNDISTPATCHED 让 logout 立即执行 generation.invalidate()，随后再等待认证锁；
        // 这样测试的是“请求尚未返回时退出登录”的真实时序，而不是简单的先后调用。
        val logout = launch(start = CoroutineStart.UNDISPATCHED) {
            repository.logout()
        }
        assertTrue("logout 应在认证请求持有锁时等待", !logout.isCompleted)

        releaseRequest.complete(Unit)
        authenticate.join()
        logout.join()

        assertTrue(repository.state.value is AuthState.Authentication)
        assertNull(repository.currentBootstrap())
        assertNull(repository.apiToken)
        assertNull(secretStore.savedSecret)
        assertEquals(1, secretStore.clearCount)
        assertEquals(1, bootstrapGateway.fetchCount)
    }

    private class ThrowingBootstrapGateway(
        private val error: Exception,
    ) : AuthBootstrapGateway {
        override suspend fun fetch(baseUrl: String, secret: String): BootstrapResponse = throw error

        override fun deriveWebSocketUrl(baseUrl: String, payload: BootstrapResponse): String =
            "$baseUrl/ws?token=${payload.token}"
    }

    private fun repositoryFor(
        bootstrapGateway: AuthBootstrapGateway,
        secretStore: FakeAuthSecretStore,
    ): AuthSessionRepository = AuthSessionRepository(
        bootstrapService = bootstrapGateway,
        secretStore = secretStore,
        preferences = FakeAuthPreferencesStore(),
        defaultServerUrl = "http://test-server",
    )

    private class BlockingBootstrapGateway(
        private val response: BootstrapResponse,
        private val requestStarted: CompletableDeferred<Unit>,
        private val releaseRequest: CompletableDeferred<Unit>,
    ) : AuthBootstrapGateway {
        var fetchCount: Int = 0
            private set

        override suspend fun fetch(baseUrl: String, secret: String): BootstrapResponse {
            fetchCount += 1
            requestStarted.complete(Unit)
            releaseRequest.await()
            return response
        }

        override fun deriveWebSocketUrl(baseUrl: String, payload: BootstrapResponse): String =
            "$baseUrl/ws?token=${payload.token}"
    }

    private class FakeAuthSecretStore : AuthSecretStore {
        var savedSecret: String? = null
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

    private class FakeAuthPreferencesStore : AuthPreferencesStore {
        private val current = MutableStateFlow(UserPreferences())
        override val preferences: Flow<UserPreferences> = current

        override suspend fun setServerUrl(value: String?) {
            current.value = current.value.copy(serverUrl = value)
        }
    }

    private fun testBootstrap() = BootstrapResponse(
        token = "socket-token",
        apiToken = "api-token",
        wsPath = "/ws",
        expiresIn = 3600,
    )
}
