package com.nanobotkt.feature.auth

import com.nanobotkt.core.network.GatewayException
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

sealed interface AuthState {
    val sessionEpoch: Long

    data class Booting(override val sessionEpoch: Long = 0L) : AuthState

    /**
     * 没有可用活动会话时统一展示完整 Gateway 配置页。
     *
     * [serverUrl] 只用于地址预填；Secret 永远不进入状态流、SavedStateHandle 或 Compose
     * saveable 状态。认证拒绝和手工重新配置都回到这一状态，不存在“密码过期”分支。
     */
    data class Configuration(
        val serverUrl: String,
        val error: GatewayConfigurationError? = null,
        val submitting: Boolean = false,
        override val sessionEpoch: Long = 0L,
    ) : AuthState

    data class Ready(override val sessionEpoch: Long) : AuthState

    /** 当前完整配置仍被保留，但临时网络或服务错误阻止了连接。 */
    data class Unreachable(
        val error: GatewayConfigurationError,
        val serverUrl: String,
        override val sessionEpoch: Long,
    ) : AuthState
}

/**
 * 用户可见 Gateway 会话状态的唯一仓库。
 *
 * 短期 REST/WS Token 由 [GatewayCredentialManager] 内部管理；Repository 只在完整配置首次激活、
 * 成功替换或彻底失效时改变会话边界。普通 Token 轮换不会增加 sessionEpoch。
 */
