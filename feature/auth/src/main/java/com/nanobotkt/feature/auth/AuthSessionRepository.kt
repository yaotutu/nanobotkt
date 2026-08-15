package com.nanobotkt.feature.auth

import com.nanobotkt.core.model.BootstrapResponse
import com.nanobotkt.core.model.BootstrapSnapshotProvider
import com.nanobotkt.core.model.IngressLimitsProvider
import com.nanobotkt.core.model.WebUiIngressLimits
import com.nanobotkt.core.network.AuthContext
import com.nanobotkt.core.network.GatewayException
import com.nanobotkt.core.network.GatewayServerAddressError
import com.nanobotkt.core.network.GatewayServerAddressResult
import com.nanobotkt.core.network.GatewayServerUrl
import com.nanobotkt.core.network.normalizeGatewayServerAddress
import com.nanobotkt.core.transport.TransportCredentials
import java.util.concurrent.atomic.AtomicLong
import javax.inject.Inject
import javax.inject.Singleton
import kotlin.math.max
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext

/** 用户填写服务器地址或执行切换时可稳定映射到 UI 的失败原因。 */
sealed interface ServerConnectionError {
    data class InvalidAddress(val reason: GatewayServerAddressError) : ServerConnectionError
    data object AuthenticationRequired : ServerConnectionError
    data object Timeout : ServerConnectionError
    data object NetworkUnavailable : ServerConnectionError
    data object HtmlResponse : ServerConnectionError
    data object NonJsonResponse : ServerConnectionError
    data class Http(val status: Int, val message: String?) : ServerConnectionError
    data object StorageFailure : ServerConnectionError
    data object Cancelled : ServerConnectionError
    data class Unknown(val message: String?) : ServerConnectionError
}

/** 候选 Gateway 只有通过 Bootstrap 验证并完成激活后才返回 Success。 */
sealed interface ServerSwitchResult {
    data class Success(val serverUrl: String) : ServerSwitchResult
    data class Failure(val error: ServerConnectionError) : ServerSwitchResult
}

sealed interface AuthState {
    val sessionEpoch: Long
    val tokenGeneration: Long

    data class Booting(
        override val sessionEpoch: Long = 0,
        override val tokenGeneration: Long = 0,
    ) : AuthState

    data class Authentication(
        val failed: Boolean = false,
        val submitting: Boolean = false,
        val connectionError: ServerConnectionError? = null,
        override val sessionEpoch: Long = 0,
        override val tokenGeneration: Long = 0,
    ) : AuthState

    data class Ready(
        val bootstrap: BootstrapResponse,
        override val sessionEpoch: Long,
        override val tokenGeneration: Long,
    ) : AuthState

    data class Unreachable(
        val message: String,
        override val sessionEpoch: Long,
        override val tokenGeneration: Long,
    ) : AuthState
}

