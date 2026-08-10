@file:OptIn(kotlinx.serialization.ExperimentalSerializationApi::class)

package com.nanobotkt.core.model

import kotlinx.serialization.ExperimentalSerializationApi
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.JsonClassDiscriminator
import kotlinx.serialization.json.JsonElement

@Serializable
data class OutboundMedia(@SerialName("data_url") val dataUrl: String, val name: String? = null)

@Serializable
data class UiMediaAttachment(val kind: String, val url: String? = null, val name: String? = null)

@Serializable data class UiImage(val url: String? = null, val name: String? = null)

@Serializable
data class UiCliAppAttachment(
    val name: String,
    @SerialName("display_name") val displayName: String? = null,
    val category: String? = null,
    @SerialName("entry_point") val entryPoint: String? = null,
    @SerialName("logo_url") val logoUrl: String? = null,
    @SerialName("brand_color") val brandColor: String? = null,
)

@Serializable
data class UiMcpPresetAttachment(
    val name: String,
    @SerialName("display_name") val displayName: String? = null,
    val category: String? = null,
    val transport: String? = null,
    val status: String? = null,
    val configured: Boolean? = null,
    @SerialName("logo_url") val logoUrl: String? = null,
    @SerialName("brand_color") val brandColor: String? = null,
)

@Serializable data class UiMessageSource(val kind: String, val label: String? = null)

@Serializable
data class ToolProgressEvent(
    val version: Int? = null,
    val phase: String? = null,
    @SerialName("call_id") val callId: String? = null,
    val name: String? = null,
    val arguments: JsonElement? = null,
    val result: JsonElement? = null,
    val error: JsonElement? = null,
    val files: List<JsonElement>? = null,
    val embeds: List<JsonElement>? = null,
    val function: JsonElement? = null,
)

@Serializable
data class UiFileDiff(
    val format: String,
    val context: Int? = null,
    val truncated: Boolean? = null,
    val text: String? = null,
)

@Serializable
data class UiFileEdit(
    val version: Int? = null,
    @SerialName("call_id") val callId: String,
    val tool: String,
    val path: String,
    @SerialName("absolute_path") val absolutePath: String? = null,
    val phase: String? = null,
    val added: Int = 0,
    val deleted: Int = 0,
    val approximate: Boolean? = null,
    val status: String,
    val operation: String? = null,
    val binary: Boolean? = null,
    val error: String? = null,
    val pending: Boolean? = null,
    val diff: UiFileDiff? = null,
)

@Serializable data class AgentUiBlob(val kind: String, val data: JsonElement? = null)

/**
 * 服务端文件预览接口返回的内容。
 *
 * `content` 可能因为服务端大小限制而被截断，调用方必须同时检查 `truncated`，不能把截断内容误认为完整文件。
 */
@Serializable
data class FilePreviewPayload(
    val path: String,
    @SerialName("display_path") val displayPath: String,
    @SerialName("project_path") val projectPath: String,
    val language: String,
    val content: String,
    val size: Long,
    val truncated: Boolean,
)

@Serializable
data class UiMessage(
    val id: String,
    val role: String,
    val content: String,
    val kind: String? = null,
    val isStreaming: Boolean? = null,
    val createdAt: Long,
    val traces: List<String>? = null,
    val toolEvents: List<ToolProgressEvent>? = null,
    val fileEdits: List<UiFileEdit>? = null,
    val activitySegmentId: String? = null,
    val reasoning: String? = null,
    val reasoningStreaming: Boolean? = null,
    val latencyMs: Long? = null,
    val completedAt: Long? = null,
    val source: UiMessageSource? = null,
    val cliApps: List<UiCliAppAttachment>? = null,
    val mcpPresets: List<UiMcpPresetAttachment>? = null,
    val turnId: String? = null,
    val turnPhase: String? = null,
    val turnSeq: Int? = null,
    val media: List<UiMediaAttachment>? = null,
    val images: List<UiImage>? = null,
)

@Serializable
data class ThreadPage(
    @SerialName("before_cursor") val beforeCursor: String? = null,
    @SerialName("has_more_before") val hasMoreBefore: Boolean? = null,
    @SerialName("loaded_message_count") val loadedMessageCount: Int? = null,
    @SerialName("total_known_message_count") val totalKnownMessageCount: Int? = null,
    @SerialName("user_message_offset") val userMessageOffset: Int? = null,
)

