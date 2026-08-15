package com.nanobotkt.feature.chat

import android.net.Uri
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.nanobotkt.core.model.SessionAutomationJob
import com.nanobotkt.core.model.SlashCommand
import com.nanobotkt.core.model.UiCliAppAttachment
import com.nanobotkt.core.model.UiMcpPresetAttachment
import com.nanobotkt.core.model.WorkspaceScope
import com.nanobotkt.core.persistence.ComposerRecentsStore
import dagger.hilt.android.lifecycle.HiltViewModel
import javax.inject.Inject
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

@HiltViewModel
class ChatViewModel
@Inject
constructor(
    private val repository: ChatRepository,
    private val attachmentEncoder: AttachmentEncoding,
    private val voiceRecorder: VoiceRecorder,
    private val composerRecentsStore: ComposerRecentsStore,
) : ViewModel() {
    val state: StateFlow<ChatUiState> = repository.state
    private val mutableComposer = MutableStateFlow(ComposerUiState())
    val composer: StateFlow<ComposerUiState> = mutableComposer.asStateFlow()

    private var voiceTimer: Job? = null
    private var recordingAnalysis = RecordingAnalysis()
    private var openedSessionKey: String? = null
    private var composerEpoch: Long = 0
    private var lastTurnActive = state.value.activeTurnId != null
    private var skipNextQueueFlush = false
    // 自动发送 Queue 头项时，acceptance 协程可能仍处于 sending=true，但服务端对应 turn 已经
    // 极快地开始并结束。此时 turn-end 不能直接发送下一项，也不能简单丢弃这次触发；先记录
    // “发送结束后继续 flush”，待 acceptance 收敛后再串行处理，避免剩余 Queue 永久卡住。
    private var queueFlushDeferredUntilSendCompletes = false
    private var queueCounter = 0L
    private val composerRecents = mutableListOf<String>()
    private val composerRecentsSaveMutex = Mutex()
    private var composerRecentsRevision = 0L

    init {
        val hydrationRevision = composerRecentsRevision
        viewModelScope.launch {
            val persisted = composerRecentsStore.load()
            if (composerRecentsRevision == hydrationRevision) {
                composerRecents.clear()
                composerRecents.addAll(persisted)
                mutableComposer.value =
                    mutableComposer.value.copy(recentCommands = composerRecents.toList())
            }
        }
        viewModelScope.launch {
            state.collect { chatState ->
                val turnActive = chatState.activeTurnId != null
                val wasTurnActive = lastTurnActive
                lastTurnActive = turnActive
                if (wasTurnActive && !turnActive) {
                    if (skipNextQueueFlush) {
                        skipNextQueueFlush = false
                        queueFlushDeferredUntilSendCompletes = false
                    } else if (mutableComposer.value.sending) {
                        queueFlushDeferredUntilSendCompletes =
                            mutableComposer.value.queuedPrompts.isNotEmpty()
                    } else {
                        flushNextQueuedPrompt()
                    }
                }
            }
        }
    }

    fun open(
        sessionKey: String,
        chatId: String,
        workspaceScope: WorkspaceScope? = null,
        modelPreset: String? = null,
    ) {
        // 预览内容属于当前会话，重新打开会话时先关闭，避免旧文件内容短暂残留。
        repository.clearFilePreview()
        if (openedSessionKey != sessionKey) {
            composerEpoch += 1
            voiceTimer?.cancel()
            voiceTimer = null
            voiceRecorder.cancel()
            recordingAnalysis = RecordingAnalysis()
            skipNextQueueFlush = false
            queueFlushDeferredUntilSendCompletes = false
            mutableComposer.value = ComposerUiState(recentCommands = composerRecents.toList())
            openedSessionKey = sessionKey
        }
        repository.openSession(sessionKey, chatId, workspaceScope, modelPreset)
    }

    fun startNewTopic() {
        repository.clearFilePreview()
        composerEpoch += 1
        voiceTimer?.cancel()
        voiceTimer = null
        voiceRecorder.cancel()
        recordingAnalysis = RecordingAnalysis()
        openedSessionKey = null
        skipNextQueueFlush = false
        queueFlushDeferredUntilSendCompletes = false
        mutableComposer.value = ComposerUiState(recentCommands = composerRecents.toList())
        repository.startNewTopic()
    }

    fun setWorkspaceScope(workspaceScope: WorkspaceScope) {
        if (state.value.activeTurnId == null) repository.setWorkspaceScope(workspaceScope)
    }

    fun newChat(onCreated: (String) -> Unit = {}) =
        viewModelScope.launch {
            val requestEpoch = composerEpoch
            runCatching { repository.newChat(state.value.workspaceScope) }
                .onSuccess { sessionKey ->
                    if (requestEpoch == composerEpoch) onCreated(sessionKey)
                }
                .onFailure {
                    if (requestEpoch == composerEpoch) {
                        mutableComposer.value =
                            mutableComposer.value.copy(error = it.message ?: "new_chat_failed")
                    }
                }
        }

    fun changeModelPreset(name: String) =
        viewModelScope.launch { runCatching { repository.changeModelPreset(name) } }

    fun refresh() = repository.refresh()

    fun loadOlder() = repository.loadOlder()

    fun setQuotedContext(content: String) {
        val normalized = normalizeQuotedContext(content)
        mutableComposer.value =
            mutableComposer.value.copy(
                quotedContext = normalized.takeIf(String::isNotEmpty),
                error = null,
            )
    }

    fun clearQuotedContext() {
        mutableComposer.value = mutableComposer.value.copy(quotedContext = null)
    }

    fun updateText(text: String, cursorPosition: Int = text.length) {
        mutableComposer.value =
            mutableComposer.value.copy(
                text = text,
                cursorPosition = cursorPosition.coerceIn(0, text.length),
                slashMenuDismissed = false,
                mentionMenuDismissed = false,
                error = null,
            )
    }

    fun selectSlashCommand(command: SlashCommand) {
        val turnActive = state.value.activeTurnId != null
        val stopActiveTurn = command.command == "/stop" || command.lifecycle == "stop_active_turn"
        if (turnActive && stopActiveTurn) {
            mutableComposer.value =
                mutableComposer.value.copy(
                    text = "",
                    cursorPosition = 0,
                    attachments = emptyList(),
                    quotedContext = null,
                    slashMenuDismissed = true,
                    mentionMenuDismissed = false,
                    error = null,
                )
            stop()
            return
        }

        recordRecentCommand(command.command)
        val nextText = if (command.acceptsArgs) "${command.command} " else command.command
        mutableComposer.value =
            mutableComposer.value.copy(
                text = nextText,
                cursorPosition = nextText.length,
                slashMenuDismissed = true,
                mentionMenuDismissed = false,
                recentCommands = composerRecents.toList(),
                error = null,
            )
    }

    fun selectSkillMention(candidate: SkillMentionCandidate) {
        val current = mutableComposer.value
        val query = skillMentionQuery(current.text, current.cursorPosition) ?: return
        val next = insertSkillMention(current.text, query, candidate)
        recordRecentCommand(candidate.command)
        mutableComposer.value =
            current.copy(
                text = next.value,
                cursorPosition = next.cursor,
                slashMenuDismissed = true,
                mentionMenuDismissed = false,
                recentCommands = composerRecents.toList(),
                error = null,
            )
    }

    fun selectCapabilityMention(candidate: CapabilityMentionCandidate) {
        val current = mutableComposer.value
        val query = capabilityMentionQuery(current.text, current.cursorPosition) ?: return
        val next = insertCapabilityMention(current.text, query, candidate)
        mutableComposer.value =
            current.copy(
                text = next.value,
                cursorPosition = next.cursor,
                slashMenuDismissed = false,
                mentionMenuDismissed = true,
                error = null,
            )
    }

    fun removeAttachment(index: Int) {
        mutableComposer.value =
            mutableComposer.value.copy(
                attachments =
                    mutableComposer.value.attachments.filterIndexed { itemIndex, _ ->
                        itemIndex != index
                    }
            )
    }

    fun addAttachments(uris: List<Uri>) {
        if (uris.isEmpty()) return

        // 在启动协程前捕获 epoch，避免用户切换会话后协程才开始执行时，
        // 错把旧会话的附件任务绑定到新会话的 composer。
        val requestEpoch = composerEpoch
        val limits = ingressLimits(state.value.limits)
        val current = mutableComposer.value
        // encodingCount 代表已经占用的附件名额；并发选择附件时必须把它计入
        // available，否则多批任务可能共同超过服务端的附件数量上限。
        val available =
            (limits.maxCount - current.attachments.size - current.encodingCount).coerceAtLeast(0)
        if (available == 0) {
            mutableComposer.value = current.copy(error = "too_many_attachments")
            return
        }

        val selected = uris.take(available)
        mutableComposer.value =
            current.copy(
                encodingCount = current.encodingCount + selected.size,
                error = if (uris.size > available) "too_many_attachments" else null,
            )

        viewModelScope.launch {
            selected.forEach { uri ->
                try {
                    val attachment = attachmentEncoder.encode(uri, limits.maxFileBytes)
                    if (requestEpoch != composerEpoch) return@launch
                    val error =
                        validateEncodedAttachment(
                            current = mutableComposer.value.attachments,
                            candidate = attachment,
                            limits = limits,
                        )
                    if (error == null) {
                        mutableComposer.value =
                            mutableComposer.value.copy(
                                attachments = mutableComposer.value.attachments + attachment
                            )
                    } else {
                        mutableComposer.value = mutableComposer.value.copy(error = error)
                    }
                } catch (error: CancellationException) {
                    // ViewModel 被销毁或任务被取消时必须保留协程取消语义，不能把取消
                    // 当作普通附件错误吞掉，否则上层生命周期无法正确结束编码任务。
                    throw error
                } catch (error: Throwable) {
                    if (requestEpoch == composerEpoch) {
                        mutableComposer.value =
                            mutableComposer.value.copy(error = error.message ?: "io")
                    }
                } finally {
                    if (requestEpoch == composerEpoch) {
                        mutableComposer.value =
                            mutableComposer.value.copy(
                                encodingCount =
                                    (mutableComposer.value.encodingCount - 1).coerceAtLeast(0)
                            )
                    }
                }
            }
        }
    }

    fun send() {
        val current = mutableComposer.value
        if (
            current.sending ||
                current.encodingCount > 0 ||
                (current.text.isBlank() &&
                    current.attachments.isEmpty() &&
                    current.quotedContext.isNullOrBlank())
        )
            return

        val capabilityPayloads =
            activeCapabilityMentionPayloads(
                value = current.text,
                cliApps = state.value.cliApps,
                mcpPresets = state.value.mcpPresets,
            )
        val prompt =
            QueuedPrompt(
                id = nextQueuedPromptId(),
                text = current.text,
                attachments = current.attachments,
                quotedContext = current.quotedContext,
                cliApps = capabilityPayloads.cliApps,
                mcpPresets = capabilityPayloads.mcpPresets,
                workspaceScope = state.value.workspaceScope,
                sessionGuard =
                    ChatSessionGuard(
                        sessionKey = state.value.sessionKey,
                        chatId = state.value.chatId,
                    ),
            )
        val turnActive = state.value.activeTurnId != null
        val hasPlainTextCommandPayload =
            current.attachments.isEmpty() &&
                capabilityPayloads.cliApps.isEmpty() &&
                capabilityPayloads.mcpPresets.isEmpty()
        val lifecycle =
            if (hasPlainTextCommandPayload) {
                slashCommandLifecycle(current.text.trim(), state.value.slashCommands)
            } else {
                null
            }
        if (turnActive && lifecycle == ResolvedSlashCommandLifecycle.STOP_ACTIVE_TURN) {
            mutableComposer.value =
                current.copy(
                    text = "",
                    cursorPosition = 0,
                    attachments = emptyList(),
                    quotedContext = null,
                    slashMenuDismissed = true,
                    error = null,
                )
            stop()
            return
        }
        if (turnActive && !current.text.trimStart().startsWith('/')) {
            mutableComposer.value =
                current.copy(
                    text = "",
                    cursorPosition = 0,
                    attachments = emptyList(),
                    quotedContext = null,
                    queuedPrompts = current.queuedPrompts + prompt,
                    error = null,
                )
            return
        }

        skipNextQueueFlush = false
        queueFlushDeferredUntilSendCompletes = false
        mutableComposer.value =
            current.copy(
                text = "",
                cursorPosition = 0,
                quotedContext = null,
                queuedPrompts = emptyList(),
                sending = true,
                error = null,
            )
        submitPrompt(
            prompt = prompt,
            options =
                ChatSendOptions(
                    sideChannel = lifecycle.isSideChannel(),
                    cliApps = prompt.cliApps,
                    mcpPresets = prompt.mcpPresets,
                    sessionGuard = prompt.sessionGuard,
                ),
            restoreDraftOnFailure = true,
            requeueOnFailure = false,
        )
    }

    fun removeQueuedPrompt(id: String) {
        mutableComposer.value =
            mutableComposer.value.copy(
                queuedPrompts = mutableComposer.value.queuedPrompts.filterNot { it.id == id }
            )
    }

    fun retry(messageId: String) {
        if (state.value.activeTurnId != null || mutableComposer.value.retryingMessageId != null)
            return
        mutableComposer.value =
            mutableComposer.value.copy(retryingMessageId = messageId, error = null)
        viewModelScope.launch {
            val requestEpoch = composerEpoch
            runCatching { repository.retry(messageId) }
                .onSuccess {
                    // 重试再次被服务端拒绝时 Repository 会保留新的 FAILED 气泡；Composer 不再
                    // 额外显示同一错误，避免用户同时看到气泡状态和底部错误两份反馈。
                    if (requestEpoch == composerEpoch) {
                        mutableComposer.value = mutableComposer.value.copy(error = null)
                    }
                }
                .onFailure { error ->
                    if (requestEpoch == composerEpoch) {
                        mutableComposer.value =
                            mutableComposer.value.copy(
                                error = error.message ?: "message_send_failed"
                            )
                    }
                }
            if (requestEpoch == composerEpoch) {
                mutableComposer.value = mutableComposer.value.copy(retryingMessageId = null)
            }
        }
    }

    fun fork(messageId: String, beforeUserIndex: Int, title: String, onCreated: (String) -> Unit) {
        if (mutableComposer.value.forkingMessageId != null) return
        mutableComposer.value =
            mutableComposer.value.copy(forkingMessageId = messageId, error = null)
        // 在启动 coroutine 之前捕获 epoch，避免切换会话发生在调度前时误把旧结果
        // 应用到新会话的 Composer 状态。
        val requestEpoch = composerEpoch
        viewModelScope.launch {
            runCatching { repository.fork(beforeUserIndex, title) }
                .onSuccess { sessionKey ->
                    if (requestEpoch == composerEpoch) {
                        mutableComposer.value = ComposerUiState()
                        onCreated(sessionKey)
                    }
                }
                .onFailure { error ->
                    if (requestEpoch == composerEpoch) {
                        mutableComposer.value =
                            mutableComposer.value.copy(error = error.message ?: "fork_failed")
                    }
                }
            if (requestEpoch == composerEpoch) {
                mutableComposer.value = mutableComposer.value.copy(forkingMessageId = null)
            }
        }
    }

    fun startVoiceRecording(permissionGranted: Boolean) {
        val voice = mutableComposer.value.voice
        if (voice.isRecording || voice.isTranscribing) return
        if (!permissionGranted) {
            updateVoice { it.copy(error = VoiceRecorderError.PERMISSION) }
            return
        }

        recordingAnalysis = RecordingAnalysis()
        runCatching { voiceRecorder.start(DEFAULT_MAX_DURATION_SEC, DEFAULT_MAX_UPLOAD_MB) }
            .onFailure { error ->
                updateVoice { it.copy(error = voiceErrorFromUnknown(error)) }
                return
            }
        updateVoice { VoiceUiState(isRecording = true, waveform = waveformFromMetering(null, 0)) }
        voiceTimer?.cancel()
        voiceTimer =
            viewModelScope.launch {
                while (isActive && mutableComposer.value.voice.isRecording) {
                    delay(80)
                    val duration = voiceRecorder.durationMs()
                    val metering = voiceRecorder.meteringDb()
                    if (metering != null) recordingAnalysis.observe(metering)
                    val noInputHint =
                        duration >= NO_INPUT_HINT_MS && recordingAnalysis.shouldShowNoInputHint()
                    updateVoice {
                        it.copy(
                            durationMs = duration,
                            waveform = waveformFromMetering(metering, duration),
                            noInputHint = noInputHint,
                        )
                    }
                    if (
                        voiceRecorder.maxReached() ||
                            duration >= boundedVoiceDurationSec(DEFAULT_MAX_DURATION_SEC) * 1_000L
                    ) {
                        launch { stopVoiceRecording(maxReached = true) }
                        break
                    }
                }
            }
    }

    fun stopVoiceRecording(cancelled: Boolean = false, maxReached: Boolean = false) {
        if (!mutableComposer.value.voice.isRecording) return
        voiceTimer?.cancel()
        voiceTimer = null
        if (cancelled) {
            voiceRecorder.cancel()
            updateVoice { VoiceUiState() }
            return
        }

        updateVoice { it.copy(isRecording = false, isTranscribing = true, noInputHint = false) }
        viewModelScope.launch {
            val requestEpoch = composerEpoch
            try {
                val recording = voiceRecorder.stopAndEncode()
                if (requestEpoch != composerEpoch) return@launch
                val validationError =
                    recordingStopError(
                        durationMs = recording.durationMs,
                        maxReached = maxReached || recording.maxReached,
                        analysis = recordingAnalysis,
                    )
                if (validationError != null) {
                    updateVoice { VoiceUiState(error = validationError) }
                    return@launch
                }
                val transcript =
                    repository.transcribeAudio(recording.dataUrl, recording.durationMs).trim()
                if (requestEpoch != composerEpoch) return@launch
                if (transcript.isEmpty()) error("missing_audio")
                val currentText = mutableComposer.value.text
                val nextText =
                    listOf(currentText.trimEnd(), transcript)
                        .filter(String::isNotBlank)
                        .joinToString(" ")
                mutableComposer.value =
                    mutableComposer.value.copy(
                        text = nextText,
                        cursorPosition = nextText.length,
                        voice = VoiceUiState(),
                        error = null,
                    )
            } catch (error: Throwable) {
                if (requestEpoch == composerEpoch) {
                    updateVoice { VoiceUiState(error = voiceErrorFromUnknown(error)) }
                }
            }
        }
    }

    fun stop() {
        val current = mutableComposer.value
        skipNextQueueFlush = current.queuedPrompts.isNotEmpty()
        queueFlushDeferredUntilSendCompletes = false
        mutableComposer.value = current.copy(queuedPrompts = emptyList())
        repository.stop()
    }

    fun clearError() = repository.clearError()

    /** 请求当前会话的文件预览；仓储层负责校验会话并隔离迟到响应。 */
    fun previewFile(path: String) = repository.loadFilePreview(path)

    fun closeFilePreview() = repository.clearFilePreview()

    /** Compose 只获得无凭据的绝对媒体地址，不直接依赖 AuthContext 或网络客户端。 */
    fun resolveMediaUrl(url: String): String = repository.resolveMediaUrl(url)

    val loadSessionAutomations: suspend (String) -> List<SessionAutomationJob> =
        repository::loadSessionAutomations

    override fun onCleared() {
        voiceTimer?.cancel()
        voiceRecorder.cancel()
        super.onCleared()
    }

    private fun flushNextQueuedPrompt() {
        val current = mutableComposer.value
        if (current.sending || state.value.activeTurnId != null) return
        val next = current.queuedPrompts.firstOrNull() ?: return
        queueFlushDeferredUntilSendCompletes = false
        mutableComposer.value =
            current.copy(
                queuedPrompts = current.queuedPrompts.drop(1),
                sending = true,
                error = null,
            )
        submitPrompt(
            prompt = next,
            options = ChatSendOptions(retainFailureInTimeline = false),
            restoreDraftOnFailure = false,
            requeueOnFailure = true,
        )
    }

    private fun submitPrompt(
        prompt: QueuedPrompt,
        options: ChatSendOptions = ChatSendOptions(),
        restoreDraftOnFailure: Boolean,
        requeueOnFailure: Boolean,
    ) {
        val requestEpoch = composerEpoch
        viewModelScope.launch {
            runCatching {
                    repository.send(
                        text = prompt.text,
                        media = prompt.attachments.map { it.outbound },
                        quotedContext = prompt.quotedContext,
                        options =
                            options.copy(
                                cliApps = prompt.cliApps,
                                mcpPresets = prompt.mcpPresets,
                                capabilityPayloadsResolved = true,
                                workspaceScope = prompt.workspaceScope,
                            ),
                    )
                }
                .onSuccess { outcome ->
                    if (requestEpoch == composerEpoch) {
                        mutableComposer.value =
                            mutableComposer.value.copy(
                                attachments =
                                    if (restoreDraftOnFailure) emptyList()
                                    else mutableComposer.value.attachments,
                                sending = false,
                                // FailedRetained 已经通过时间轴气泡提供可操作反馈；底部不再重复
                                // 展示全局错误。Accepted 同样清除上一轮 Composer 错误。
                                error = null,
                            )
                        // 如果 turn-end 发生在 acceptance 返回之前，状态收集器已经把本次
                        // flush 延后。必须在 sending=false 之后补做一次，否则不会再出现新的
                        // active→idle 边沿，剩余排队消息会一直停在顶部和时间轴中。
                        if (queueFlushDeferredUntilSendCompletes) {
                            queueFlushDeferredUntilSendCompletes = false
                            flushNextQueuedPrompt()
                        }
                    }
                }
                .onFailure { error ->
                    if (requestEpoch == composerEpoch) {
                        // Queue acceptance 失败会把当前 prompt 插回队首并等待用户处理；不能沿用
                        // 较早的 turn-end 信号立即重试，否则会形成无上限的自动失败循环。
                        queueFlushDeferredUntilSendCompletes = false
                        val current = mutableComposer.value
                        mutableComposer.value =
                            current.copy(
                                text = if (restoreDraftOnFailure) prompt.text else current.text,
                                cursorPosition =
                                    if (restoreDraftOnFailure) prompt.text.length
                                    else current.cursorPosition,
                                attachments =
                                    if (restoreDraftOnFailure) prompt.attachments
                                    else current.attachments,
                                quotedContext =
                                    if (restoreDraftOnFailure) prompt.quotedContext
                                    else current.quotedContext,
                                queuedPrompts =
                                    if (requeueOnFailure) listOf(prompt) + current.queuedPrompts
                                    else current.queuedPrompts,
                                sending = false,
                                error = error.message ?: "message_send_failed",
                            )
                    }
                }
        }
    }

    private fun recordRecentCommand(command: String) {
        composerRecents.remove(command)
        composerRecents.add(0, command)
        while (composerRecents.size > 5) composerRecents.removeAt(composerRecents.lastIndex)
        composerRecentsRevision += 1
        val revision = composerRecentsRevision
        val snapshot = composerRecents.toList()
        viewModelScope.launch {
            composerRecentsSaveMutex.withLock {
                if (revision == composerRecentsRevision) {
                    composerRecentsStore.save(snapshot)
                }
            }
        }
    }

    private fun nextQueuedPromptId(): String {
        queueCounter += 1
        return "queued-prompt-${System.currentTimeMillis()}-$queueCounter"
    }

    private fun updateVoice(transform: (VoiceUiState) -> VoiceUiState) {
        mutableComposer.value =
            mutableComposer.value.copy(voice = transform(mutableComposer.value.voice))
    }
}

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
