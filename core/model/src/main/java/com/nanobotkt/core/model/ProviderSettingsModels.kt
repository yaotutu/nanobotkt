package com.nanobotkt.core.model

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.JsonElement

/** Provider、模型预设、运行设置与版本信息模型。 按服务端 wire 字段原样建模，拆文件不改变序列化契约。 */
@Serializable
data class ProviderModelInfo(
    val id: String,
    val label: String? = null,
    val description: String? = null,
    @SerialName("owned_by") val ownedBy: String? = null,
    @SerialName("context_window") val contextWindow: Int? = null,
)

@Serializable
data class ProviderModelsPayload(
    val provider: String,
    val label: String,
    val status: String,
    @SerialName("catalog_kind") val catalogKind: String,
    val models: List<ProviderModelInfo> = emptyList(),
    @SerialName("model_count") val modelCount: Int = models.size,
    val message: String? = null,
    @SerialName("fetched_at") val fetchedAt: Long? = null,
)

@Serializable
data class ModelPresetInfo(
    val name: String,
    val label: String,
    val active: Boolean,
    @SerialName("is_default") val isDefault: Boolean,
    val model: String,
    val provider: String,
    @SerialName("resolved_provider") val resolvedProvider: String? = null,
    @SerialName("max_tokens") val maxTokens: Int = 0,
    @SerialName("context_window_tokens") val contextWindowTokens: Int = 0,
    val temperature: Double = 0.0,
    @SerialName("reasoning_effort") val reasoningEffort: String? = null,
    @SerialName("reasoning_effort_values") val reasoningEffortValues: List<String>? = null,
)

@Serializable
data class ProviderSettingsInfo(
    val name: String,
    val label: String = name,
    @SerialName("is_custom") val isCustom: Boolean? = null,
    val configured: Boolean = false,
    @SerialName("auth_type") val authType: String? = null,
    @SerialName("api_key_required") val apiKeyRequired: Boolean? = null,
    @SerialName("api_key_hint") val apiKeyHint: String? = null,
    @SerialName("api_base") val apiBase: String? = null,
    @SerialName("default_api_base") val defaultApiBase: String? = null,
    @SerialName("model_selectable") val modelSelectable: Boolean? = null,
    @SerialName("model_catalog") val modelCatalog: String? = null,
    @SerialName("api_type") val apiType: String? = null,
    @SerialName("oauth_account") val oauthAccount: String? = null,
    @SerialName("oauth_expires_at") val oauthExpiresAt: Long? = null,
    @SerialName("oauth_login_supported") val oauthLoginSupported: Boolean? = null,
    val proxy: String? = null,
    @SerialName("advanced_fields") val advancedFields: List<String>? = null,
    @SerialName("extra_headers") val extraHeaders: Map<String, String>? = null,
    @SerialName("extra_body") val extraBody: JsonElement? = null,
    @SerialName("extra_query") val extraQuery: Map<String, String>? = null,
    @SerialName("thinking_style") val thinkingStyle: String? = null,
    val region: String? = null,
    val profile: String? = null,
)

@Serializable
data class AgentSettings(
    val model: String = "",
    val provider: String = "",
    @SerialName("resolved_provider") val resolvedProvider: String? = null,
    @SerialName("has_api_key") val hasApiKey: Boolean = false,
    @SerialName("model_preset") val modelPreset: String? = null,
    @SerialName("max_tokens") val maxTokens: Int = 0,
    @SerialName("context_window_tokens") val contextWindowTokens: Int = 0,
    val temperature: Double = 0.0,
    @SerialName("reasoning_effort") val reasoningEffort: String? = null,
    val timezone: String = "",
    @SerialName("bot_name") val botName: String = "",
    @SerialName("bot_icon") val botIcon: String = "",
    @SerialName("tool_hint_max_length") val toolHintMaxLength: Int = 0,
)

@Serializable
data class SettingsApplyState(
    val status: String = "idle",
    val sections: List<String> = emptyList(),
)

@Serializable
data class UsageDayInfo(
    val date: String,
    @SerialName("prompt_tokens") val promptTokens: Long = 0,
    @SerialName("completion_tokens") val completionTokens: Long = 0,
    @SerialName("cached_tokens") val cachedTokens: Long = 0,
    @SerialName("total_tokens") val totalTokens: Long = 0,
    val requests: Long = 0,
)

@Serializable
data class SettingsUsage(
    val days: List<UsageDayInfo> = emptyList(),
    @SerialName("total_tokens") val totalTokens: Long = 0,
    @SerialName("total_tokens_30d") val totalTokens30d: Long = 0,
    @SerialName("total_tokens_365d") val totalTokens365d: Long = 0,
    @SerialName("peak_day_tokens") val peakDayTokens: Long = 0,
    @SerialName("current_streak_days") val currentStreakDays: Int = 0,
    @SerialName("longest_streak_days") val longestStreakDays: Int = 0,
    @SerialName("active_days_30d") val activeDays30d: Int = 0,
    @SerialName("requests_30d") val requests30d: Long = 0,
    @SerialName("updated_at") val updatedAt: String? = null,
)