/**
 * 为每一轮认证请求分配单调递增的代数。
 *
 * logout 与服务器激活都会使旧代数失效；旧请求返回后必须先核对代数，才能写回认证状态
 * 或重新创建 renewal job，避免旧服务器/旧账号响应在边界切换后“复活”。
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
    private var currentBaseUrl = normalizedDefaultServerUrl()

    val state: StateFlow<AuthState> = mutableState.asStateFlow()
    override val baseUrl: String get() = currentBaseUrl
    override val apiToken: String? get() = currentBootstrap?.apiToken
    override fun currentBootstrap(): BootstrapResponse? = currentBootstrap

    fun start() {
        if (started) return
        started = true
        val expectedGeneration = authGeneration.current()
        scope.launch {
            val storedUrl = preferences.preferences.first().serverUrl
            currentBaseUrl = normalizedServerUrlOrNull(storedUrl) ?: normalizedDefaultServerUrl()
            val secret = secretStore.load(currentBaseUrl)
            if (!authGeneration.isCurrent(expectedGeneration)) return@launch
            if (secret.isNullOrBlank()) {
                mutableState.value = AuthState.Authentication()
                return@launch
            }
            refresh(
                secret = secret,
                establishSession = true,
                authFailureReturnsToForm = true,
                expectedGeneration = expectedGeneration,
            )
        }
    }

    /**
     * 从登录页验证用户明确填写的地址和 Secret。
     *
     * 候选 Bootstrap 成功前不会改写当前地址或持久化 Secret；因此拼写错误、网络错误或
     * 认证失败都不会把应用锁死在错误端点。登录成功后才激活候选地址。
     */
    suspend fun authenticate(rawServerUrl: String, secret: String): ServerSwitchResult {
        val previous = mutableState.value
        val candidateUrl = when (val normalized = normalizeGatewayServerAddress(rawServerUrl)) {
            is GatewayServerAddressResult.Valid -> normalized.url
            is GatewayServerAddressResult.Invalid -> {
                val error = ServerConnectionError.InvalidAddress(normalized.error)
                mutableState.value = AuthState.Authentication(
                    connectionError = error,
                    sessionEpoch = previous.sessionEpoch,
                    tokenGeneration = previous.tokenGeneration,
                )
                return ServerSwitchResult.Failure(error)
            }
        }
        // Secret 属于不透明凭据，首尾空格也可能是服务端生成值的一部分；这里只拒绝
        // 全空白输入，绝不能为了“清理表单”而改变真正发送和持久化的字节序列。
        val candidateSecret = secret
        if (candidateSecret.isBlank()) {
            val error = ServerConnectionError.AuthenticationRequired
            mutableState.value = AuthState.Authentication(
                failed = true,
                sessionEpoch = previous.sessionEpoch,
                tokenGeneration = previous.tokenGeneration,
            )
            return ServerSwitchResult.Failure(error)
        }

        val expectedGeneration = authGeneration.current()
        mutableState.value = AuthState.Authentication(
            submitting = true,
            sessionEpoch = previous.sessionEpoch,
            tokenGeneration = previous.tokenGeneration,
        )
        val payload = try {
            bootstrapService.fetch(candidateUrl, candidateSecret)
        } catch (error: CancellationException) {
            throw error
        } catch (error: Exception) {
            val connectionError = error.toServerConnectionError()
            if (authGeneration.isCurrent(expectedGeneration)) {
                mutableState.value = if (connectionError == ServerConnectionError.AuthenticationRequired) {
                    AuthState.Authentication(
                        failed = true,
                        sessionEpoch = previous.sessionEpoch,
                        tokenGeneration = previous.tokenGeneration,
                    )
                } else {
                    AuthState.Unreachable(
                        message = error.toAuthMessage(),
                        sessionEpoch = previous.sessionEpoch,
                        tokenGeneration = previous.tokenGeneration,
                    )
                }
            }
            return ServerSwitchResult.Failure(connectionError)
        }

        val activation = activateValidatedServer(
            candidateUrl = candidateUrl,
            candidateSecret = candidateSecret,
            payload = payload,
            expectedGeneration = expectedGeneration,
            beforeActivate = {},
        )
        if (activation is ServerSwitchResult.Failure &&
            activation.error != ServerConnectionError.Cancelled &&
            authGeneration.isCurrent(expectedGeneration)
        ) {
            mutableState.value = AuthState.Authentication(
                connectionError = activation.error,
                sessionEpoch = previous.sessionEpoch,
                tokenGeneration = previous.tokenGeneration,
            )
        }
        return activation
    }

    /**
     * 已登录状态下安全切换 Gateway。
     *
     * 网络验证在当前连接仍保持的阶段完成；只有候选 Bootstrap 成功后，才进入互斥激活区，
     * 保存新端点、同步清理旧服务器状态并发布新的 Ready。失败不会修改当前地址、Secret、
     * Bootstrap 或连接状态，也绝不会把旧服务器 Secret 自动发送给候选地址。
     */
    suspend fun switchServer(
        rawServerUrl: String,
        secret: String,
        beforeActivate: () -> Unit,
    ): ServerSwitchResult {
        val candidateUrl = when (val normalized = normalizeGatewayServerAddress(rawServerUrl)) {
            is GatewayServerAddressResult.Valid -> normalized.url
            is GatewayServerAddressResult.Invalid ->
                return ServerSwitchResult.Failure(ServerConnectionError.InvalidAddress(normalized.error))
        }
        // 与登录路径保持同一规则：检查空白但原样发送，避免两个入口产生不同凭据语义。
        val candidateSecret = secret
        if (candidateSecret.isBlank()) {
            return ServerSwitchResult.Failure(ServerConnectionError.AuthenticationRequired)
        }

        val expectedGeneration = authGeneration.current()
        val payload = try {
            // 必须使用用户本次显式输入的 Secret；禁止读取当前端点 Secret 后转发到候选地址。
            bootstrapService.fetch(candidateUrl, candidateSecret)
        } catch (error: CancellationException) {
            throw error
        } catch (error: Exception) {
            return ServerSwitchResult.Failure(error.toServerConnectionError())
        }

        return activateValidatedServer(
            candidateUrl = candidateUrl,
            candidateSecret = candidateSecret,
            payload = payload,
            expectedGeneration = expectedGeneration,
            beforeActivate = beforeActivate,
        )
    }

    suspend fun retry() {
        val expectedGeneration = authGeneration.current()
        val secret = secretStore.load(currentBaseUrl)
        if (!authGeneration.isCurrent(expectedGeneration)) return
        if (secret.isNullOrBlank()) {
            mutableState.value = AuthState.Authentication()
            return
        }
        val previous = mutableState.value
        mutableState.value = AuthState.Booting(previous.sessionEpoch, previous.tokenGeneration)
        refresh(
            secret = secret,
            establishSession = currentBootstrap == null,
            authFailureReturnsToForm = true,
            expectedGeneration = expectedGeneration,
        )
    }

    /** 从不可达页面回到地址/Secret 表单；不删除当前端点的已保存 Secret。 */
    fun showAuthentication() {
        val previous = mutableState.value
        mutableState.value = AuthState.Authentication(
            sessionEpoch = previous.sessionEpoch,
            tokenGeneration = previous.tokenGeneration,
        )
    }

    suspend fun refreshForSocket(): String? {
        val expectedGeneration = authGeneration.current()
        val secret = secretStore.load(currentBaseUrl) ?: return null
        return refreshMutex.withLock {
            if (!authGeneration.isCurrent(expectedGeneration)) return@withLock null
            try {
                val payload = bootstrapService.fetch(currentBaseUrl, secret)
                if (!applyBootstrap(payload, establishSession = false, expectedGeneration = expectedGeneration)) {
                    return@withLock null
                }
                bootstrapService.deriveWebSocketUrl(currentBaseUrl, payload)
            } catch (error: CancellationException) {
                throw error
            } catch (_: Exception) {
                // Socket 刷新失败只表示本次重认证不可用，由 Transport 保持原有容错语义。
                null
            }
        }
    }

    suspend fun logout() {
        refreshMutex.withLock {
            // generation 的失效必须与服务器激活共用同一互斥区。网络验证不持有该锁，
            // 因此 logout 仍能立即使候选请求过期；而一旦激活已进入提交阶段，则先让该
            // 原子阶段完成，再由 logout 清理，避免在 DataStore 挂起点插入并复活新会话。
            authGeneration.invalidate()
            renewalJob?.cancel()
            renewalJob = null
            currentBootstrap = null
            secretStore.clear(currentBaseUrl)
            mutableState.value = AuthState.Authentication()
        }
    }

    override fun currentWebSocketUrl(): String? =
        currentBootstrap?.let { bootstrapService.deriveWebSocketUrl(currentBaseUrl, it) }

    override suspend fun reauthenticateWebSocketUrl(): String? = refreshForSocket()
    override fun maxFrameBytes(): Int? = currentBootstrap?.limits?.transport?.maxFrameBytes
    override fun currentIngressLimits(): WebUiIngressLimits? = currentBootstrap?.limits

    private suspend fun activateValidatedServer(
        candidateUrl: String,
        candidateSecret: String,
        payload: BootstrapResponse,
        expectedGeneration: Long,
        beforeActivate: () -> Unit,
    ): ServerSwitchResult = refreshMutex.withLock {
        if (!authGeneration.isCurrent(expectedGeneration)) {
            return@withLock ServerSwitchResult.Failure(ServerConnectionError.Cancelled)
        }

        val oldUrl = currentBaseUrl
        val oldSecret = try {
            // oldUrl 始终是当前活动端点，读取它只用于回滚快照，不会把旧凭据发送或迁移
            // 到候选地址。这样旧 Secret 清理失败或本地 cleanup 失败时都能恢复完整旧状态。
            secretStore.load(oldUrl)
        } catch (error: CancellationException) {
            throw error
        } catch (_: Exception) {
            return@withLock ServerSwitchResult.Failure(ServerConnectionError.StorageFailure)
        }
        try {
            // 先完成持久化，再使旧会话失效。若写盘失败，当前内存连接仍保持不变。
            secretStore.save(candidateUrl, candidateSecret)
            preferences.setServerUrl(candidateUrl)
            if (oldUrl != candidateUrl) {
                // “成功切换后不保留旧端点 Secret”是安全契约的一部分，不能吞掉清理失败
                // 后仍向 UI 报告成功。此时尚未关闭旧连接，失败可以完整回滚。
                secretStore.clear(oldUrl)
            }
            // 此回调必须只做同步、无外部 I/O 的本地清理。若某一步意外抛错，下面会恢复
            // 地址和 Secret；即使 Transport 已部分关闭，也不会发布候选 Ready 或把旧缓存
            // 带到新服务器，用户可留在当前端点重新连接或再次切换。
            beforeActivate()
        } catch (error: CancellationException) {
            rollbackCandidatePersistence(oldUrl, candidateUrl, oldSecret)
            throw error
        } catch (_: Exception) {
            rollbackCandidatePersistence(oldUrl, candidateUrl, oldSecret)
            return@withLock ServerSwitchResult.Failure(ServerConnectionError.StorageFailure)
        }

        val activatedGeneration = authGeneration.invalidate()
        renewalJob?.cancel()
        renewalJob = null

        // 清理已在 currentBaseUrl/Bootstrap 切换前完成：Repository 代次、附件登记和旧 Socket
        // 先同步失效，随后新的 Ready 才可能触发 Transport.resume()。
        currentBaseUrl = candidateUrl
        currentBootstrap = null
        applyBootstrap(payload, establishSession = true, expectedGeneration = activatedGeneration)

        ServerSwitchResult.Success(candidateUrl)
    }

    /**
     * 恢复候选激活前的持久化状态。
     *
     * 切换协程可能在 DataStore 写入后被取消，因此回滚必须运行在 NonCancellable 中。候选与
     * 当前端点相同时恢复旧 Secret；不同端点清空候选槽位，并恢复可能已清理的旧端点槽位。
     * 回滚属于 best effort：即使磁盘继续失败，内存仍保持旧端点且不会发布候选 Bootstrap。
     */
    private suspend fun rollbackCandidatePersistence(
        oldUrl: String,
        candidateUrl: String,
        oldSecret: String?,
    ) = withContext(NonCancellable) {
        runCatching {
            if (candidateUrl == oldUrl) {
                if (oldSecret != null) secretStore.save(oldUrl, oldSecret)
                else secretStore.clear(oldUrl)
            } else {
                secretStore.clear(candidateUrl)
            }
        }
        if (candidateUrl != oldUrl && oldSecret != null) {
            runCatching { secretStore.save(oldUrl, oldSecret) }
        }
        runCatching { preferences.setServerUrl(oldUrl) }
    }

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
                mutableState.value = if (authFailureReturnsToForm) {
                    AuthState.Authentication(
                        failed = true,
                        sessionEpoch = previous.sessionEpoch,
                        tokenGeneration = previous.tokenGeneration,
                    )
                } else {
                    AuthState.Unreachable(error.toAuthMessage(), previous.sessionEpoch, previous.tokenGeneration)
                }
            } catch (error: CancellationException) {
                throw error
            } catch (error: Exception) {
                if (authGeneration.isCurrent(expectedGeneration)) {
                    mutableState.value = AuthState.Unreachable(
                        error.toAuthMessage(),
                        previous.sessionEpoch,
                        previous.tokenGeneration,
                    )
                }
            }
        }
    }

    private fun applyBootstrap(
        payload: BootstrapResponse,
        establishSession: Boolean,
        expectedGeneration: Long,
    ): Boolean {
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
            val secret = secretStore.load(currentBaseUrl) ?: return@launch
            refresh(
                secret = secret,
                establishSession = false,
                authFailureReturnsToForm = false,
                expectedGeneration = expectedGeneration,
            )
        }
        return true
    }

    private fun normalizedDefaultServerUrl(): String =
        normalizedServerUrlOrNull(defaultServerUrl)
            ?: error("Configured default Gateway URL must be a valid HTTP(S) address")

    private fun normalizedServerUrlOrNull(value: String?): String? =
        when (val result = normalizeGatewayServerAddress(value.orEmpty())) {
            is GatewayServerAddressResult.Valid -> result.url
            is GatewayServerAddressResult.Invalid -> null
        }
}

