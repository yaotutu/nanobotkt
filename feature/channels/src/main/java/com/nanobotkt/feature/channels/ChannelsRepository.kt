package com.nanobotkt.feature.channels

import com.nanobotkt.core.model.ChannelConfigurePayload
import com.nanobotkt.core.model.ChannelConnectPayload
import com.nanobotkt.core.model.ChannelValidationPayload
import com.nanobotkt.core.model.NanobotFeaturesPayload
import com.nanobotkt.core.network.GatewayApiClient
import java.net.URLEncoder
import java.util.concurrent.atomic.AtomicLong
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json

interface ChannelsRepository {
    val state: StateFlow<ChannelsUiState>

    /** 清理当前登录会话，并使所有在途频道请求的结果失效。 */
    fun reset()

    suspend fun refresh()

    suspend fun setEnabled(name: String, enabled: Boolean, instanceId: String? = null)

    suspend fun configure(
        name: String,
        values: Map<String, String>,
        enable: Boolean? = null,
        instanceId: String? = null,
    )

    /**
     * 先按官方 WebUI 语义执行校验，只有 canEnable=true 时才保存并启用。
     * 返回值用于让调用方知道校验失败时没有继续发送 configure 请求。
     */
    suspend fun validateAndConfigure(
        name: String,
        values: Map<String, String>,
        instanceId: String? = null,
    ): ChannelValidationPayload?

    suspend fun validate(name: String, values: Map<String, String>, instanceId: String? = null)

    suspend fun startConnect(name: String, instanceId: String? = null): ChannelConnectPayload?

    suspend fun pollConnect(
        name: String,
        sessionId: String,
        instanceId: String? = null,
    ): ChannelConnectPayload?

    suspend fun cancelConnect(
        name: String,
        sessionId: String,
        instanceId: String? = null,
    ): ChannelConnectPayload?
}

data class ChannelsUiState(
    val payload: NanobotFeaturesPayload? = null,
    val validation: ChannelValidationPayload? = null,
    /** 校验结果所属的频道实例；避免多个同名实例共享一份 validation。 */
    val validationKey: String? = null,
    /** 兼容旧调用方；新 UI 应按 channel + instance 从 connections 读取。 */
    val connection: ChannelConnectPayload? = null,
    val connections: Map<String, ChannelConnectPayload> = emptyMap(),
    val loading: Boolean = false,
    val pending: Set<String> = emptySet(),
    val error: String? = null,
) {
    fun validationFor(name: String, instanceId: String? = null): ChannelValidationPayload? =
        validation?.takeIf { validationKey == channelConnectionKey(name, instanceId) }

    fun connectionFor(name: String, instanceId: String? = null): ChannelConnectPayload? =
        connections[channelConnectionKey(name, instanceId)]
            ?: connection?.takeIf { it.instanceId == instanceId }
}

internal fun channelConnectionKey(name: String, instanceId: String?): String =
    "$name::${instanceId ?: "default"}"

/** 统一生成频道配置/启停/校验操作的去重键。 */
internal fun channelActionKey(name: String, instanceId: String?): String =
    "feature:${channelConnectionKey(name, instanceId)}"

/** 连接轮询与普通频道配置使用独立 pending 键，保证 Cancel 能正常发起。 */
internal fun channelConnectKey(name: String, instanceId: String?): String =
    "connect:${channelConnectionKey(name, instanceId)}"

internal fun channelCancelKey(name: String, instanceId: String?): String =
    "cancel:${channelConnectionKey(name, instanceId)}"

