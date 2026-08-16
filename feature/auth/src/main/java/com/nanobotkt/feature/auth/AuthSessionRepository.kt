package com.nanobotkt.feature.auth

import com.nanobotkt.core.network.GatewayException
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import javax.inject.Inject
import javax.inject.Singleton

sealed interface AuthState {
    val sessionEpoch: Long

    data class Booting(override val sessionEpoch: Long = 0L) : AuthState

    data class Authentication(
        val failed: Boolean = false,
        val submitting: Boolean = false,
        override val sessionEpoch: Long = 0L,
    ) : AuthState

    data class Ready(override val sessionEpoch: Long) : AuthState

    data class Unreachable(
        val message: String,
        override val sessionEpoch: Long,
    ) : AuthState
}

/**
 * 用户可见认证会话的状态仓库。
 *
 * 短期 API/WS Token、TTL 和并发刷新全部由 [GatewayCredentialManager] 封装。Repository 只在
 * 真正建立或结束登录会话时更新 [AuthState]，因此普通 Token 轮换不会再触发 App Root、
 * Settings 或业务 Repository 的“重新认证”生命周期。
 */
@Singleton
class AuthSessionRepository @Inject constructor(
    private val credentials: GatewayCredentialManager,
) {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val mutableState = MutableStateFlow<AuthState>(AuthState.Booting())
    private var started = false
    private var sessionEpoch = 0L

    val state: StateFlow<AuthState> = mutableState.asStateFlow()
    val baseUrl: String get() = credentials.baseUrl

    fun start() {
        if (started) return
        started = true

        scope.launch {
            credentials.events.collect { event ->
                if (event is CredentialEvent.AuthenticationRejected) {
                    // 只有 Bootstrap 明确拒绝 Secret 才结束登录会话。网络、429、503 或普通
                    // Token 续期失败不会发布该事件，也就不会把用户错误地送回登录页。
                    mutableState.value = AuthState.Authentication(
                        failed = true,
                        sessionEpoch = sessionEpoch,
                    )
                }
            }
        }
        scope.launch {
            try {
                if (credentials.restore()) establishSession()
                else mutableState.value = AuthState.Authentication(sessionEpoch = sessionEpoch)
            } catch (_: GatewayException.AuthenticationRequired) {
                mutableState.value = AuthState.Authentication(failed = true, sessionEpoch = sessionEpoch)
            } catch (error: CancellationException) {
                throw error
            } catch (error: Exception) {
                mutableState.value = AuthState.Unreachable(error.toAuthMessage(), sessionEpoch)
            }
        }
    }

    suspend fun authenticate(secret: String) {
        val normalized = secret.trim()
        if (normalized.isEmpty()) return
        mutableState.value = AuthState.Authentication(
            submitting = true,
            sessionEpoch = sessionEpoch,
        )
        try {
            credentials.authenticate(normalized)
            establishSession()
        } catch (_: GatewayException.AuthenticationRequired) {
            mutableState.value = AuthState.Authentication(failed = true, sessionEpoch = sessionEpoch)
        } catch (error: CancellationException) {
            throw error
        } catch (error: Exception) {
            mutableState.value = AuthState.Unreachable(error.toAuthMessage(), sessionEpoch)
        }
    }

    suspend fun retry() {
        mutableState.value = AuthState.Booting(sessionEpoch)
        try {
            if (credentials.retry()) establishSession()
            else mutableState.value = AuthState.Authentication(sessionEpoch = sessionEpoch)
        } catch (_: GatewayException.AuthenticationRequired) {
            mutableState.value = AuthState.Authentication(failed = true, sessionEpoch = sessionEpoch)
        } catch (error: CancellationException) {
            throw error
        } catch (error: Exception) {
            mutableState.value = AuthState.Unreachable(error.toAuthMessage(), sessionEpoch)
        }
    }

    suspend fun logout() {
        credentials.logout()
        mutableState.value = AuthState.Authentication(sessionEpoch = sessionEpoch)
    }

    suspend fun updateServerUrl(url: String?) {
        credentials.updateServerUrl(url)
        retry()
    }

    private fun establishSession() {
        sessionEpoch += 1L
        mutableState.value = AuthState.Ready(sessionEpoch)
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
