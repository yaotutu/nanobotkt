package com.nanobotkt.core.model

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

@Serializable
enum class ConnectionStatus { @SerialName("idle") IDLE, @SerialName("connecting") CONNECTING, @SerialName("open") OPEN, @SerialName("reconnecting") RECONNECTING, @SerialName("closed") CLOSED, @SerialName("error") ERROR }

@Serializable
enum class RuntimeSurface { @SerialName("browser") BROWSER, @SerialName("native") NATIVE }

@Serializable
data class RuntimeCapabilities(
    @SerialName("can_restart_engine") val canRestartEngine: Boolean = false,
    @SerialName("can_pick_folder") val canPickFolder: Boolean = false,
    @SerialName("can_open_logs") val canOpenLogs: Boolean = false,
    @SerialName("can_export_diagnostics") val canExportDiagnostics: Boolean = false,
)

@Serializable
data class WebUiIngressLimits(
    val transport: TransportLimits,
    val message: MessageLimits,
    val attachments: AttachmentLimits,
) {
    @Serializable data class TransportLimits(@SerialName("max_frame_bytes") val maxFrameBytes: Int, @SerialName("envelope_reserve_bytes") val envelopeReserveBytes: Int)
    @Serializable data class MessageLimits(@SerialName("max_text_bytes") val maxTextBytes: Int)
    @Serializable data class AttachmentLimits(@SerialName("max_count") val maxCount: Int, @SerialName("max_file_bytes") val maxFileBytes: Long, @SerialName("max_total_bytes") val maxTotalBytes: Long)
}

@Serializable
data class GoalStatePayload(val active: Boolean, @SerialName("ui_summary") val uiSummary: String? = null, val objective: String? = null)

@Serializable
data class BootstrapResponse(
    val token: String,
    @SerialName("api_token") val apiToken: String,
    @SerialName("ws_path") val wsPath: String,
    @SerialName("ws_url") val wsUrl: String? = null,
    @SerialName("expires_in") val expiresIn: Long,
    val limits: WebUiIngressLimits? = null,
    @SerialName("model_name") val modelName: String? = null,
    @SerialName("runtime_surface") val runtimeSurface: RuntimeSurface? = null,
    @SerialName("runtime_capabilities") val runtimeCapabilities: RuntimeCapabilities? = null,
)

interface IngressLimitsProvider {
    fun currentIngressLimits(): WebUiIngressLimits?
}

/**
 * 可以安全暴露给业务层的 Gateway 运行时信息。
 *
 * 该快照刻意不包含 Bootstrap Secret、API Token、WebSocket Token 或带 Token 的 URL，
 * 防止短期凭据从鉴权系统泄漏到 Chat、Settings 等业务模块。
 */
data class GatewayRuntimeSnapshot(
    val limits: WebUiIngressLimits? = null,
    val modelName: String? = null,
    val runtimeSurface: RuntimeSurface? = null,
    val runtimeCapabilities: RuntimeCapabilities? = null,
)

interface GatewayRuntimeSnapshotProvider {
    fun currentRuntimeSnapshot(): GatewayRuntimeSnapshot?
}
