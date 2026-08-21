package com.nanobotkt.feature.chat

import android.net.Uri
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.nanobotkt.core.model.SessionAutomationJob
import com.nanobotkt.core.model.SlashCommand
import com.nanobotkt.core.model.UiCliAppAttachment
import com.nanobotkt.core.model.UiMcpPresetAttachment
import com.nanobotkt.core.model.WorkspaceScope
import com.nanobotkt.core.persistence.ComposerDraftStore
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
    private val composerDraftStore: ComposerDraftStore,
) : ViewModel() {
    val state: StateFlow<ChatUiState> = repository.state

    // Composer 只保存一条可恢复 Draft；ViewModel 负责发送编排，Repository 负责服务端状态。
    private val composerCoordinator =
        ComposerStateCoordinator(viewModelScope, composerRecentsStore, composerDraftStore)
    val composer: StateFlow<ComposerUiState> = composerCoordinator.state
    private val voiceCoordinator =
        ChatVoiceCoordinator(
            scope = viewModelScope,
            recorder = voiceRecorder,
            composer = composerCoordinator,
            transcribe = repository::transcribeAudio,
        )

    private var openedSessionKey: String? = null

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
            composerCoordinator.switchScope(existingComposerScope(sessionKey, chatId))
            openedSessionKey = sessionKey
        }
        repository.openSession(sessionKey, chatId, workspaceScope, modelPreset)
    }

    fun startNewTopic() {
        repository.clearFilePreview()
        voiceCoordinator.reset()
        composerCoordinator.switchScope(newTopicComposerScope(state.value.workspaceScope?.projectPath))
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
        if (!canEditDraft()) return
        val normalized = normalizeQuotedContext(content)
        composerCoordinator.value =
            composerCoordinator.value.copy(
                quotedContext = normalized.takeIf(String::isNotEmpty),
                error = null,
            )
    }

    fun clearQuotedContext() {
        if (!canEditDraft()) return
        composerCoordinator.value = composerCoordinator.value.copy(quotedContext = null)
    }

    fun updateText(text: String, cursorPosition: Int = text.length) {
        if (!canEditDraft()) return
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
            // Active turn 的唯一提交动作是 Stop；停止服务端 turn 不应清空用户正在编辑的 Draft。
            stop()
            return
        }
        if (!canEditDraft()) return

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
        if (!canEditDraft()) return
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
        if (!canEditDraft()) return
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
        if (!canEditDraft()) return
        composerCoordinator.value =
            composerCoordinator.value.copy(
                attachments =
                    composerCoordinator.value.attachments.filterIndexed { itemIndex, _ ->
                        itemIndex != index
                    }
            )
    }

    fun addAttachments(uris: List<Uri>) {
        if (uris.isEmpty() || !canEditDraft()) return

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
        // 产品取舍：服务端仍有 active turn 时，主按钮和业务入口都只允许 Stop。
        // 这里的业务 guard 不能只依赖 UI，否则 IME Send、无障碍动作或迟到点击仍可能绕过按钮。
        if (
            state.value.activeTurnId != null ||
                current.sending ||
                // hydration 完成前磁盘可能持有更大的 revision。此时允许编辑但禁止发送，避免
                // 较小 revision 的同步保存被 Room 拒绝后仍调用网络，导致 acceptance 后旧草稿复活。
                current.hydrating ||
                current.encodingCount > 0 ||
                (current.text.isBlank() &&
                    current.attachments.isEmpty() &&
                    current.quotedContext.isNullOrBlank())
        ) return

        val capabilityPayloads =
            activeCapabilityMentionPayloads(
                value = current.text,
                cliApps = state.value.cliApps,
                mcpPresets = state.value.mcpPresets,
            )
        val requestEpoch = composerCoordinator.epoch
        val snapshotRevision = current.revision
        val text = current.text
        val attachments = current.attachments
        val quotedContext = current.quotedContext
        val options =
            ChatSendOptions(
                retainFailureInTimeline = false,
                cliApps = capabilityPayloads.cliApps,
                mcpPresets = capabilityPayloads.mcpPresets,
                capabilityPayloadsResolved = true,
                workspaceScope = state.value.workspaceScope,
                sessionGuard =
                    ChatSessionGuard(
                        sessionKey = state.value.sessionKey,
                        chatId = state.value.chatId,
                    ),
            )

        // acceptance 返回前保持正文、引用和附件可见，但文本框进入只读，避免同一 revision
        // 同时代表“正在发送的内容”和“下一份草稿”而重新引入双状态同步问题。
        composerCoordinator.value = current.copy(sending = true, error = null)
        viewModelScope.launch {
            try {
                // Room 是网络发送的前置条件。若本地保存失败，绝不调用 Repository，Composer 原样保留。
                val stillCurrent =
                    composerCoordinator.persistDraftForSend(requestEpoch, snapshotRevision)
                if (!stillCurrent) return@launch

                when (
                    val outcome =
                        repository.send(
                            text = text,
                            media = attachments.map { it.outbound },
                            quotedContext = quotedContext,
                            options = options,
                        )
                ) {
                    ChatSendOutcome.Accepted -> {
                        // 只有明确 acceptance 才清空；条件 revision 防止迟到结果误删其他会话或新输入。
                        composerCoordinator.clearAfterAcceptance(requestEpoch, snapshotRevision)
                    }
                    is ChatSendOutcome.FailedRetained -> {
                        finishSendFailure(requestEpoch, outcome.reason)
                    }
                }
            } catch (error: CancellationException) {
                // 取消可能发生在 WebSocket 已写出之后。磁盘 Draft 保持原样，恢复后由用户决定是否重发。
                throw error
            } catch (error: Exception) {
                finishSendFailure(requestEpoch, error.message ?: "message_send_failed")
            }
        }
    }

    /**
     * 发送 acceptance 等待期禁止修改 payload。
     *
     * UI 会同步禁用文本、附件和引用入口，这里的业务 guard 负责兜住 IME、无障碍动作、迟到
     * callback 与测试直接调用，避免发送快照和可见 Draft 再次分裂成两份状态。Active turn 不在
     * 此限制中：用户仍可编辑下一条，只是主操作固定为 Stop。
     */
    private fun canEditDraft(): Boolean = !composerCoordinator.value.sending

    /** 发送失败只解除只读并展示错误；正文、引用、附件和磁盘 Draft 都保持不变。 */
    private fun finishSendFailure(requestEpoch: Long, reason: String) {
        if (requestEpoch != composerCoordinator.epoch) return
        composerCoordinator.value =
            composerCoordinator.value.copy(
                sending = false,
                error = reason,
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

    fun startVoiceRecording(permissionGranted: Boolean) {
        if (canEditDraft()) voiceCoordinator.start(permissionGranted)
    }

    fun stopVoiceRecording(cancelled: Boolean = false, maxReached: Boolean = false) =
        voiceCoordinator.stop(cancelled, maxReached)

    /** 锁屏、HOME 或切到其他应用时停止录音，并绕过 debounce 尽力保存最后一次输入。 */
    fun onAppBackgrounded() {
        voiceCoordinator.stop(cancelled = true)
        viewModelScope.launch {
            // 发送中的 payload 已在网络调用前同步保存；这里主要覆盖尚未点击发送的最后几百毫秒输入。
            runCatching { composerCoordinator.persistCurrentDraftNow() }
        }
    }

    fun stop() {
        // Stop 只停止当前服务端 turn，绝不修改用户正在编辑的下一份 Draft。
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

}