@Singleton
class AuthSessionRepository @Inject constructor(
    private val credentials: GatewayCredentialManager,
) {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val mutableState = MutableStateFlow<AuthState>(AuthState.Booting())

    /**
     * 把“开始新操作”和“检查迟到结果”放在同一把普通锁中。
     *
     * CredentialManager 负责保护配置、Secret 和 Token；这里保护的是用户可见状态。若 logout、
     * Retry、首次连接或 Settings 重配互相交错，旧操作即使稍后返回成功，也不能覆盖新操作已经
     * 发布的 Configuration/Ready。锁内不执行任何 suspend 或 I/O，只做代次和 StateFlow 写入。
     */
    private val operationLock = Any()
    private var operationGeneration = 0L
    private var started = false
    private var sessionEpoch = 0L

    val state: StateFlow<AuthState> = mutableState.asStateFlow()
    val baseUrl: String get() = credentials.baseUrl

    fun start() {
        val startupGeneration = synchronized(operationLock) {
            if (started) return
            started = true
            operationGeneration
        }

        scope.launch {
            credentials.events.collect { event ->
                when (event) {
                    is CredentialEvent.AuthenticationRejected -> {
                        synchronized(operationLock) {
                            // 活动配置被明确拒绝属于新的会话边界。先废止所有正在等待的操作，
                            // 再发布完整配置页，避免 Retry 的 catch 或迟到重配结果把 Ready 复活。
                            operationGeneration += 1L
                            mutableState.value = AuthState.Configuration(
                                serverUrl = event.serverUrl,
                                error = GatewayConfigurationError.AuthenticationRejected,
                                sessionEpoch = sessionEpoch,
                            )
                        }
                    }
                }
            }
        }
        scope.launch {
            try {
                if (credentials.restore()) {
                    establishSessionIfCurrent(startupGeneration)
                } else {
                    publishIfCurrent(startupGeneration) { configurationState() }
                }
            } catch (_: GatewayException.AuthenticationRequired) {
                publishIfCurrent(startupGeneration) {
                    configurationState(error = GatewayConfigurationError.AuthenticationRejected)
                }
            } catch (error: CancellationException) {
                throw error
            } catch (error: Exception) {
                publishIfCurrent(startupGeneration) {
                    AuthState.Unreachable(
                        error = error.toGatewayConfigurationError(),
                        serverUrl = credentials.baseUrl,
                        sessionEpoch = sessionEpoch,
                    )
                }
            }
        }
    }

    /** 初次配置提交完整地址和 Secret；失败时保留可编辑表单而不是进入只允许 Retry 的页面。 */
    suspend fun connect(config: GatewayConnectionConfig) {
        val generation = beginOperation {
            AuthState.Configuration(
                serverUrl = config.serverUrl,
                submitting = true,
                sessionEpoch = sessionEpoch,
            )
        }
        when (val result = credentials.configure(config)) {
            is GatewayConfigurationResult.Success -> establishSessionIfCurrent(generation)
            is GatewayConfigurationResult.Failure -> publishIfCurrent(generation) {
                AuthState.Configuration(
                    serverUrl = config.serverUrl,
                    error = result.error,
                    sessionEpoch = sessionEpoch,
                )
            }
        }
    }

    /**
     * 在 Ready 会话中验证并替换完整配置。
     *
     * [beforeActivation] 由 app 组合根同步清理旧 Gateway 的 feature/transport 状态。失败候选
     * 不会调用该回调；成功时 Repository 只在回调完成后发布新的 Ready epoch。
     */
    suspend fun reconfigure(
        config: GatewayConnectionConfig,
        beforeActivation: () -> Unit,
    ): GatewayConfigurationResult {
        val generation = beginOperation()
        val result = credentials.configure(config, beforeActivation)
        if (result is GatewayConfigurationResult.Success) {
            establishSessionIfCurrent(generation)
        }
        return result
    }

    /** 冷启动临时失败后的重试；当前持久化配置保持不变。 */
    suspend fun retry() {
        val generation = beginOperation { AuthState.Booting(sessionEpoch) }
        try {
            if (credentials.retryCurrent()) {
                establishSessionIfCurrent(generation)
            } else {
                publishIfCurrent(generation) { configurationState() }
            }
        } catch (_: GatewayException.AuthenticationRequired) {
            publishIfCurrent(generation) {
                configurationState(error = GatewayConfigurationError.AuthenticationRejected)
            }
        } catch (error: CancellationException) {
            throw error
        } catch (error: Exception) {
            publishIfCurrent(generation) {
                AuthState.Unreachable(
                    error = error.toGatewayConfigurationError(),
                    serverUrl = credentials.baseUrl,
                    sessionEpoch = sessionEpoch,
                )
            }
        }
    }

    /** 从临时错误页进入完整重新配置，不清除仍可重试的当前配置。 */
    fun editConfiguration() {
        beginOperation { configurationState() }
    }

    suspend fun logout() {
        // 必须在等待 CredentialManager 的互斥锁之前废止旧操作；否则一个延迟候选可能先返回
        // Success 并在 logout 完成后重新发布 Ready。
        val generation = beginOperation()
        credentials.logout()
        publishIfCurrent(generation) { configurationState() }
    }

    /** 开始新的用户可见操作，并可原子发布它的初始状态。 */
    private fun beginOperation(initialState: (() -> AuthState)? = null): Long = synchronized(operationLock) {
        operationGeneration += 1L
        initialState?.let { mutableState.value = it() }
        operationGeneration
    }

    /** 只允许仍是最新的操作发布结果，阻断 logout/重配之后到达的迟到状态。 */
    private inline fun publishIfCurrent(
        expectedGeneration: Long,
        stateFactory: () -> AuthState,
    ): Boolean = synchronized(operationLock) {
        if (operationGeneration != expectedGeneration) return@synchronized false
        mutableState.value = stateFactory()
        true
    }

    private fun configurationState(
        error: GatewayConfigurationError? = null,
    ) = AuthState.Configuration(
        serverUrl = credentials.baseUrl,
        error = error,
        sessionEpoch = sessionEpoch,
    )

    /** sessionEpoch 仅在完整配置真正建立新会话时递增，Token 轮换不会触碰它。 */
    private fun establishSessionIfCurrent(expectedGeneration: Long): Boolean = synchronized(operationLock) {
        if (operationGeneration != expectedGeneration) return@synchronized false
        sessionEpoch += 1L
        mutableState.value = AuthState.Ready(sessionEpoch)
        true
    }
}

internal fun Throwable.toGatewayConfigurationError(): GatewayConfigurationError = when (this) {
    is GatewayException.AuthenticationRequired -> GatewayConfigurationError.AuthenticationRejected
    is GatewayException.HtmlResponse -> GatewayConfigurationError.HtmlResponse
    is GatewayException.NonJsonResponse -> GatewayConfigurationError.NonJsonResponse
    is GatewayException.InvalidPayload -> GatewayConfigurationError.InvalidResponse
    is GatewayException.Timeout -> GatewayConfigurationError.Timeout
    is GatewayException.Network -> GatewayConfigurationError.NetworkUnavailable
    is GatewayException.Http -> GatewayConfigurationError.Http(status, message)
    else -> GatewayConfigurationError.Unknown(message)
}
