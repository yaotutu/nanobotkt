package com.nanobotkt.feature.auth

import com.nanobotkt.core.model.BootstrapResponse
import com.nanobotkt.core.model.BootstrapSnapshotProvider
import com.nanobotkt.core.model.IngressLimitsProvider
import com.nanobotkt.core.model.WebUiIngressLimits
import com.nanobotkt.core.network.AuthContext
import com.nanobotkt.core.network.GatewayException
import com.nanobotkt.core.network.GatewayServerUrl
import com.nanobotkt.core.transport.TransportCredentials
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import javax.inject.Inject
import javax.inject.Singleton
import kotlin.math.max
import java.util.concurrent.atomic.AtomicLong

sealed interface AuthState {
    val sessionEpoch: Long
    val tokenGeneration: Long
    data class Booting(override val sessionEpoch: Long = 0, override val tokenGeneration: Long = 0) : AuthState
    data class Authentication(val failed: Boolean = false, val submitting: Boolean = false, override val sessionEpoch: Long = 0, override val tokenGeneration: Long = 0) : AuthState
    data class Ready(val bootstrap: BootstrapResponse, override val sessionEpoch: Long, override val tokenGeneration: Long) : AuthState
    data class Unreachable(val message: String, override val sessionEpoch: Long, override val tokenGeneration: Long) : AuthState
}

/**
 * 为每一轮认证请求分配单调递增的代数。
 *
 * logout 会使当前代数失效；网络请求返回后必须先核对代数，才能把结果写回
 * 认证状态或重新创建 renewal job。这样即使底层 HTTP 请求取消不及时，旧账号的
 * bootstrap 响应也不会在退出登录后“复活”会话。
 */
internal class AuthGeneration {
    private val value = AtomicLong(0L)

    fun current(): Long = value.get()

    fun invalidate(): Long = value.incrementAndGet()

    fun isCurrent(expected: Long): Boolean = value.get() == expected
}