@Singleton
class DefaultChannelsRepository @Inject constructor(
    private val api: GatewayApiClient,
    private val json: Json,
) : ChannelsRepository {
    private val mutable = MutableStateFlow(ChannelsUiState())
    override val state = mutable.asStateFlow()

    /** 刷新、配置和连接轮询串行化，避免并行请求交错修改频道状态。 */
    private val requestMutex = Mutex()

    /**
     * 记录请求被哪个 session 接纳。reset 后允许新 session 使用同名 key，
     * 因此不能使用单纯 Set，否则旧请求 finally 可能误删新 session 的去重标记。
     */
    private val inFlight = mutableMapOf<String, Long>()
    private val sessionGeneration = AtomicLong(0L)

    override fun reset() {
        // 先使旧 session 失效，再清空状态和去重表；旧请求返回后的所有写入都会被
        // updateIfSession 拦截，不能把上一个账号的频道配置、连接或错误带回新会话。
        sessionGeneration.incrementAndGet()
        synchronized(inFlight) { inFlight.clear() }
        mutable.value = ChannelsUiState()
    }

    override suspend fun refresh() {
        val expectedSession = sessionGeneration.get()
        requestMutex.withLock {
            if (isCurrent(expectedSession)) refreshLocked(expectedSession)
        }
    }

    override suspend fun setEnabled(name: String, enabled: Boolean, instanceId: String?) {
        mutate(channelActionKey(name, instanceId)) { session ->
            val payload = api.get<NanobotFeaturesPayload>(
                "/api/settings/nanobot-features/${if (enabled) "enable" else "disable"}",
                mapOf("name" to name, "instance_id" to instanceId),
            )
            updateIfSession(session) { it.copy(payload = payload) }
        }
    }

    override suspend fun configure(
        name: String,
        values: Map<String, String>,
        enable: Boolean?,
        instanceId: String?,
    ) {
        mutate(channelActionKey(name, instanceId)) { session ->
            val result = api.request(
                "/api/settings/channels/configure",
                ChannelConfigurePayload.serializer(),
                query = mapOf("name" to name, "enable" to enable, "instance_id" to instanceId),
                headers = mapOf("X-Nanobot-Channel-Values" to json.encodeToString(values)),
            )
            result.nanobotFeatures?.let { updated ->
                updateIfSession(session) { it.copy(payload = updated) }
            } ?: refreshLocked(session)
        }
    }

    override suspend fun validateAndConfigure(
        name: String,
        values: Map<String, String>,
        instanceId: String?,
    ): ChannelValidationPayload? = mutate(channelActionKey(name, instanceId)) { session ->
        // 官方 WebUI 的 Save & enable 是 validate -> configure 的两阶段流程。
        // 这样缺少凭据或检查失败时，不会先把频道错误地启用起来。
        val encodedValues = json.encodeToString(values)
        val validation = api.request(
            "/api/settings/channels/validate",
            ChannelValidationPayload.serializer(),
            query = mapOf("name" to name, "instance_id" to instanceId),
            headers = mapOf("X-Nanobot-Channel-Values" to encodedValues),
        )
        val key = channelConnectionKey(name, instanceId)
        updateIfSession(session) {
            it.copy(validation = validation, validationKey = key)
        }
        if (!validation.canEnable || !isCurrent(session)) return@mutate validation

        val result = api.request(
            "/api/settings/channels/configure",
            ChannelConfigurePayload.serializer(),
            query = mapOf("name" to name, "enable" to true, "instance_id" to instanceId),
            headers = mapOf("X-Nanobot-Channel-Values" to encodedValues),
        )
        result.nanobotFeatures?.let { updated ->
            updateIfSession(session) { it.copy(payload = updated) }
        } ?: refreshLocked(session)
        validation
    }

    override suspend fun validate(name: String, values: Map<String, String>, instanceId: String?) {
        mutate(channelActionKey(name, instanceId)) { session ->
            val result = api.request(
                "/api/settings/channels/validate",
                ChannelValidationPayload.serializer(),
                query = mapOf("name" to name, "instance_id" to instanceId),
                headers = mapOf("X-Nanobot-Channel-Values" to json.encodeToString(values)),
            )
            updateIfSession(session) {
                it.copy(
                    validation = result,
                    validationKey = channelConnectionKey(name, instanceId),
                )
            }
        }
    }

    override suspend fun startConnect(
        name: String,
        instanceId: String?,
    ): ChannelConnectPayload? = mutate(channelConnectKey(name, instanceId)) { session ->
        val result = api.get<ChannelConnectPayload>(
            "/api/settings/channels/${name.path()}/connect/start",
            mapOf("instance_id" to instanceId),
        )
        updateConnection(session, name, instanceId, result)
        result
    }

    override suspend fun pollConnect(
        name: String,
        sessionId: String,
        instanceId: String?,
    ): ChannelConnectPayload? = mutate(channelConnectKey(name, instanceId)) { session ->
        val result = api.get<ChannelConnectPayload>(
            "/api/settings/channels/${name.path()}/connect/poll",
            mapOf("session_id" to sessionId),
        )
        updateConnection(session, name, instanceId, result)
        result.nanobotFeatures?.let { updated ->
            updateIfSession(session) { it.copy(payload = updated) }
        }
        result
    }

    override suspend fun cancelConnect(
        name: String,
        sessionId: String,
        instanceId: String?,
    ): ChannelConnectPayload? = mutate(channelCancelKey(name, instanceId)) { session ->
        val result = api.get<ChannelConnectPayload>(
            "/api/settings/channels/${name.path()}/connect/cancel",
            mapOf("session_id" to sessionId),
        )
        updateConnection(session, name, instanceId, result)
        result
    }

    private suspend fun <T> mutate(
        key: String,
        block: suspend (Long) -> T,
    ): T? {
        val expectedSession = sessionGeneration.get()
        val admitted = synchronized(inFlight) {
            if (inFlight.containsKey(key)) {
                false
            } else {
                inFlight[key] = expectedSession
                true
            }
        }
        if (!admitted || !isCurrent(expectedSession)) {
            synchronized(inFlight) {
                if (inFlight[key] == expectedSession) inFlight.remove(key)
            }
            return null
        }

        try {
            return requestMutex.withLock {
                if (!isCurrent(expectedSession)) return@withLock null
                updateIfSession(expectedSession) {
                    it.copy(
                        pending = it.pending + key,
                        error = null,
                    )
                }
                try {
                    block(expectedSession)
                } catch (error: CancellationException) {
                    throw error
                } catch (error: Exception) {
                    updateIfSession(expectedSession) {
                        it.copy(error = error.message ?: "channel_action_failed")
                    }
                    null
                } finally {
                    updateIfSession(expectedSession) { it.copy(pending = it.pending - key) }
                }
            }
        } finally {
            // 只有同一 session 的请求才能清理自己的 in-flight 标记。
            synchronized(inFlight) {
                if (inFlight[key] == expectedSession) inFlight.remove(key)
            }
        }
    }

    private suspend fun refreshLocked(expectedSession: Long) {
        if (!isCurrent(expectedSession)) return
        val before = mutable.value
        updateIfSession(expectedSession) { it.copy(loading = true, error = null) }
        try {
            val payload = api.get<NanobotFeaturesPayload>("/api/settings/nanobot-features")
            updateIfSession(expectedSession) {
                it.copy(
                    payload = payload,
                    loading = false,
                    error = null,
                )
            }
        } catch (error: CancellationException) {
            updateIfSession(expectedSession) { before }
            throw error
        } catch (error: Exception) {
            updateIfSession(expectedSession) {
                it.copy(
                    loading = false,
                    error = error.message ?: "channels_refresh_failed",
                )
            }
        }
    }

    private fun updateConnection(
        expectedSession: Long,
        name: String,
        instanceId: String?,
        result: ChannelConnectPayload,
    ) {
        val key = channelConnectionKey(name, instanceId)
        updateIfSession(expectedSession) {
            it.copy(
                connection = result,
                connections = it.connections + (key to result),
            )
        }
    }

    private fun updateIfSession(
        expectedSession: Long,
        transform: (ChannelsUiState) -> ChannelsUiState,
    ) {
        if (isCurrent(expectedSession)) mutable.value = transform(mutable.value)
    }

    private fun isCurrent(expectedSession: Long): Boolean =
        sessionGeneration.get() == expectedSession

    private fun String.path(): String =
        URLEncoder.encode(this, "UTF-8").replace("+", "%20")
}
