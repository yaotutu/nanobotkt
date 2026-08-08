package com.nanobotkt.feature.settings

import com.nanobotkt.core.model.ApiServicePayload
import com.nanobotkt.core.model.ProviderModelsPayload
import com.nanobotkt.core.model.ProviderOAuthResult
import com.nanobotkt.core.model.SettingsPayload
import com.nanobotkt.core.model.VersionCheckResult
import com.nanobotkt.core.network.GatewayApiClient
import java.util.concurrent.atomic.AtomicLong
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonObject

data class SettingsUpdate(
    val modelPreset: String? = null,
    val model: String? = null,
    val provider: String? = null,
    val contextWindowTokens: Int? = null,
    val timezone: String? = null,
    val botName: String? = null,
    val botIcon: String? = null,
    val toolHintMaxLength: Int? = null,
)

data class ProviderUpdate(
    val provider: String,
    val displayName: String? = null,
    val apiKey: String? = null,
    val apiBase: String? = null,
    val apiType: String? = null,
    val proxy: String? = null,
    val thinkingStyle: String? = null,
    val region: String? = null,
    val profile: String? = null,
    /** JSON object 文本；空字符串表示显式清空，null 表示不修改。 */
    val extraHeaders: String? = null,
    val extraBody: String? = null,
    val extraQuery: String? = null,
)

/**
 * 自定义模型配置的创建参数。
 *
 * 服务端将这些字段写入 named model preset；可选参数为空时由服务端沿用
 * 当前默认模型配置，避免 Android 端擅自猜测上下文窗口或采样参数。
 */
data class ModelConfigurationCreate(
    val label: String,
    val name: String? = null,
    val model: String,
    val provider: String,
    val maxTokens: Int? = null,
    val contextWindowTokens: Int? = null,
    val temperature: Double? = null,
    val reasoningEffort: String? = null,
)

/** 模型配置的逐字段更新参数；null 表示本次不发送该字段。 */
data class ModelConfigurationUpdate(
    val name: String,
    val label: String? = null,
    val model: String? = null,
    val provider: String? = null,
    val maxTokens: Int? = null,
    val contextWindowTokens: Int? = null,
    val temperature: Double? = null,
    val reasoningEffort: String? = null,
)

/**
 * 模型 fallback 调用顺序。服务端要求 JSON 数组字符串，而不是重复 query 参数。
 */
data class ModelCallOrderUpdate(val presetNames: List<String>)

/** 创建自定义 Provider 时可写入的最小安全字段集合。 */
data class CustomProviderCreate(
    val displayName: String,
    val apiBase: String,
    val apiKey: String? = null,
    val proxy: String? = null,
    val thinkingStyle: String? = null,
    /** JSON object 文本；空字符串不写入该字段。 */
    val extraHeaders: String? = null,
    val extraBody: String? = null,
    val extraQuery: String? = null,
)

/**
 * Web Search 更新请求。
 *
 * 服务端按字段执行 patch：未提供的字段应保持原值，因此 nullable 字段表示“本次不修改”。
 */
data class WebSearchSettingsUpdate(
    val provider: String,
    val apiKey: String? = null,
    val baseUrl: String? = null,
    val maxResults: Int? = null,
    val timeout: Int? = null,
    val useJinaReader: Boolean? = null,
)

/** 图片生成设置的逐字段 patch 请求。 */
data class ImageGenerationSettingsUpdate(
    val enabled: Boolean? = null,
    val provider: String? = null,
    val model: String? = null,
    val defaultAspectRatio: String? = null,
    val defaultImageSize: String? = null,
    val maxImagesPerTurn: Int? = null,
)

/** 语音转写设置的逐字段 patch 请求。 */
data class TranscriptionSettingsUpdate(
    val enabled: Boolean? = null,
    val provider: String? = null,
    val model: String? = null,
    val language: String? = null,
    val maxDurationSec: Int? = null,
    val maxUploadMb: Int? = null,
)

