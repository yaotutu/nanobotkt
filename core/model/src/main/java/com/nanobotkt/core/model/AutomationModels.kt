package com.nanobotkt.core.model

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.JsonElement

/** Automation 调度、运行历史与会话关联模型。 按服务端 wire 字段原样建模，拆文件不改变序列化契约。 */
@Serializable
data class AutomationRunHistoryEntry(
    @SerialName("run_at_ms") val runAtMs: Long,
    val status: String,
    @SerialName("duration_ms") val durationMs: Long? = null,
    val error: String? = null,
)

@Serializable
data class AutomationSchedule(
    val kind: String,
    @SerialName("at_ms") val atMs: Long? = null,
    @SerialName("every_ms") val everyMs: Long? = null,
    val expr: String? = null,
    val tz: String? = null,
)

@Serializable
data class AutomationPayload(
    val message: String = "",
    val kind: String? = null,
    val command: String? = null,
)

@Serializable
data class AutomationState(
    @SerialName("next_run_at_ms") val nextRunAtMs: Long? = null,
    @SerialName("last_run_at_ms") val lastRunAtMs: Long? = null,
    @SerialName("last_status") val lastStatus: String? = null,
    @SerialName("last_error") val lastError: String? = null,
    val pending: Boolean? = null,
    @SerialName("run_history") val runHistory: List<AutomationRunHistoryEntry>? = null,
)

@Serializable
data class AutomationOrigin(
    @SerialName("session_key") val sessionKey: String? = null,
    val channel: String = "",
    @SerialName("chat_id") val chatId: String? = null,
    val title: String? = null,
    val preview: String? = null,
)

@Serializable data class AutomationTrigger(val id: String, val command: String)

@Serializable
data class SessionAutomationJob(
    val id: String,
    val name: String,
    val enabled: Boolean,
    val protected: Boolean? = null,
    @SerialName("delete_after_run") val deleteAfterRun: Boolean? = null,
    @SerialName("created_at_ms") val createdAtMs: Long? = null,
    @SerialName("updated_at_ms") val updatedAtMs: Long? = null,
    val kind: String? = null,
    val schedule: AutomationSchedule,
    val payload: AutomationPayload,
    val state: AutomationState = AutomationState(),
    val origin: AutomationOrigin? = null,
    val trigger: AutomationTrigger? = null,
)

@Serializable data class AutomationsPayload(val jobs: List<SessionAutomationJob> = emptyList())

@Serializable
data class AutomationUpdatePayload(
    val name: String? = null,
    val message: String? = null,
    val schedule: AutomationSchedule? = null,
)
