package com.nanobotkt.core.model

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.JsonElement

/** Channel 配置、配对与运行能力模型。 按服务端 wire 字段原样建模，拆文件不改变序列化契约。 */
@Serializable
data class ChannelValidationCheck(
    val id: String,
    val label: String,
    val status: String,
    val message: String? = null,
    @SerialName("action_url") val actionUrl: String? = null,
)

@Serializable
data class ChannelIdentity(
    val name: String? = null,
    val workspace: String? = null,
    val account: String? = null,
    @SerialName("avatar_url") val avatarUrl: String? = null,
)

@Serializable
data class ChannelValidationPayload(
    val name: String,
    val status: String,
    val checks: List<ChannelValidationCheck> = emptyList(),
    val identity: ChannelIdentity? = null,
    @SerialName("missing_fields") val missingFields: List<String> = emptyList(),
    @SerialName("can_enable") val canEnable: Boolean = false,
    @SerialName("requires_restart") val requiresRestart: Boolean = false,
    @SerialName("checked_at") val checkedAt: String? = null,
    val message: String? = null,
)

@Serializable
data class PairingRequestInfo(
    val code: String,
    val channel: String,
    @SerialName("sender_id") val senderId: String,
    @SerialName("created_at_ms") val createdAtMs: Long? = null,
    @SerialName("expires_at_ms") val expiresAtMs: Long? = null,
    @SerialName("expires_in_seconds") val expiresInSeconds: Long? = null,
)

@Serializable
data class PairingLastAction(
    val ok: Boolean,
    val action: String,
    val message: String,
    val code: String? = null,
    val channel: String? = null,
    @SerialName("sender_id") val senderId: String? = null,
)

@Serializable
data class PairingPayload(
    val requests: List<PairingRequestInfo> = emptyList(),
    @SerialName("last_action") val lastAction: PairingLastAction? = null,
)

@Serializable
data class ChannelConnectPayload(
    @SerialName("session_id") val sessionId: String,
    @SerialName("instance_id") val instanceId: String? = null,
    val status: String,
    val message: String? = null,
    @SerialName("qr_url") val qrUrl: String? = null,
    val domain: String? = null,
    @SerialName("interval_ms") val intervalMs: Long? = null,
    @SerialName("expires_at_ms") val expiresAtMs: Long? = null,
    @SerialName("app_id") val appId: String? = null,
    val account: String? = null,
    @SerialName("nanobot_features") val nanobotFeatures: NanobotFeaturesPayload? = null,
)

@Serializable
data class ChannelConfigurePayload(
    val name: String,
    val saved: Boolean,
    @SerialName("saved_keys") val savedKeys: List<String>? = null,
    @SerialName("nanobot_features") val nanobotFeatures: NanobotFeaturesPayload? = null,
)

@Serializable
data class ChannelSetupContractField(
    val key: String,
    val field: String,
    val kind: String,
    val choices: List<String> = emptyList(),
    val required: Boolean = false,
    @SerialName("default_value") val defaultValue: String? = null,
)

@Serializable
data class ChannelSetupContract(
    val fields: List<ChannelSetupContractField> = emptyList(),
    @SerialName("official_url") val officialUrl: String? = null,
)

@Serializable
data class NanobotChannelInstanceInfo(
    val id: String,
    val name: String,
    @SerialName("display_name") val displayName: String? = null,
    @SerialName("avatar_url") val avatarUrl: String? = null,
    val enabled: Boolean = false,
    val running: Boolean? = null,
    @SerialName("runtime_status") val runtimeStatus: String? = null,
    @SerialName("runtime_error") val runtimeError: String? = null,
    val configured: Boolean = false,
    @SerialName("config_values") val configValues: Map<String, String> = emptyMap(),
    @SerialName("configured_fields") val configuredFields: List<String> = emptyList(),
)

@Serializable
data class NanobotFeatureInfo(
    val name: String,
    @SerialName("display_name") val displayName: String = name,
    val capabilities: List<String>? = null,
    @SerialName("settings_visible") val settingsVisible: Boolean? = null,
    @SerialName("connect_supported") val connectSupported: Boolean? = null,
    val webui: String? = null,
    val type: String = "feature",
    val enabled: Boolean = false,
    val running: Boolean? = null,
    @SerialName("runtime_status") val runtimeStatus: String? = null,
    @SerialName("runtime_error") val runtimeError: String? = null,
    val error: String? = null,
    val configured: Boolean? = null,
    @SerialName("config_values") val configValues: Map<String, String>? = null,
    @SerialName("configured_fields") val configuredFields: List<String>? = null,
    val setup: ChannelSetupContract? = null,
    val instances: List<NanobotChannelInstanceInfo>? = null,
    val installed: Boolean = false,
    val ready: Boolean = false,
    val status: String = "",
    @SerialName("install_supported") val installSupported: Boolean = false,
    @SerialName("requires_restart") val requiresRestart: Boolean = false,
)

@Serializable
data class NanobotFeatureAction(val ok: Boolean, val message: String, val enabled: Boolean? = null)

@Serializable
data class NanobotFeaturesPayload(
    val features: List<NanobotFeatureInfo> = emptyList(),
    @SerialName("enabled_count") val enabledCount: Int = 0,
    @SerialName("requires_restart") val requiresRestart: Boolean? = null,
    @SerialName("last_action") val lastAction: NanobotFeatureAction? = null,
)