data class SettingsUiState(
    val payload: SettingsPayload? = null,
    val apiService: ApiServicePayload? = null,
    val versionCheck: VersionCheckResult? = null,
    val providerModels: ProviderModelsPayload? = null,
    val oauth: ProviderOAuthResult? = null,
    val loading: Boolean = false,
    val pending: Set<String> = emptySet(),
    val error: String? = null,
)

interface SettingsRepository {
    val state: StateFlow<SettingsUiState>

    /** 清理当前登录会话的设置，并使所有在途 refresh/mutation 响应失效。 */
    fun reset()

    suspend fun refresh()
    suspend fun update(update: SettingsUpdate)
    suspend fun updateProvider(update: ProviderUpdate)
    suspend fun createModelConfiguration(create: ModelConfigurationCreate)
    suspend fun updateModelConfiguration(update: ModelConfigurationUpdate)
    suspend fun deleteModelConfiguration(name: String)
    suspend fun updateModelCallOrder(update: ModelCallOrderUpdate)
    suspend fun migrateModelConfigurations()
    suspend fun createProvider(create: CustomProviderCreate)
    suspend fun providerModels(provider: String)
    suspend fun oauthLogin(provider: String)
    suspend fun oauthComplete(provider: String, flowId: String, code: String?)
    suspend fun oauthLogout(provider: String)
    suspend fun checkVersion()
    suspend fun startApiService(host: String, port: Int, timeout: Int, apiKey: String?)
    suspend fun stopApiService()
    suspend fun networkSafety(local: Boolean, mode: String)
    suspend fun updateWebSearch(update: WebSearchSettingsUpdate)
    suspend fun updateImage(update: ImageGenerationSettingsUpdate)
    suspend fun updateTranscription(update: TranscriptionSettingsUpdate)
}

