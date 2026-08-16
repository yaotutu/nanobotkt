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
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch

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

    // Composer、Queue 和 Voice 各自只持有本职责内的可变状态；ViewModel 只编排用户事件和仓储调用。
    private val composerCoordinator =
        ComposerStateCoordinator(viewModelScope, composerRecentsStore)
    val composer: StateFlow<ComposerUiState> = composerCoordinator.state
    private val queueCoordinator =
        ChatQueueCoordinator(initialTurnActive = state.value.activeTurnId != null)
    private val voiceCoordinator =
        ChatVoiceCoordinator(
            scope = viewModelScope,
            recorder = voiceRecorder,
            composer = composerCoordinator,
            transcribe = repository::transcribeAudio,
        )

    private var openedSessionKey: String? = null

    init {
        viewModelScope.launch {
            state.collect { chatState ->
                if (
                    queueCoordinator.onChatStateChanged(
                        turnActive = chatState.activeTurnId != null,
                        composer = composerCoordinator.value,
                    )
                ) {
                    flushNextQueuedPrompt()
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
            voiceCoordinator.reset()
            composerCoordinator.resetForSession()
            queueCoordinator.reset(turnActive = state.value.activeTurnId != null)
            openedSessionKey = sessionKey
        }
        repository.openSession(sessionKey, chatId, workspaceScope, modelPreset)
    }

    fun startNewTopic() {
        repository.clearFilePreview()
        voiceCoordinator.reset()
        composerCoordinator.resetForSession()
        queueCoordinator.reset(turnActive = state.value.activeTurnId != null)
        openedSessionKey = null
        repository.startNewTopic()
    }

    fun setWorkspaceScope(workspaceScope: WorkspaceScope) {
        if (state.value.activeTurnId == null) repository.setWorkspaceScope(workspaceScope)
    }

    fun newChat(onCreated: (String) -> Unit = {}) =
        viewModelScope.launch {
            val requestEpoch = composerCoordinator.epoch
            runCatching { repository.newChat(state.value.workspaceScope) }
                .onSuccess { sessionKey ->
                    if (requestEpoch == composerCoordinator.epoch) onCreated(sessionKey)
                }
                .onFailure {
                    if (requestEpoch == composerCoordinator.epoch) {
                        composerCoordinator.value =
                            composerCoordinator.value.copy(error = it.message ?: "new_chat_failed")
                    }
                }
        }

    fun changeModelPreset(name: String) =
        viewModelScope.launch { runCatching { repository.changeModelPreset(name) } }

    fun refresh() = repository.refresh()

    fun loadOlder() = repository.loadOlder()

    fun setQuotedContext(content: String) {
        val normalized = normalizeQuotedContext(content)
        composerCoordinator.value =
            composerCoordinator.value.copy(
                quotedContext = normalized.takeIf(String::isNotEmpty),
                error = null,
            )
    }

    fun clearQuotedContext() {
        composerCoordinator.value = composerCoordinator.value.copy(quotedContext = null)
    }

    fun updateText(text: String, cursorPosition: Int = text.length) {
        composerCoordinator.value =
            composerCoordinator.value.copy(
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
            composerCoordinator.value =
                composerCoordinator.value.copy(
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

        composerCoordinator.recordRecentCommand(command.command)
        val nextText = if (command.acceptsArgs) "${command.command} " else command.command
        composerCoordinator.value =
            composerCoordinator.value.copy(
                text = nextText,
                cursorPosition = nextText.length,
                slashMenuDismissed = true,
                mentionMenuDismissed = false,
                error = null,
            )
    }

    fun selectSkillMention(candidate: SkillMentionCandidate) {
        val current = composerCoordinator.value
        val query = skillMentionQuery(current.text, current.cursorPosition) ?: return
        val next = insertSkillMention(current.text, query, candidate)
        composerCoordinator.recordRecentCommand(candidate.command)
        composerCoordinator.value =
            composerCoordinator.value.copy(
                text = next.value,
                cursorPosition = next.cursor,
                slashMenuDismissed = true,
                mentionMenuDismissed = false,
                error = null,
            )
    }

    fun selectCapabilityMention(candidate: CapabilityMentionCandidate) {
        val current = composerCoordinator.value
        val query = capabilityMentionQuery(current.text, current.cursorPosition) ?: return
        val next = insertCapabilityMention(current.text, query, candidate)
        composerCoordinator.value =
            current.copy(
                text = next.value,
                cursorPosition = next.cursor,
                slashMenuDismissed = false,
                mentionMenuDismissed = true,
                error = null,
            )
    }

    fun removeAttachment(index: Int) {
        composerCoordinator.value =
            composerCoordinator.value.copy(
                attachments =
                    composerCoordinator.value.attachments.filterIndexed { itemIndex, _ ->
                        itemIndex != index
                    }
            )
    }

    fun addAttachments(uris: List<Uri>) {
        if (uris.isEmpty()) return

        // 在启动协程前捕获 epoch，避免用户切换会话后协程才开始执行时，
        // 错把旧会话的附件任务绑定到新会话的 composer。
        val requestEpoch = composerCoordinator.epoch
        val limits = ingressLimits(state.value.limits)
        val current = composerCoordinator.value
        // encodingCount 代表已经占用的附件名额；并发选择附件时必须把它计入
        // available，否则多批任务可能共同超过服务端的附件数量上限。
        val available =
            (limits.maxCount - current.attachments.size - current.encodingCount).coerceAtLeast(0)
        if (available == 0) {
            composerCoordinator.value = current.copy(error = "too_many_attachments")
            return
        }

        val selected = uris.take(available)
        composerCoordinator.value =
            current.copy(
                encodingCount = current.encodingCount + selected.size,
                error = if (uris.size > available) "too_many_attachments" else null,
            )

        viewModelScope.launch {
            selected.forEach { uri ->
                try {
                    val attachment = attachmentEncoder.encode(uri, limits.maxFileBytes)
                    if (requestEpoch != composerCoordinator.epoch) return@launch
                    val error =
                        validateEncodedAttachment(
                            current = composerCoordinator.value.attachments,
                            candidate = attachment,
                            limits = limits,
                        )
                    if (error == null) {
                        composerCoordinator.value =
                            composerCoordinator.value.copy(
                                attachments = composerCoordinator.value.attachments + attachment
                            )
                    } else {
                        composerCoordinator.value = composerCoordinator.value.copy(error = error)
                    }
                } catch (error: CancellationException) {
                    // ViewModel 被销毁或任务被取消时必须保留协程取消语义，不能把取消
                    // 当作普通附件错误吞掉，否则上层生命周期无法正确结束编码任务。
                    throw error
                } catch (error: Throwable) {
                    if (requestEpoch == composerCoordinator.epoch) {
                        composerCoordinator.value =
                            composerCoordinator.value.copy(error = error.message ?: "io")
                    }
                } finally {
                    if (requestEpoch == composerCoordinator.epoch) {
                        composerCoordinator.value =
                            composerCoordinator.value.copy(
                                encodingCount =
                                    (composerCoordinator.value.encodingCount - 1).coerceAtLeast(0)
                            )
                    }
                }
            }
        }
    }

    fun send() {
        val current = composerCoordinator.value
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
                id = queueCoordinator.nextPromptId(),
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
            composerCoordinator.value =
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
            composerCoordinator.value =
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

        queueCoordinator.onDirectSendStarted()
        composerCoordinator.value =
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
        composerCoordinator.value =
            composerCoordinator.value.copy(
                queuedPrompts = composerCoordinator.value.queuedPrompts.filterNot { it.id == id }
            )
    }

    fun retry(messageId: String) {
        if (state.value.activeTurnId != null || composerCoordinator.value.retryingMessageId != null)
            return
        composerCoordinator.value =
            composerCoordinator.value.copy(retryingMessageId = messageId, error = null)
        viewModelScope.launch {
            val requestEpoch = composerCoordinator.epoch
            runCatching { repository.retry(messageId) }
                .onSuccess {
                    // 重试再次被服务端拒绝时 Repository 会保留新的 FAILED 气泡；Composer 不再
                    // 额外显示同一错误，避免用户同时看到气泡状态和底部错误两份反馈。
                    if (requestEpoch == composerCoordinator.epoch) {
                        composerCoordinator.value = composerCoordinator.value.copy(error = null)
                    }
                }
                .onFailure { error ->
                    if (requestEpoch == composerCoordinator.epoch) {
                        composerCoordinator.value =
                            composerCoordinator.value.copy(
                                error = error.message ?: "message_send_failed"
                            )
                    }
                }
            if (requestEpoch == composerCoordinator.epoch) {
                composerCoordinator.value = composerCoordinator.value.copy(retryingMessageId = null)
            }
        }
    }

    fun fork(messageId: String, beforeUserIndex: Int, title: String, onCreated: (String) -> Unit) {
        if (composerCoordinator.value.forkingMessageId != null) return
        composerCoordinator.value =
            composerCoordinator.value.copy(forkingMessageId = messageId, error = null)
        // 在启动 coroutine 之前捕获 epoch，避免切换会话发生在调度前时误把旧结果
        // 应用到新会话的 Composer 状态。
        val requestEpoch = composerCoordinator.epoch
        viewModelScope.launch {
            runCatching { repository.fork(beforeUserIndex, title) }
                .onSuccess { sessionKey ->
                    if (requestEpoch == composerCoordinator.epoch) {
                        composerCoordinator.value = ComposerUiState()
                        onCreated(sessionKey)
                    }
                }
                .onFailure { error ->
                    if (requestEpoch == composerCoordinator.epoch) {
                        composerCoordinator.value =
                            composerCoordinator.value.copy(error = error.message ?: "fork_failed")
                    }
                }
            if (requestEpoch == composerCoordinator.epoch) {
                composerCoordinator.value = composerCoordinator.value.copy(forkingMessageId = null)
            }
        }
    }

    fun startVoiceRecording(permissionGranted: Boolean) =
        voiceCoordinator.start(permissionGranted)

    fun stopVoiceRecording(cancelled: Boolean = false, maxReached: Boolean = false) =
        voiceCoordinator.stop(cancelled, maxReached)

    fun stop() {
        val current = composerCoordinator.value
        queueCoordinator.onStop(hasQueuedPrompts = current.queuedPrompts.isNotEmpty())
        composerCoordinator.value = current.copy(queuedPrompts = emptyList())
        repository.stop()
    }

    fun clearError() = repository.clearError()

    /** 请求当前会话的文件预览；仓储层负责校验会话并隔离迟到响应。 */
    fun previewFile(path: String) = repository.loadFilePreview(path)

    fun closeFilePreview() = repository.clearFilePreview()

    /** Compose 只获得无凭据的绝对媒体地址，不直接依赖凭据提供者或网络客户端。 */
    fun resolveMediaUrl(url: String): String = repository.resolveMediaUrl(url)

    val loadSessionAutomations: suspend (String) -> List<SessionAutomationJob> =
        repository::loadSessionAutomations

    override fun onCleared() {
        voiceCoordinator.close()
        super.onCleared()
    }

    private fun flushNextQueuedPrompt() {
        val current = composerCoordinator.value
        if (current.sending || state.value.activeTurnId != null) return
        val next = current.queuedPrompts.firstOrNull() ?: return
        queueCoordinator.onQueueFlushStarted()
        composerCoordinator.value =
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
        val requestEpoch = composerCoordinator.epoch
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
                    if (requestEpoch == composerCoordinator.epoch) {
                        composerCoordinator.value =
                            composerCoordinator.value.copy(
                                attachments =
                                    if (restoreDraftOnFailure) emptyList()
                                    else composerCoordinator.value.attachments,
                                sending = false,
                                // FailedRetained 已经通过时间轴气泡提供可操作反馈；底部不再重复
                                // 展示全局错误。Accepted 同样清除上一轮 Composer 错误。
                                error = null,
                            )
                        // 如果 turn-end 发生在 acceptance 返回之前，状态收集器已经把本次
                        // flush 延后。必须在 sending=false 之后补做一次，否则不会再出现新的
                        // active→idle 边沿，剩余排队消息会一直停在顶部和时间轴中。
                        if (queueCoordinator.onSubmitSucceeded()) {
                            flushNextQueuedPrompt()
                        }
                    }
                }
                .onFailure { error ->
                    if (requestEpoch == composerCoordinator.epoch) {
                        // Queue acceptance 失败会把当前 prompt 插回队首并等待用户处理；不能沿用
                        // 较早的 turn-end 信号立即重试，否则会形成无上限的自动失败循环。
                        queueCoordinator.onSubmitFailed()
                        val current = composerCoordinator.value
                        composerCoordinator.value =
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
}
