package com.nanobotkt.core.model

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.JsonElement

/** Skills、Apps、MCP Preset 与 Slash Command 模型。 按服务端 wire 字段原样建模，拆文件不改变序列化契约。 */
@Serializable
data class SkillSummary(
    val name: String,
    val description: String = "",
    val source: String = "",
    val available: Boolean = false,
    @SerialName("unavailable_reason") val unavailableReason: String? = null,
)

@Serializable
data class SkillRequirements(
    val bins: List<String> = emptyList(),
    val env: List<String> = emptyList(),
    @SerialName("missing_bins") val missingBins: List<String> = emptyList(),
    @SerialName("missing_env") val missingEnv: List<String> = emptyList(),
)

@Serializable
data class SkillDetail(
    val name: String,
    val description: String = "",
    val source: String = "",
    val available: Boolean = false,
    @SerialName("unavailable_reason") val unavailableReason: String? = null,
    val requirements: SkillRequirements = SkillRequirements(),
    @SerialName("raw_markdown") val rawMarkdown: String = "",
)

@Serializable data class SkillsPayload(val skills: List<SkillSummary> = emptyList())

@Serializable data class AppPackageRef(val manager: String = "", val name: String? = null)

@Serializable
data class AppField(
    val name: String,
    val target: String? = null,
    val required: Boolean? = null,
    val secret: Boolean? = null,
    @SerialName("env_var") val envVar: String? = null,
)

@Serializable
data class AppCapability(
    val type: String,
    @SerialName("entry_point") val entryPoint: String? = null,
    val `package`: AppPackageRef? = null,
    val path: String? = null,
    val transport: String? = null,
    val command: String? = null,
    val args: List<String>? = null,
    val url: String? = null,
    val fields: List<AppField>? = null,
)

@Serializable
data class AppPlan(
    val supported: Boolean = false,
    val strategy: String? = null,
    @SerialName("managed_paths") val managedPaths: List<String>? = null,
    val verification: List<String>? = null,
)

@Serializable
data class AppTrust(
    val registry: String = "",
    val level: String = "",
    @SerialName("review_status") val reviewStatus: String = "",
)

@Serializable
data class AppManifest(
    val schema: String = "",
    val id: String = "",
    @SerialName("display_name") val displayName: String = "",
    val version: String? = null,
    val description: String = "",
    val category: String = "",
    val source: String = "",
    @SerialName("logo_url") val logoUrl: String? = null,
    @SerialName("brand_color") val brandColor: String? = null,
    @SerialName("docs_url") val docsUrl: String? = null,
    val capabilities: List<AppCapability> = emptyList(),
    val install: AppPlan = AppPlan(),
    val remove: AppPlan = AppPlan(),
    val trust: AppTrust = AppTrust(),
)

@Serializable
data class CliAppInfo(
    val name: String,
    @SerialName("display_name") val displayName: String = name,
    val category: String = "",
    val description: String = "",
    val requires: String = "",
    val source: String = "",
    @SerialName("entry_point") val entryPoint: String = "",
    @SerialName("install_supported") val installSupported: Boolean = false,
    val installed: Boolean = false,
    val available: Boolean = false,
    val status: String = "",
    @SerialName("logo_url") val logoUrl: String? = null,
    @SerialName("brand_color") val brandColor: String? = null,
    @SerialName("skill_installed") val skillInstalled: Boolean = false,
    val manifest: AppManifest? = null,
)

@Serializable
data class CapabilityAction(
    val ok: Boolean = false,
    val message: String = "",
    val installed: Boolean? = null,
    val removed: Boolean? = null,
    val output: String? = null,
    @SerialName("still_available") val stillAvailable: Boolean? = null,
    val verification: List<String>? = null,
    @SerialName("verification_failed") val verificationFailed: List<String>? = null,
    @SerialName("tool_count") val toolCount: Int? = null,
    @SerialName("tool_names") val toolNames: List<String>? = null,
    @SerialName("checked_at") val checkedAt: String? = null,
    val error: String? = null,
)

@Serializable
data class CliAppsPayload(
    val apps: List<CliAppInfo> = emptyList(),
    @SerialName("installed_count") val installedCount: Int = 0,
    @SerialName("catalog_updated_at") val catalogUpdatedAt: String? = null,
    @SerialName("catalog_refresh_pending") val catalogRefreshPending: Boolean? = null,
    @SerialName("last_action") val lastAction: CapabilityAction? = null,
)

@Serializable
data class McpPresetField(
    val name: String,
    val label: String = name,
    val secret: Boolean = false,
    val required: Boolean = false,
    val configured: Boolean = false,
    val placeholder: String? = null,
    @SerialName("env_var") val envVar: String? = null,
)

@Serializable
data class McpPresetInfo(
    val name: String,
    @SerialName("display_name") val displayName: String = name,
    val category: String = "",
    val description: String = "",
    @SerialName("docs_url") val docsUrl: String = "",
    val transport: String = "",
    val requires: String = "",
    val note: String = "",
    @SerialName("install_supported") val installSupported: Boolean = false,
    val installed: Boolean = false,
    val configured: Boolean = false,
    val available: Boolean = false,
    val status: String = "",
    @SerialName("logo_url") val logoUrl: String? = null,
    @SerialName("brand_color") val brandColor: String? = null,
    @SerialName("required_fields") val requiredFields: List<McpPresetField> = emptyList(),
    @SerialName("connection_summary") val connectionSummary: String = "",
    @SerialName("tool_count") val toolCount: Int? = null,
    @SerialName("tool_names") val toolNames: List<String>? = null,
    @SerialName("checked_at") val checkedAt: String? = null,
    val error: String? = null,
    @SerialName("enabled_tools") val enabledTools: List<String>? = null,
    val source: String? = null,
    val manifest: AppManifest? = null,
)

@Serializable
data class McpHotReload(
    val ok: Boolean = false,
    val message: String = "",
    val added: List<String>? = null,
    val changed: List<String>? = null,
    val removed: List<String>? = null,
    val retried: List<String>? = null,
    val connected: List<String>? = null,
    val configured: List<String>? = null,
    val failed: List<String>? = null,
    @SerialName("tools_removed") val toolsRemoved: Int? = null,
    @SerialName("requires_restart") val requiresRestart: Boolean? = null,
)

@Serializable
data class McpPresetsPayload(
    val presets: List<McpPresetInfo> = emptyList(),
    @SerialName("installed_count") val installedCount: Int = 0,
    @SerialName("requires_restart") val requiresRestart: Boolean? = null,
    @SerialName("hot_reload") val hotReload: McpHotReload? = null,
    @SerialName("last_action") val lastAction: CapabilityAction? = null,
)

@Serializable
data class SlashCommand(
    val command: String,
    val title: String,
    val description: String,
    val icon: String,
    @SerialName("arg_hint") val argHint: String = "",
    val lifecycle: String,
    @SerialName("accepts_args") val acceptsArgs: Boolean = false,
)

@Serializable data class SlashCommandsPayload(val commands: List<SlashCommand> = emptyList())