@Serializable
data class WebUiThreadPayload(
    val schemaVersion: Int,
    val sessionKey: String? = null,
    val savedAt: String? = null,
    val messages: List<UiMessage> = emptyList(),
    @SerialName("fork_boundary_message_count") val forkBoundaryMessageCount: Int? = null,
    @SerialName("completed_turn_ids") val completedTurnIds: List<String>? = null,
    @SerialName("has_pending_tool_calls") val hasPendingToolCalls: Boolean? = null,
    @SerialName("active_turn_id") val activeTurnId: String? = null,
    val page: ThreadPage? = null,
    @SerialName("workspace_scope") val workspaceScope: WorkspaceScope? = null,
)

@Serializable
@JsonClassDiscriminator("event")
sealed interface InboundEvent {
    @Serializable
    @SerialName("ready")
    data class Ready(
        @SerialName("chat_id") val chatId: String,
        @SerialName("client_id") val clientId: String,
    ) : InboundEvent

    @Serializable
    @SerialName("attached")
    data class Attached(@SerialName("chat_id") val chatId: String) : InboundEvent

    @Serializable
    @SerialName("message_accepted")
    data class MessageAccepted(
        @SerialName("chat_id") val chatId: String,
        @SerialName("turn_id") val turnId: String,
    ) : InboundEvent

    @Serializable
    @SerialName("message")
    data class Message(
        @SerialName("chat_id") val chatId: String,
        val text: String,
        val kind: String? = null,
        val media: List<String>? = null,
        @SerialName("media_urls") val mediaUrls: List<UiImage>? = null,
        @SerialName("tool_events") val toolEvents: List<ToolProgressEvent>? = null,
        @SerialName("latency_ms") val latencyMs: Long? = null,
        @SerialName("turn_id") val turnId: String? = null,
        @SerialName("turn_phase") val turnPhase: String? = null,
        @SerialName("turn_seq") val turnSeq: Int? = null,
        @SerialName("reply_to") val replyTo: String? = null,
        val source: UiMessageSource? = null,
        @SerialName("agent_ui") val agentUi: AgentUiBlob? = null,
    ) : InboundEvent

    @Serializable
    @SerialName("file_edit")
    data class FileEdit(
        @SerialName("chat_id") val chatId: String,
        val edits: List<UiFileEdit>,
        @SerialName("turn_id") val turnId: String? = null,
        @SerialName("turn_phase") val turnPhase: String? = null,
        @SerialName("turn_seq") val turnSeq: Int? = null,
    ) : InboundEvent

    @Serializable
    @SerialName("delta")
    data class Delta(
        @SerialName("chat_id") val chatId: String,
        val text: String,
        @SerialName("stream_id") val streamId: String? = null,
        @SerialName("turn_id") val turnId: String? = null,
        @SerialName("turn_phase") val turnPhase: String? = null,
        @SerialName("turn_seq") val turnSeq: Int? = null,
    ) : InboundEvent

    @Serializable
    @SerialName("reasoning_delta")
    data class ReasoningDelta(
        @SerialName("chat_id") val chatId: String,
        val text: String,
        @SerialName("stream_id") val streamId: String? = null,
        @SerialName("turn_id") val turnId: String? = null,
        @SerialName("turn_phase") val turnPhase: String? = null,
        @SerialName("turn_seq") val turnSeq: Int? = null,
    ) : InboundEvent

    @Serializable
    @SerialName("reasoning_end")
    data class ReasoningEnd(
        @SerialName("chat_id") val chatId: String,
        @SerialName("stream_id") val streamId: String? = null,
        @SerialName("turn_id") val turnId: String? = null,
        @SerialName("turn_phase") val turnPhase: String? = null,
        @SerialName("turn_seq") val turnSeq: Int? = null,
    ) : InboundEvent

    @Serializable
    @SerialName("stream_end")
    data class StreamEnd(
        @SerialName("chat_id") val chatId: String,
        @SerialName("stream_id") val streamId: String? = null,
        val text: String? = null,
        val resuming: Boolean? = null,
        @SerialName("merge_next") val mergeNext: Boolean? = null,
        @SerialName("turn_id") val turnId: String? = null,
        @SerialName("turn_phase") val turnPhase: String? = null,
        @SerialName("turn_seq") val turnSeq: Int? = null,
    ) : InboundEvent

