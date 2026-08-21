package com.nanobotkt.feature.chat

/** Composer 的纯 UI 状态；网络与磁盘副作用分别由 Repository 和 Coordinator 负责。 */
data class ComposerUiState(
    /** 仅正文、光标、引用或附件变化时递增，用于草稿保存与 acceptance 条件清理。 */
    val revision: Long = 0L,
    /** 切换会话后正在从 Room 恢复草稿；用户仍可直接输入，较新 revision 会阻止旧快照回灌。 */
    val hydrating: Boolean = false,
    val text: String = "",
    val cursorPosition: Int = 0,
    val attachments: List<ComposerAttachment> = emptyList(),
    val encodingCount: Int = 0,
    val error: String? = null,
    val quotedContext: String? = null,
    val retryingMessageId: String? = null,
    val forkingMessageId: String? = null,
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