@Singleton
class AuthSessionRepository @Inject constructor(
    private val bootstrapService: AuthBootstrapGateway,
    private val secretStore: AuthSecretStore,
    private val preferences: AuthPreferencesStore,
    @param:GatewayServerUrl private val defaultServerUrl: String,
) : AuthContext, TransportCredentials, IngressLimitsProvider, BootstrapSnapshotProvider {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val refreshMutex = Mutex()
    private val authGeneration = AuthGeneration()
    private val mutableState = MutableStateFlow<AuthState>(AuthState.Booting())
    private var renewalJob: Job? = null
    private var started = false
    private var currentBootstrap: BootstrapResponse? = null
    private var currentBaseUrl = defaultServerUrl.trimEnd('/')

    val state: StateFlow<AuthState> = mutableState.asStateFlow()
    override val baseUrl: String get() = currentBaseUrl
    override val apiToken: String? get() = currentBootstrap?.apiToken
    override fun currentBootstrap(): BootstrapResponse? = currentBootstrap

    fun start() {
        if (started) return
        started = true
        val expectedGeneration = authGeneration.current()
        scope.launch {
            currentBaseUrl = preferences.preferences.first().serverUrl?.trimEnd('/') ?: defaultServerUrl.trimEnd('/')
            val secret = secretStore.load()
            if (!authGeneration.isCurrent(expectedGeneration)) return@launch
            if (secret.isNullOrBlank()) {
                mutableState.value = AuthState.Authentication()
                return@launch
            }
            refresh(
                secret,
                establishSession = true,
                authFailureReturnsToForm = true,
                expectedGeneration = expectedGeneration,
            )
        }
    }

    suspend fun authenticate(secret: String) {
        val value = secret.trim()
        if (value.isEmpty()) return
        val expectedGeneration = authGeneration.current()
        val previous = mutableState.value
        if (!authGeneration.isCurrent(expectedGeneration)) return
        mutableState.value = AuthState.Authentication(submitting = true, sessionEpoch = previous.sessionEpoch, tokenGeneration = previous.tokenGeneration)
        refreshMutex.withLock {
            if (!authGeneration.isCurrent(expectedGeneration)) return@withLock
            try {
                val payload = bootstrapService.fetch(currentBaseUrl, value)
                if (!authGeneration.isCurrent(expectedGeneration)) return@withLock
                // secretStore.save 与 applyBootstrap 都在同一把锁内完成；logout 也使用
                // 这把锁，因此不会出现“logout 清理完成后旧 authenticate 又保存 secret”的窗口。
                secretStore.save(value)
                applyBootstrap(payload, establishSession = currentBootstrap == null, expectedGeneration = expectedGeneration)
            } catch (error: GatewayException.AuthenticationRequired) {
                if (authGeneration.isCurrent(expectedGeneration)) {
                    mutableState.value = AuthState.Authentication(failed = true, sessionEpoch = previous.sessionEpoch, tokenGeneration = previous.tokenGeneration)
                }
            } catch (error: CancellationException) {
                throw error
            } catch (error: Exception) {
                if (authGeneration.isCurrent(expectedGeneration)) {
                    mutableState.value = AuthState.Unreachable(error.toAuthMessage(), previous.sessionEpoch, previous.tokenGeneration)
                }
            }
        }
    }

    suspend fun retry() {
        val expectedGeneration = authGeneration.current()
        val secret = secretStore.load()
        if (!authGeneration.isCurrent(expectedGeneration)) return
        if (secret.isNullOrBlank()) {
            mutableState.value = AuthState.Authentication()
            return
        }
        val previous = mutableState.value
        mutableState.value = AuthState.Booting(previous.sessionEpoch, previous.tokenGeneration)
        refresh(
            secret,
            establishSession = currentBootstrap == null,
            authFailureReturnsToForm = true,
            expectedGeneration = expectedGeneration,
        )
    }

    suspend fun refreshForSocket(): String? {
        val expectedGeneration = authGeneration.current()
        val secret = secretStore.load() ?: return null
        return refreshMutex.withLock {
            if (!authGeneration.isCurrent(expectedGeneration)) return@withLock null
            try {
                val payload = bootstrapService.fetch(currentBaseUrl, secret)
                if (!applyBootstrap(payload, establishSession = false, expectedGeneration = expectedGeneration)) return@withLock null
                bootstrapService.deriveWebSocketUrl(currentBaseUrl, payload)
            } catch (error: CancellationException) {
                // 协程取消是结构化并发的控制信号，不能被当作普通刷新失败吞掉；
                // 原样重新抛出，才能让上层正确结束或取消当前 Socket 重认证请求。
                throw error
            } catch (_: Exception) {
                // 认证失败、网络失败等普通异常只表示本次刷新不可用，保持原有
                // 容错语义，由调用方通过 null 处理并继续使用现有连接状态。
                null
            }
        }
    }

    suspend fun logout() {
        // 先递增代数，再等待正在进行的 refresh/authenticate 完成。旧请求即使
        // 返回成功，也会因为代数失效而不能写回；拿到锁后再清理 secret，确保
        // logout 返回时持久化凭据和内存 bootstrap 都已经清空。
        authGeneration.invalidate()
        refreshMutex.withLock {
            renewalJob?.cancel()
            renewalJob = null
            currentBootstrap = null
            secretStore.clear()
            mutableState.value = AuthState.Authentication()
        }
    }

    suspend fun updateServerUrl(url: String?) {
        preferences.setServerUrl(url)
        currentBaseUrl = url?.trim()?.trimEnd('/')?.takeIf(String::isNotEmpty) ?: defaultServerUrl.trimEnd('/')
        retry()
    }

    override fun currentWebSocketUrl(): String? = currentBootstrap?.let { bootstrapService.deriveWebSocketUrl(currentBaseUrl, it) }
    override suspend fun reauthenticateWebSocketUrl(): String? = refreshForSocket()
    override fun maxFrameBytes(): Int? = currentBootstrap?.limits?.transport?.maxFrameBytes
    override fun currentIngressLimits(): WebUiIngressLimits? = currentBootstrap?.limits

    private suspend fun refresh(
        secret: String,
        establishSession: Boolean,
        authFailureReturnsToForm: Boolean,
        expectedGeneration: Long,
    ) {
        refreshMutex.withLock {
            if (!authGeneration.isCurrent(expectedGeneration)) return@withLock
            val previous = mutableState.value
            try {
                val payload = bootstrapService.fetch(currentBaseUrl, secret)
                if (!applyBootstrap(payload, establishSession, expectedGeneration)) return@withLock
            } catch (error: GatewayException.AuthenticationRequired) {
                if (!authGeneration.isCurrent(expectedGeneration)) return@withLock
                if (authFailureReturnsToForm) mutableState.value = AuthState.Authentication(failed = true, sessionEpoch = previous.sessionEpoch, tokenGeneration = previous.tokenGeneration)
                else mutableState.value = AuthState.Unreachable(error.toAuthMessage(), previous.sessionEpoch, previous.tokenGeneration)
            } catch (error: CancellationException) {
                throw error
            } catch (error: Exception) {
                if (authGeneration.isCurrent(expectedGeneration)) {
                    mutableState.value = AuthState.Unreachable(error.toAuthMessage(), previous.sessionEpoch, previous.tokenGeneration)
                }
            }
        }
    }

    private fun applyBootstrap(payload: BootstrapResponse, establishSession: Boolean, expectedGeneration: Long): Boolean {
        if (!authGeneration.isCurrent(expectedGeneration)) return false
        val previous = mutableState.value
        currentBootstrap = payload
        val epoch = if (establishSession) previous.sessionEpoch + 1 else previous.sessionEpoch
        mutableState.value = AuthState.Ready(payload, epoch, previous.tokenGeneration + 1)
        renewalJob?.cancel()
        val renewAfterMillis = max(5_000L, payload.expiresIn * 800L)
        renewalJob = scope.launch {
            delay(renewAfterMillis)
            if (!authGeneration.isCurrent(expectedGeneration)) return@launch
            val secret = secretStore.load() ?: return@launch
            refresh(
                secret,
                establishSession = false,
                authFailureReturnsToForm = false,
                expectedGeneration = expectedGeneration,
            )
        }
        return true
    }
}

private fun Throwable.toAuthMessage(): String = when (this) {
    is GatewayException.HtmlResponse -> "gateway_html_response"
    is GatewayException.NonJsonResponse -> "non_json_response"
    is GatewayException.Timeout -> "timeout"
    is GatewayException.Network -> "network_unavailable"
    is GatewayException.Http -> message ?: "HTTP $status"
    else -> message ?: "unknown_error"
}


