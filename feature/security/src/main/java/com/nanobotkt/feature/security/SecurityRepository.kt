package com.nanobotkt.feature.security

import com.nanobotkt.core.model.PairingPayload
import com.nanobotkt.core.network.GatewayApiClient
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import java.util.concurrent.atomic.AtomicLong
import javax.inject.Inject
import javax.inject.Singleton

interface SecurityRepository {
    val state: StateFlow<SecurityUiState>

    /** 清理当前会话的 pairing 状态，并使在途请求失效。 */
    fun reset()

    suspend fun refresh()
    suspend fun action(action: String, code: String)
}

data class SecurityUiState(
    val payload: PairingPayload? = null,
    val loading: Boolean = false,
    val pending: Set<String> = emptySet(),
    val error: String? = null,
)

@Singleton
class DefaultSecurityRepository @Inject constructor(
    private val api: GatewayApiClient,
) : SecurityRepository {
    private val mutable = MutableStateFlow(SecurityUiState())
    override val state: StateFlow<SecurityUiState> = mutable.asStateFlow()

    /** 轮询和 approve/deny 串行化，避免旧轮询响应覆盖 action 的结果。 */
    private val requestMutex = Mutex()
    private val inFlight = mutableMapOf<String, Long>()
    private val sessionGeneration = AtomicLong(0L)

    override fun reset() {
        sessionGeneration.incrementAndGet()
        synchronized(inFlight) { inFlight.clear() }
        mutable.value = SecurityUiState()
    }

    override suspend fun refresh() {
        val expectedSession = sessionGeneration.get()
        requestMutex.withLock { refreshLocked(expectedSession) }
    }

    override suspend fun action(action: String, code: String) {
        val expectedSession = sessionGeneration.get()
        synchronized(inFlight) {
            if (inFlight.containsKey(code)) return
            inFlight[code] = expectedSession
        }
        try {
            requestMutex.withLock {
                if (sessionGeneration.get() != expectedSession) return@withLock
                mutable.value = mutable.value.copy(
                    pending = mutable.value.pending + code,
                    error = null,
                )
                try {
                    val payload = api.get<PairingPayload>(
                        "/api/settings/pairing/$action",
                        mapOf("code" to code),
                    )
                    if (sessionGeneration.get() != expectedSession) return@withLock
                    mutable.value = mutable.value.copy(payload = payload)
                    refreshLocked(expectedSession)
                } catch (error: CancellationException) {
                    throw error
                } catch (error: Exception) {
                    if (sessionGeneration.get() == expectedSession) {
                        mutable.value = mutable.value.copy(
                            error = error.message ?: "pairing_action_failed",
                        )
                    }
                } finally {
                    if (sessionGeneration.get() == expectedSession) {
                        mutable.value = mutable.value.copy(
                            pending = mutable.value.pending - code,
                        )
                    }
                }
            }
        } finally {
            synchronized(inFlight) {
                if (inFlight[code] == expectedSession) inFlight.remove(code)
            }
        }
    }

    private suspend fun refreshLocked(expectedSession: Long) {
        if (sessionGeneration.get() != expectedSession) return
        val before = mutable.value
        mutable.value = before.copy(loading = true, error = null)
        try {
            val payload = api.get<PairingPayload>("/api/settings/pairing")
            if (sessionGeneration.get() == expectedSession) {
                // 普通刷新响应通常没有 last_action；保留最近一次 action 的反馈，
                // 避免 approve/deny 成功提示在紧接着的轮询中被清空。
                val preservedPayload = payload.copy(
                    lastAction = payload.lastAction ?: mutable.value.payload?.lastAction,
                )
                mutable.value = mutable.value.copy(
                    payload = preservedPayload,
                    loading = false,
                    error = null,
                )
            }
        } catch (error: CancellationException) {
            if (sessionGeneration.get() == expectedSession) mutable.value = before
            throw error
        } catch (error: Exception) {
            if (sessionGeneration.get() == expectedSession) {
                mutable.value = mutable.value.copy(
                    loading = false,
                    error = error.message ?: "pairing_refresh_failed",
                )
            }
        }
    }
}