@Serializable
data class RuntimeSettings(
    @SerialName("config_path") val configPath: String = "",
    @SerialName("workspace_path") val workspacePath: String = "",
    @SerialName("gateway_host") val gatewayHost: String = "",
    @SerialName("gateway_port") val gatewayPort: Int = 0,
    val heartbeat: JsonElement? = null,
    val dream: JsonElement? = null,
    @SerialName("unified_session") val unifiedSession: Boolean = false,
)

@Serializable
data class AdvancedSettings(
    @SerialName("restrict_to_workspace") val restrictToWorkspace: Boolean = false,
    @SerialName("workspace_sandbox") val workspaceSandbox: WorkspaceSandboxStatus? = null,
    @SerialName("ssrf_whitelist_count") val ssrfWhitelistCount: Int = 0,
    @SerialName("webui_allow_local_service_access")
    val webuiAllowLocalServiceAccess: Boolean = false,
    @SerialName("allow_local_preview_access") val allowLocalPreviewAccess: Boolean? = null,
    @SerialName("webui_default_access_mode") val webuiDefaultAccessMode: String = "default",
    @SerialName("private_service_protection_enabled")
    val privateServiceProtectionEnabled: Boolean = true,
    @SerialName("mcp_server_count") val mcpServerCount: Int = 0,
    @SerialName("exec_enabled") val execEnabled: Boolean = false,
    @SerialName("exec_sandbox") val execSandbox: String? = null,
    @SerialName("exec_path_prepend_set") val execPathPrependSet: Boolean = false,
    @SerialName("exec_path_append_set") val execPathAppendSet: Boolean = false,
)

@Serializable
data class SettingsPayload(
    @SerialName("runtime_surface") val runtimeSurface: RuntimeSurface? = null,
    @SerialName("runtime_capabilities") val runtimeCapabilities: RuntimeCapabilities? = null,
    @SerialName("apply_state") val applyState: SettingsApplyState? = null,
    val agent: AgentSettings = AgentSettings(),
    @SerialName("model_presets") val modelPresets: List<ModelPresetInfo> = emptyList(),
    @SerialName("model_call_order") val modelCallOrder: List<String> = emptyList(),
    @SerialName("model_call_order_editable") val modelCallOrderEditable: Boolean = false,
    @SerialName("created_model_preset") val createdModelPreset: String? = null,
    @SerialName("created_provider") val createdProvider: String? = null,
    val providers: List<ProviderSettingsInfo> = emptyList(),
    @SerialName("web_search") val webSearch: WebSearchSettings? = null,
    val web: WebSettings? = null,
    val api: JsonElement? = null,
    val observability: JsonElement? = null,
    @SerialName("image_generation") val imageGeneration: ImageGenerationSettings? = null,
    val transcription: TranscriptionSettings? = null,
    val runtime: RuntimeSettings = RuntimeSettings(),
    val usage: SettingsUsage? = null,
    val advanced: AdvancedSettings = AdvancedSettings(),
    @SerialName("requires_restart") val requiresRestart: Boolean = false,
    @SerialName("restart_required_sections") val restartRequiredSections: List<String>? = null,
    val version: Map<String, String>? = null,
    val docs: Map<String, String>? = null,
)

@Serializable
data class ApiServicePayload(
    val installed: Boolean = false,
    val running: Boolean = false,
    val managed: Boolean = false,
    val host: String = "",
    val port: Int = 0,
    val timeout: Int = 0,
    @SerialName("api_key_hint") val apiKeyHint: String? = null,
    val endpoint: String = "",
    val command: String = "",
    @SerialName("log_path") val logPath: String? = null,
    @SerialName("last_action") val lastAction: String? = null,
)

@Serializable
data class VersionUpdateInfo(
    @SerialName("currentVersion") val currentVersion: String = "",
    @SerialName("latestVersion") val latestVersion: String = "",
    @SerialName("pypiUrl") val pypiUrl: String? = null,
)

@Serializable
data class VersionCheckResult(
    @SerialName("updateAvailable") val updateAvailable: VersionUpdateInfo? = null
)

@Serializable
data class ProviderOAuthResult(
    val status: String? = null,
    val provider: String? = null,
    @SerialName("flow_id") val flowId: String? = null,
    @SerialName("authorization_url") val authorizationUrl: String? = null,
    @SerialName("expires_in") val expiresIn: Long? = null,
)