    @Serializable
    @SerialName("turn_end")
    data class TurnEnd(
        @SerialName("chat_id") val chatId: String,
        @SerialName("latency_ms") val latencyMs: Long? = null,
        @SerialName("turn_id") val turnId: String? = null,
        @SerialName("turn_phase") val turnPhase: String? = null,
        @SerialName("turn_seq") val turnSeq: Int? = null,
        @SerialName("goal_state") val goalState: GoalStatePayload? = null,
    ) : InboundEvent

    @Serializable
    @SerialName("goal_status")
    data class GoalStatus(
        @SerialName("chat_id") val chatId: String,
        val status: String,
        @SerialName("started_at") val startedAt: Long? = null,
        @SerialName("turn_id") val turnId: String? = null,
    ) : InboundEvent

    @Serializable
    @SerialName("session_updated")
    data class SessionUpdated(
        @SerialName("chat_id") val chatId: String,
        val scope: String? = null,
        @SerialName("workspace_scope") val workspaceScope: WorkspaceScope? = null,
    ) : InboundEvent

    @Serializable
    @SerialName("transcription_result")
    data class TranscriptionResult(
        @SerialName("request_id") val requestId: String,
        val text: String,
    ) : InboundEvent

    @Serializable
    @SerialName("transcription_error")
    data class TranscriptionError(
        @SerialName("request_id") val requestId: String? = null,
        val detail: String? = null,
        val provider: String? = null,
    ) : InboundEvent

    @Serializable
    @SerialName("runtime_model_updated")
    data class RuntimeModelUpdated(
        @SerialName("model_name") val modelName: String,
        @SerialName("model_preset") val modelPreset: String? = null,
    ) : InboundEvent

    @Serializable
    @SerialName("turn_model_updated")
    data class TurnModelUpdated(
        @SerialName("chat_id") val chatId: String,
        @SerialName("model_name") val modelName: String,
    ) : InboundEvent

    @Serializable
    @SerialName("goal_state")
    data class GoalState(
        @SerialName("chat_id") val chatId: String,
        @SerialName("goal_state") val goalState: GoalStatePayload,
    ) : InboundEvent

    @Serializable
    @SerialName("error")
    data class Error(
        @SerialName("chat_id") val chatId: String? = null,
        val detail: String? = null,
        val reason: String? = null,
        @SerialName("turn_id") val turnId: String? = null,
    ) : InboundEvent
}

@Serializable
@JsonClassDiscriminator("type")
sealed interface OutboundFrame {
    @Serializable
    @SerialName("new_chat")
    data class NewChat(@SerialName("workspace_scope") val workspaceScope: WorkspaceScope? = null) :
        OutboundFrame

    @Serializable
    @SerialName("fork_chat")
    data class ForkChat(
        @SerialName("source_chat_id") val sourceChatId: String,
        @SerialName("before_user_index") val beforeUserIndex: Int,
        val title: String? = null,
    ) : OutboundFrame

    @Serializable
    @SerialName("attach")
    data class Attach(@SerialName("chat_id") val chatId: String) : OutboundFrame

    @Serializable
    @SerialName("set_workspace_scope")
    data class SetWorkspaceScope(
        @SerialName("chat_id") val chatId: String,
        @SerialName("workspace_scope") val workspaceScope: WorkspaceScope,
    ) : OutboundFrame

    @Serializable
    @SerialName("message")
    data class Message(
        @SerialName("chat_id") val chatId: String,
        val content: String,
        val media: List<OutboundMedia>? = null,
        @SerialName("cli_apps") val cliApps: List<UiCliAppAttachment>? = null,
        @SerialName("mcp_presets") val mcpPresets: List<UiMcpPresetAttachment>? = null,
        @SerialName("quoted_context") val quotedContext: String? = null,
        @SerialName("workspace_scope") val workspaceScope: WorkspaceScope? = null,
        @SerialName("turn_id") val turnId: String,
        val webui: Boolean,
    ) : OutboundFrame

    @Serializable
    @SerialName("transcribe_audio")
    data class TranscribeAudio(
        @SerialName("request_id") val requestId: String,
        @SerialName("data_url") val dataUrl: String,
        @SerialName("duration_ms") val durationMs: Long? = null,
    ) : OutboundFrame
}