private fun Throwable.toServerConnectionError(): ServerConnectionError = when (this) {
    is GatewayException.AuthenticationRequired -> ServerConnectionError.AuthenticationRequired
    is GatewayException.HtmlResponse -> ServerConnectionError.HtmlResponse
    is GatewayException.NonJsonResponse,
    is GatewayException.InvalidPayload,
    -> ServerConnectionError.NonJsonResponse
    is GatewayException.Timeout -> ServerConnectionError.Timeout
    is GatewayException.Network -> ServerConnectionError.NetworkUnavailable
    is GatewayException.Http -> ServerConnectionError.Http(status, message)
    else -> ServerConnectionError.Unknown(message)
}

private fun Throwable.toAuthMessage(): String = when (this) {
    is GatewayException.HtmlResponse -> "gateway_html_response"
    is GatewayException.NonJsonResponse,
    is GatewayException.InvalidPayload,
    -> "non_json_response"
    is GatewayException.Timeout -> "timeout"
    is GatewayException.Network -> "network_unavailable"
    // HTTP body 由用户填写的候选服务器控制，可能恶意反射认证头；不可直接进入 UI。
    is GatewayException.Http -> "HTTP $status"
    // 未分类异常也只返回稳定代码，避免解析库或网络栈 message 泄露 URL/响应片段。
    else -> "unknown_error"
}
