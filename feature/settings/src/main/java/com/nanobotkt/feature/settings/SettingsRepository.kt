package com.nanobotkt.feature.settings

import kotlinx.coroutines.CancellationException
import com.nanobotkt.core.model.ApiServicePayload
import com.nanobotkt.core.model.ProviderModelsPayload
import com.nanobotkt.core.model.ProviderOAuthResult
import com.nanobotkt.core.model.SettingsPayload
import com.nanobotkt.core.model.VersionCheckResult
import com.nanobotkt.core.network.GatewayApiClient
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonObject
import javax.inject.Inject
import javax.inject.Singleton

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
)

data class WebSearchSettingsUpdate(
    val provider: String,
    val apiKey: String? = null,
    val baseUrl: String? = null,
    val maxResults: Int = 5,
    val timeout: Int = 30,
    val useJinaReader: Boolean = true,
)

data class ImageGenerationSettingsUpdate(
    val enabled: Boolean,
    val provider: String,
    val model: String,
    val defaultAspectRatio: String,
    val defaultImageSize: String,
    val maxImagesPerTurn: Int,
)

data class TranscriptionSettingsUpdate(
    val enabled: Boolean,
    val provider: String,
    val model: String,
    val language: String,
    val maxDurationSec: Int,
    val maxUploadMb: Int,
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
    suspend fun refresh()
    suspend fun update(update: SettingsUpdate)
    suspend fun updateProvider(update: ProviderUpdate)
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
    private val mutable = MutableStateFlow(SettingsUiState())
    override val state: StateFlow<SettingsUiState> = mutable.asStateFlow()

    override suspend fun refresh() {
        val old = mutable.value
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
            mutable.value = mutable.value.copy(
                payload = pair.first,
                apiService = pair.second,
                loading = false,
                error = null,
            )
        } catch (error: CancellationException) {
            mutable.value = old
            throw error
        } catch (error: Exception) {
            mutable.value = old.copy(
                loading = false,
                error = error.message ?: "settings_refresh_failed",
            )
        }
    }

    override suspend fun update(update: SettingsUpdate) = mutate("settings") {
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
        replace(api.request("/api/settings/update", SettingsPayload.serializer(), query = query))
    }

    override suspend fun updateProvider(update: ProviderUpdate) = mutate("provider:${update.provider}") {
        val values = buildMap<String, String> {
            update.displayName?.let { put("displayName", it) }
            update.apiKey?.let { put("apiKey", it) }
            update.apiBase?.let { put("apiBase", it) }
            update.apiType?.let { put("apiType", it) }
            update.proxy?.let { put("proxy", it) }
        }
        replace(
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

    override suspend fun providerModels(provider: String) = mutate("provider-models", refreshAfter = false) {
        mutable.value = mutable.value.copy(
            providerModels = api.get("/api/settings/provider-models", mapOf("provider" to provider)),
        )
    }

    override suspend fun oauthLogin(provider: String) = mutate("oauth:$provider", refreshAfter = false) {
        applyOAuthResponse(
            api.get<JsonElement>("/api/settings/provider/oauth-login", mapOf("provider" to provider)),
        )
    }

    override suspend fun oauthComplete(provider: String, flowId: String, code: String?) =
        mutate("oauth:$provider", refreshAfter = false) {
            applyOAuthResponse(
                api.request(
                    "/api/settings/provider/oauth-login/complete",
                    JsonElement.serializer(),
                    query = mapOf("provider" to provider, "flow_id" to flowId),
                    headers = code?.let { mapOf("X-Nanobot-OAuth-Code" to it) }.orEmpty(),
                ),
            )
        }

    override suspend fun oauthLogout(provider: String) = mutate("oauth:$provider") {
        replace(api.get("/api/settings/provider/oauth-logout", mapOf("provider" to provider)))
        mutable.value = mutable.value.copy(oauth = null)
    }

    override suspend fun checkVersion() = mutate("version", refreshAfter = false) {
        mutable.value = mutable.value.copy(versionCheck = api.get("/api/settings/version-check"))
    }

    override suspend fun startApiService(host: String, port: Int, timeout: Int, apiKey: String?) =
        mutate("api-service", refreshAfter = false) {
            val headers = apiKey?.let {
                mapOf("X-Nanobot-API-Service-Values" to json.encodeToString(mapOf("api_key" to it)))
            }.orEmpty()
            mutable.value = mutable.value.copy(
                apiService = api.request(
                    "/api/settings/api-service/start",
                    ApiServicePayload.serializer(),
                    query = mapOf("host" to host, "port" to port, "timeout" to timeout),
                    headers = headers,
                ),
            )
        }

    override suspend fun stopApiService() = mutate("api-service", refreshAfter = false) {
        mutable.value = mutable.value.copy(apiService = api.get("/api/settings/api-service/stop"))
    }

    override suspend fun networkSafety(local: Boolean, mode: String) = mutate("network") {
        replace(
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

    override suspend fun updateWebSearch(update: WebSearchSettingsUpdate) = mutate("web") {
        val query = buildMap<String, Any?> {
            put("provider", update.provider)
            update.apiKey?.let { put("api_key", it) }
            update.baseUrl?.let { put("base_url", it) }
            put("max_results", update.maxResults)
            put("timeout", update.timeout)
            put("use_jina_reader", update.useJinaReader)
        }
        replace(api.request("/api/settings/web-search/update", SettingsPayload.serializer(), query = query))
    }

    override suspend fun updateImage(update: ImageGenerationSettingsUpdate) = mutate("image") {
        replace(
            api.request(
                "/api/settings/image-generation/update",
                SettingsPayload.serializer(),
                query = mapOf(
                    "enabled" to update.enabled,
                    "provider" to update.provider,
                    "model" to update.model,
                    "default_aspect_ratio" to update.defaultAspectRatio,
                    "default_image_size" to update.defaultImageSize,
                    "max_images_per_turn" to update.maxImagesPerTurn,
                ),
            ),
        )
    }

    override suspend fun updateTranscription(update: TranscriptionSettingsUpdate) = mutate("voice") {
        replace(
            api.request(
                "/api/settings/transcription/update",
                SettingsPayload.serializer(),
                query = mapOf(
                    "enabled" to update.enabled,
                    "provider" to update.provider,
                    "model" to update.model,
                    "language" to update.language,
                    "max_duration_sec" to update.maxDurationSec,
                    "max_upload_mb" to update.maxUploadMb,
                ),
            ),
        )
    }

    /** OAuth endpoints may return either an intermediate flow object or canonical settings. */
    private fun applyOAuthResponse(element: JsonElement) {
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
            replace(json.decodeFromJsonElement(SettingsPayload.serializer(), element))
            mutable.value = mutable.value.copy(oauth = null)
        } else {
            mutable.value = mutable.value.copy(
                oauth = json.decodeFromJsonElement(ProviderOAuthResult.serializer(), element),
            )
        }
    }

    private fun replace(payload: SettingsPayload) {
        mutable.value = mutable.value.copy(payload = payload)
    }

    private suspend fun mutate(
        key: String,
        refreshAfter: Boolean = true,
        block: suspend () -> Unit,
    ) {
        mutable.value = mutable.value.copy(pending = mutable.value.pending + key, error = null)
        try {
            block()
            if (refreshAfter) refresh()
        } catch (error: CancellationException) {
            throw error
        } catch (error: Exception) {
            mutable.value = mutable.value.copy(error = error.message ?: "settings_action_failed")
        } finally {
            mutable.value = mutable.value.copy(pending = mutable.value.pending - key)
        }
    }
}
