package com.nanobotkt.feature.chat

import com.nanobotkt.core.model.UiCliAppAttachment
import com.nanobotkt.core.model.UiMcpPresetAttachment
import com.nanobotkt.core.model.WorkspaceScope

/**
 * Composer 的纯 UI 状态与 Queue 快照。
 *
 * 这些类型不执行副作用；会话代次、最近命令持久化、Queue 边沿和 Voice 生命周期分别由对应
 * coordinator 管理，避免 ViewModel 文件再次混入状态结构定义。
 */
data class QueuedPrompt(
    val id: String,
    val text: String,
    val attachments: List<ComposerAttachment>,
    val quotedContext: String? = null,
    val cliApps: List<UiCliAppAttachment> = emptyList(),
    val mcpPresets: List<UiMcpPresetAttachment> = emptyList(),
    val workspaceScope: WorkspaceScope? = null,
    val sessionGuard: ChatSessionGuard? = null,
)

data class ComposerUiState(
    val text: String = "",
    val cursorPosition: Int = 0,
    val attachments: List<ComposerAttachment> = emptyList(),
    val encodingCount: Int = 0,
    val error: String? = null,
    val quotedContext: String? = null,
    val retryingMessageId: String? = null,
    val forkingMessageId: String? = null,
    val queuedPrompts: List<QueuedPrompt> = emptyList(),
    val sending: Boolean = false,
    val slashMenuDismissed: Boolean = false,
    val mentionMenuDismissed: Boolean = false,
    val recentCommands: List<String> = emptyList(),
    val voice: VoiceUiState = VoiceUiState(),
)

data class VoiceUiState(
    val isRecording: Boolean = false,
    val isTranscribing: Boolean = false,
    val durationMs: Long = 0,
    val waveform: List<Double> = emptyList(),
    val noInputHint: Boolean = false,
    val error: VoiceRecorderError? = null,
)
