package com.nanobotkt.feature.automations

import com.nanobotkt.core.model.AutomationUpdatePayload
import com.nanobotkt.core.model.AutomationsPayload
import com.nanobotkt.core.network.GatewayApiClient
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import java.net.URLEncoder
import java.util.concurrent.atomic.AtomicLong
import javax.inject.Inject
import javax.inject.Singleton

interface AutomationsRepository {
    val state: StateFlow<AutomationsUiState>

    /** 清理当前会话并使在途刷新、运行和编辑请求失效。 */
    fun reset()

    suspend fun refresh()
    suspend fun action(action: String, id: String)
    suspend fun update(id: String, values: AutomationUpdatePayload)
}

data class AutomationsUiState(
    val payload: AutomationsPayload? = null,
    val loading: Boolean = false,
    val pending: Set<String> = emptySet(),
    val error: String? = null,
)

@Singleton
class DefaultAutomationsRepository @Inject constructor(
    private val api: GatewayApiClient,
) : AutomationsRepository {
    private val mutable = MutableStateFlow(AutomationsUiState())
    override val state: StateFlow<AutomationsUiState> = mutable.asStateFlow()

    /** 刷新和 mutation 共用锁，避免旧响应覆盖较新的服务端列表。 */
    private val requestMutex = Mutex()

    /** 记录 admission 时的会话代次，避免 reset 后旧请求的 finally 删除新会话的 pending。 */
    private val inFlight = mutableMapOf<String, Long>()
    private val sessionGeneration = AtomicLong(0L)

    override fun reset() {
        // 先提升代次，再清状态；旧请求即使无法立即取消，也不能再写回旧账号数据。
        sessionGeneration.incrementAndGet()
        synchronized(inFlight) { inFlight.clear() }
        mutable.value = AutomationsUiState()
    }

    override suspend fun refresh() {
        val expectedSession = sessionGeneration.get()
        requestMutex.withLock { refreshLocked(expectedSession) }
    }

    override suspend fun action(action: String, id: String) = mutate(id) {
        api.get(
            "/api/webui/automations/$action",
            mapOf("id" to id),
        )
    }

    override suspend fun update(id: String, values: AutomationUpdatePayload) = mutate(id) {
        // 服务端使用 JavaScript `decodeURIComponent` 等价的 `unquote` 解码，
        // 而 URLEncoder 默认把空格编码为 `+`；这里改成 `%20`，避免名称、消息
        // 中的普通空格在服务端被错误还原成加号。
        val encodedValues = URLEncoder.encode(
            api.encode(values, AutomationUpdatePayload.serializer()),
            "UTF-8",
        ).replace("+", "%20")
        api.request(
            "/api/webui/automations/update",
            AutomationsPayload.serializer(),
            query = mapOf("id" to id),
            headers = mapOf("X-Nanobot-Automation-Values" to encodedValues),
        )
    }

    private suspend fun mutate(
        id: String,
        block: suspend () -> AutomationsPayload,
    ) {
        val expectedSession = sessionGeneration.get()
        synchronized(inFlight) {
            if (inFlight.containsKey(id)) return
            inFlight[id] = expectedSession
        }

        try {
            requestMutex.withLock {
                if (sessionGeneration.get() != expectedSession) return@withLock
                mutable.value = mutable.value.copy(
                    pending = mutable.value.pending + id,
                    error = null,
                )
                try {
                    val payload = block()
                    if (sessionGeneration.get() != expectedSession) return@withLock
                    mutable.value = mutable.value.copy(payload = payload)
                    refreshLocked(expectedSession)
                } catch (error: CancellationException) {
                    throw error
                } catch (error: Exception) {
                    if (sessionGeneration.get() == expectedSession) {
                        mutable.value = mutable.value.copy(
                            error = error.message ?: "automation_action_failed",
                        )
                    }
                } finally {
                    if (sessionGeneration.get() == expectedSession) {
                        mutable.value = mutable.value.copy(
                            pending = mutable.value.pending - id,
                        )
                    }
                }
            }
        } finally {
            synchronized(inFlight) {
                if (inFlight[id] == expectedSession) inFlight.remove(id)
            }
        }
    }

    private suspend fun refreshLocked(expectedSession: Long) {
        if (sessionGeneration.get() != expectedSession) return
        val before = mutable.value
        mutable.value = before.copy(loading = true, error = null)
        try {
            val payload = api.get<AutomationsPayload>("/api/webui/automations")
            if (sessionGeneration.get() == expectedSession) {
                mutable.value = mutable.value.copy(
                    payload = payload,
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
                    error = error.message ?: "automations_refresh_failed",
                )
            }
        }
    }
}