@Singleton
class DefaultSettingsRepository @Inject constructor(
    private val api: GatewayApiClient,
    private val json: Json,
) : SettingsRepository {
    private val refreshGeneration = AtomicLong(0)
    private val sessionGeneration = AtomicLong(0)
    private val mutationMutex = Mutex()
    private val mutable = MutableStateFlow(SettingsUiState())
    override val state: StateFlow<SettingsUiState> = mutable.asStateFlow()

    override suspend fun refresh() {
        val expectedSession = sessionGeneration.get()
        val expectedRefresh = refreshGeneration.incrementAndGet()
        val old = mutable.value
        if (!isCurrent(expectedSession, expectedRefresh)) return

        mutable.value = old.copy(loading = true, error = null)
        try {
            val pair = coroutineScope {
                val settings = async { api.get<SettingsPayload>("/api/settings") }
                val service = async {
                    try {
                        api.get<ApiServicePayload>("/api/settings/api-service")
                    } catch (error: CancellationException) {
                        throw error
                    } catch (_: Exception) {
                        null
                    }
                }
                settings.await() to service.await()
            }
            updateIfCurrent(expectedSession, expectedRefresh) { current ->
                current.copy(
                    payload = pair.first,
                    apiService = pair.second,
                    loading = false,
                    error = null,
                )
            }
        } catch (error: CancellationException) {
            // 只有仍属于当前会话且没有被更新的 refresh 才能恢复旧状态。
            updateIfCurrent(expectedSession, expectedRefresh) { old }
            throw error
        } catch (error: Exception) {
            updateIfCurrent(expectedSession, expectedRefresh) {
                it.copy(
                    loading = false,
                    error = error.message ?: "settings_refresh_failed",
                )
            }
        }
    }

    override fun reset() {
        // 先递增 session/refresh 代次，再清空状态；旧请求返回后会被统一的
        // updateIfSession/updateIfCurrent 检查拦截，不能恢复旧账号的数据、错误或 pending。
        sessionGeneration.incrementAndGet()
        refreshGeneration.incrementAndGet()
        mutable.value = SettingsUiState()
    }

    override suspend fun update(update: SettingsUpdate) = mutate("settings") { session ->
        val query = buildMap<String, Any?> {
            update.modelPreset?.let { put("model_preset", it) }
            update.model?.let { put("model", it) }
            update.provider?.let { put("provider", it) }
            update.contextWindowTokens?.let { put("context_window_tokens", it) }
            update.timezone?.let { put("timezone", it) }
            update.botName?.let { put("bot_name", it) }
            update.botIcon?.let { put("bot_icon", it) }
            update.toolHintMaxLength?.let { put("tool_hint_max_length", it) }
        }
        replace(session, api.request("/api/settings/update", SettingsPayload.serializer(), query = query))
    }

    override suspend fun updateProvider(update: ProviderUpdate) = mutate("provider:${update.provider}") { session ->
        val values = buildMap<String, String> {
            update.displayName?.let { put("displayName", it) }
            update.apiKey?.let { put("apiKey", it) }
            update.apiBase?.let { put("apiBase", it) }
            update.apiType?.let { put("apiType", it) }
            update.proxy?.let { put("proxy", it) }
            update.thinkingStyle?.let { put("thinkingStyle", it) }
            update.region?.let { put("region", it) }
            update.profile?.let { put("profile", it) }
            update.extraHeaders?.let { put("extraHeaders", it) }
            update.extraBody?.let { put("extraBody", it) }
            update.extraQuery?.let { put("extraQuery", it) }
        }
        replace(
            session,
            api.request(
                "/api/settings/provider/update",
                SettingsPayload.serializer(),
                query = mapOf("provider" to update.provider),
                headers = mapOf(
                    "X-Nanobot-Provider-Values" to java.net.URLEncoder.encode(
                        json.encodeToString(values),
                        Charsets.UTF_8.name(),
                    ),
                ),
            ),
        )
    }

    override suspend fun createModelConfiguration(create: ModelConfigurationCreate) = mutate("model-configuration") { session ->
        val query = buildMap<String, Any?> {
            put("label", create.label)
            create.name?.let { put("name", it) }
            put("model", create.model)
            put("provider", create.provider)
            create.maxTokens?.let { put("max_tokens", it) }
            create.contextWindowTokens?.let { put("context_window_tokens", it) }
            create.temperature?.let { put("temperature", it) }
            create.reasoningEffort?.let { put("reasoning_effort", it) }
        }
        replace(
            session,
            api.request("/api/settings/model-configurations/create", SettingsPayload.serializer(), query = query),
        )
    }

    override suspend fun updateModelConfiguration(update: ModelConfigurationUpdate) = mutate("model-configuration") { session ->
        val query = buildMap<String, Any?> {
            put("name", update.name)
            update.label?.let { put("label", it) }
            update.model?.let { put("model", it) }
            update.provider?.let { put("provider", it) }
            update.maxTokens?.let { put("max_tokens", it) }
            update.contextWindowTokens?.let { put("context_window_tokens", it) }
            update.temperature?.let { put("temperature", it) }
            // 空字符串是显式清除 reasoning effort 的协议值，因此不能用 isNullOrBlank 过滤。
            update.reasoningEffort?.let { put("reasoning_effort", it) }
        }
        replace(
            session,
            api.request("/api/settings/model-configurations/update", SettingsPayload.serializer(), query = query),
        )
    }

    override suspend fun deleteModelConfiguration(name: String) = mutate("model-configuration") { session ->
        replace(
            session,
            api.request(
                "/api/settings/model-configurations/delete",
                SettingsPayload.serializer(),
                query = mapOf("name" to name),
            ),
        )
    }

    override suspend fun migrateModelConfigurations() = mutate("model-configuration-migration") { session ->
        // 旧版 inline primary/fallback 配置必须由服务端迁移，客户端不复制其推断逻辑。
        replace(
            session,
            api.request(
                "/api/settings/model-configurations/migrate",
                SettingsPayload.serializer(),
            ),
        )
    }

    override suspend fun updateModelCallOrder(update: ModelCallOrderUpdate) = mutate("model-call-order") { session ->
        require(update.presetNames.isNotEmpty()) { "model call order must not be empty" }
        val query = mapOf("order" to json.encodeToString(update.presetNames))
        replace(
            session,
            api.request("/api/settings/model-call-order/update", SettingsPayload.serializer(), query = query),
        )
    }

    override suspend fun createProvider(create: CustomProviderCreate) = mutate("provider:create") { session ->
        val values = buildMap<String, String> {
            put("apiBase", create.apiBase)
            create.apiKey?.let { put("apiKey", it) }
            create.proxy?.let { put("proxy", it) }
            create.thinkingStyle?.let { put("thinkingStyle", it) }
            create.extraHeaders?.let { put("extraHeaders", it) }
            create.extraBody?.let { put("extraBody", it) }
            create.extraQuery?.let { put("extraQuery", it) }
        }
        replace(
            session,
            api.request(
                "/api/settings/provider/create",
                SettingsPayload.serializer(),
                query = mapOf("name" to create.displayName),
                headers = mapOf(
                    "X-Nanobot-Provider-Values" to java.net.URLEncoder.encode(
                        json.encodeToString(values),
                        Charsets.UTF_8.name(),
                    ),
                ),
            ),
        )
    }

    override suspend fun providerModels(provider: String) = mutate("provider-models", refreshAfter = false) { session ->
        val models = api.get<ProviderModelsPayload>("/api/settings/provider-models", mapOf("provider" to provider))
        updateIfSession(session) { it.copy(providerModels = models) }
    }

    override suspend fun oauthLogin(provider: String) = mutate("oauth:$provider", refreshAfter = false) { session ->
        val result = api.get<JsonElement>("/api/settings/provider/oauth-login", mapOf("provider" to provider))
        applyOAuthResponse(session, result)
    }

    override suspend fun oauthComplete(provider: String, flowId: String, code: String?) =
        mutate("oauth:$provider", refreshAfter = false) { session ->
            val result = api.request(
                "/api/settings/provider/oauth-login/complete",
                JsonElement.serializer(),
                query = mapOf("provider" to provider, "flow_id" to flowId),
                headers = code?.let { mapOf("X-Nanobot-OAuth-Code" to it) }.orEmpty(),
            )
            applyOAuthResponse(session, result)
        }

    override suspend fun oauthLogout(provider: String) = mutate("oauth:$provider") { session ->
        replace(session, api.get("/api/settings/provider/oauth-logout", mapOf("provider" to provider)))
        updateIfSession(session) { it.copy(oauth = null) }
    }

    override suspend fun checkVersion() = mutate("version", refreshAfter = false) { session ->
        val result = api.get<VersionCheckResult>("/api/settings/version-check")
        updateIfSession(session) { it.copy(versionCheck = result) }
    }

    override suspend fun startApiService(host: String, port: Int, timeout: Int, apiKey: String?) =
        mutate("api-service", refreshAfter = false) { session ->
            val headers = apiKey?.let {
                mapOf("X-Nanobot-API-Service-Values" to json.encodeToString(mapOf("api_key" to it)))
            }.orEmpty()
            val result = api.request(
                "/api/settings/api-service/start",
                ApiServicePayload.serializer(),
                query = mapOf("host" to host, "port" to port, "timeout" to timeout),
                headers = headers,
            )
            updateIfSession(session) { it.copy(apiService = result) }
        }

    override suspend fun stopApiService() = mutate("api-service", refreshAfter = false) { session ->
        val result = api.get<ApiServicePayload>("/api/settings/api-service/stop")
        updateIfSession(session) { it.copy(apiService = result) }
    }

    override suspend fun networkSafety(local: Boolean, mode: String) = mutate("network") { session ->
        replace(
            session,
            api.request(
                "/api/settings/network-safety/update",
                SettingsPayload.serializer(),
                query = mapOf(
                    "webui_allow_local_service_access" to local,
                    "webui_default_access_mode" to mode,
                ),
            ),
        )
    }

    override suspend fun updateWebSearch(update: WebSearchSettingsUpdate) = mutate("web") { session ->
        val query = buildMap<String, Any?> {
            // provider 是该接口定位当前配置的必填字段；其他字段只在调用方明确提供时发送。
            put("provider", update.provider)
            update.apiKey?.let { put("api_key", it) }
            update.baseUrl?.let { put("base_url", it) }
            update.maxResults?.let { put("max_results", it) }
            update.timeout?.let { put("timeout", it) }
            update.useJinaReader?.let { put("use_jina_reader", it) }
        }
        replace(session, api.request("/api/settings/web-search/update", SettingsPayload.serializer(), query = query))
    }

    override suspend fun updateImage(update: ImageGenerationSettingsUpdate) = mutate("image") { session ->
        val query = buildMap<String, Any?> {
            update.enabled?.let { put("enabled", it) }
            update.provider?.let { put("provider", it) }
            update.model?.let { put("model", it) }
            update.defaultAspectRatio?.let { put("default_aspect_ratio", it) }
            update.defaultImageSize?.let { put("default_image_size", it) }
            update.maxImagesPerTurn?.let { put("max_images_per_turn", it) }
        }
        replace(
            session,
            api.request(
                "/api/settings/image-generation/update",
                SettingsPayload.serializer(),
                query = query,
            ),
        )
    }

    override suspend fun updateTranscription(update: TranscriptionSettingsUpdate) = mutate("voice") { session ->
        val query = buildMap<String, Any?> {
            update.enabled?.let { put("enabled", it) }
            update.provider?.let { put("provider", it) }
            update.model?.let { put("model", it) }
            update.language?.let { put("language", it) }
            update.maxDurationSec?.let { put("max_duration_sec", it) }
            update.maxUploadMb?.let { put("max_upload_mb", it) }
        }
        replace(
            session,
            api.request(
                "/api/settings/transcription/update",
                SettingsPayload.serializer(),
                query = query,
            ),
        )
    }

    /** OAuth 接口可能返回中间流程对象，也可能直接返回完整 settings payload。 */
    private fun applyOAuthResponse(expectedSession: Long, element: JsonElement) {
        if (!isCurrent(expectedSession)) return
        val objectValue: JsonObject = element.jsonObject
        val settingsKeys = setOf(
            "agent",
            "providers",
            "runtime",
            "runtime_surface",
            "model_presets",
            "apply_state",
        )
        if (objectValue.keys.any(settingsKeys::contains)) {
            replace(expectedSession, json.decodeFromJsonElement(SettingsPayload.serializer(), element))
            updateIfSession(expectedSession) { it.copy(oauth = null) }
        } else {
            updateIfSession(expectedSession) {
                it.copy(oauth = json.decodeFromJsonElement(ProviderOAuthResult.serializer(), element))
            }
        }
    }

    private fun replace(expectedSession: Long, payload: SettingsPayload) {
        updateIfSession(expectedSession) { it.copy(payload = payload) }
    }

    private fun updateIfSession(
        expectedSession: Long,
        transform: (SettingsUiState) -> SettingsUiState,
    ) {
        if (isCurrent(expectedSession)) mutable.value = transform(mutable.value)
    }

    private fun updateIfCurrent(
        expectedSession: Long,
        expectedRefresh: Long,
        transform: (SettingsUiState) -> SettingsUiState,
    ) {
        if (isCurrent(expectedSession, expectedRefresh)) mutable.value = transform(mutable.value)
    }

    private fun isCurrent(expectedSession: Long, expectedRefresh: Long? = null): Boolean =
        sessionGeneration.get() == expectedSession &&
            (expectedRefresh == null || refreshGeneration.get() == expectedRefresh)

    private suspend fun mutate(
        key: String,
        refreshAfter: Boolean = true,
        block: suspend (Long) -> Unit,
    ) = mutationMutex.withLock {
        val expectedSession = sessionGeneration.get()
        if (!isCurrent(expectedSession)) return@withLock

        mutable.value = mutable.value.copy(
            pending = mutable.value.pending + key,
            error = null,
        )
        try {
            block(expectedSession)
            if (refreshAfter && isCurrent(expectedSession)) refreshForMutation(expectedSession)
        } catch (error: CancellationException) {
            throw error
        } catch (error: Exception) {
            updateIfSession(expectedSession) {
                it.copy(error = error.message ?: "settings_action_failed")
            }
        } finally {
            updateIfSession(expectedSession) { it.copy(pending = it.pending - key) }
        }
    }

    /** mutation 持有同一把锁，自动 refresh 不会与另一个 mutation 交错。 */
    private suspend fun refreshForMutation(expectedSession: Long) {
        if (isCurrent(expectedSession)) refresh()
    }
}
