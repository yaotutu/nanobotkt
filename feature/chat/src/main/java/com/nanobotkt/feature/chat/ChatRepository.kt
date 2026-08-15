package com.nanobotkt.feature.chat

import com.nanobotkt.core.model.AutomationsPayload
import com.nanobotkt.core.model.BootstrapSnapshotProvider
import com.nanobotkt.core.model.CliAppInfo
import com.nanobotkt.core.model.CliAppsPayload
import com.nanobotkt.core.model.FilePreviewPayload
import com.nanobotkt.core.model.InboundEvent
import com.nanobotkt.core.model.IngressLimitsProvider
import com.nanobotkt.core.model.McpPresetInfo
import com.nanobotkt.core.model.McpPresetsPayload
import com.nanobotkt.core.model.ModelPresetInfo
import com.nanobotkt.core.model.OutboundMedia
import com.nanobotkt.core.model.SessionAutomationJob
import com.nanobotkt.core.model.SettingsPayload
import com.nanobotkt.core.model.SkillSummary
import com.nanobotkt.core.model.SkillsPayload
import com.nanobotkt.core.model.SlashCommand
import com.nanobotkt.core.model.SlashCommandsPayload
import com.nanobotkt.core.model.UiCliAppAttachment
import com.nanobotkt.core.model.UiMediaAttachment
import com.nanobotkt.core.model.UiMcpPresetAttachment
import com.nanobotkt.core.model.UiMessage
import com.nanobotkt.core.model.WebUiIngressLimits
import com.nanobotkt.core.model.WebUiThreadPayload
import com.nanobotkt.core.model.WorkspaceScope
import com.nanobotkt.core.model.WorkspacesPayload
import com.nanobotkt.core.model.normalized
import com.nanobotkt.core.network.GatewayApiClient
import com.nanobotkt.core.network.GatewayException
import com.nanobotkt.core.transport.MessageSendResult
import com.nanobotkt.core.transport.NanobotTransport
import com.nanobotkt.core.transport.TransportError
import com.nanobotkt.core.transport.TransportStatus
import com.nanobotkt.core.workspace.WorkspaceAccessProvider
import java.net.URLEncoder
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

interface ChatRepository {
    val state: StateFlow<ChatUiState>
    /**
     * 清理当前登录会话留下的聊天状态，避免退出登录后旧会话内容继续显示。
     */
    fun reset()
    fun startNewTopic()
    fun openSession(sessionKey: String, chatId: String, workspaceScope: WorkspaceScope? = null, modelPreset: String? = null)
    suspend fun newChat(workspaceScope: WorkspaceScope? = null): String
    fun setWorkspaceScope(workspaceScope: WorkspaceScope)
    suspend fun changeModelPreset(name: String)
    fun refresh()
    fun loadOlder()
    suspend fun send(
        text: String,
        media: List<OutboundMedia> = emptyList(),
        quotedContext: String? = null,
        options: ChatSendOptions = ChatSendOptions(),
    ): ChatSendOutcome
    suspend fun retry(messageId: String): ChatSendOutcome
    suspend fun fork(beforeUserIndex: Int, title: String? = null): String
    fun stop()
    suspend fun transcribeAudio(dataUrl: String, durationMs: Long): String
    suspend fun loadSessionAutomations(sessionKey: String): List<SessionAutomationJob>
    /** 异步加载当前会话中某次文件编辑对应的文件内容。 */
    fun loadFilePreview(path: String)
    /** 关闭预览并清理可能已经过期的加载错误。 */
    fun clearFilePreview()
    /**
     * 把历史消息中的相对媒体路径解析为可加载地址。默认实现原样返回，测试 Fake 无需感知网络；
     * 生产 Repository 会补齐当前 Gateway origin，但不会把 Token 拼入 URL。
     */
    fun resolveMediaUrl(url: String): String = url
    fun clearError()
}

data class ChatSendOptions(
    val sideChannel: Boolean = false,
    val continueActiveTurn: Boolean = false,
    val cliApps: List<UiCliAppAttachment> = emptyList(),
    val mcpPresets: List<UiMcpPresetAttachment> = emptyList(),
    val capabilityPayloadsResolved: Boolean = false,
    val workspaceScope: WorkspaceScope? = null,
    /**
     * 普通直接发送失败时保留时间轴气泡；Queue 自动 flush 失败仍由 ViewModel 放回队首，不能同时
     * 留下一条 FAILED 消息，否则用户会看到同一 prompt 两份。
     */
    val retainFailureInTimeline: Boolean = true,
    /**
     * 发送开始时捕获的会话身份。切换会话后，旧 prompt 必须失败而不是发到新会话。
     */
    val sessionGuard: ChatSessionGuard? = null,
)

sealed interface ChatSendOutcome {
    data object Accepted : ChatSendOutcome

    /** acceptance 失败，但原始发送载荷已经安全保存在本地 FAILED 消息中。 */
    data class FailedRetained(
        val messageId: String,
        val reason: String,
    ) : ChatSendOutcome
}

data class ChatSessionGuard(
    val sessionKey: String?,
    val chatId: String?,
)

data class ChatUiState(
    val sessionKey: String? = null,
    val chatId: String? = null,
    val messages: List<UiMessage> = emptyList(),
    /** 只保存当前会话内客户端确认发送失败的本地消息 ID，不伪造服务端历史字段。 */
    val failedMessageIds: Set<String> = emptySet(),
    val loading: Boolean = false,
    val loadingOlder: Boolean = false,
    val sendingTurnIds: Set<String> = emptySet(),
    val activeTurnId: String? = null,
    val hasMoreBefore: Boolean = false,
    val beforeCursor: String? = null,
    val userMessageOffset: Int = 0,
    val error: String? = null,
    val limits: WebUiIngressLimits? = null,
    val slashCommands: List<SlashCommand> = emptyList(),
    val skills: List<SkillSummary> = emptyList(),
    val cliApps: List<CliAppInfo> = emptyList(),
    val mcpPresets: List<McpPresetInfo> = emptyList(),
    val workspaces: WorkspacesPayload? = null,
    val workspaceScope: WorkspaceScope? = null,
    val model: ChatModelSelection = ChatModelSelection(),
    val filePreview: FilePreviewPayload? = null,
    val filePreviewLoading: Boolean = false,
    val filePreviewError: String? = null,
)

@Singleton
class DefaultChatRepository @Inject constructor(
    private val api: GatewayApiClient,
    private val transport: NanobotTransport,
    private val limitsProvider: IngressLimitsProvider,
    private val bootstrapProvider: BootstrapSnapshotProvider,
    private val workspaceAccessProvider: WorkspaceAccessProvider,
) : ChatRepository {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val mutableState = MutableStateFlow(ChatUiState())
    private val canonical = mutableListOf<UiMessage>()
    private val optimistic = linkedMapOf<String, LocalOutgoingMessage>()
    private val sideChannelTurnIds = mutableSetOf<String>()
    /**
     * WebSocket 同一条拒绝会同时进入 acceptance Deferred、events 和 errors 三条异步链路。
     * 记录已经由 [awaitAcceptance] 转成 FAILED 气泡的 turnId，避免较晚到达的旁路事件再次
     * 写入全局错误，或在用户已经点击重试后用旧状态覆盖新的时间轴。
     */
    private val locallyHandledAcceptanceFailures = linkedSetOf<String>()
    private val streamFold = ChatStreamFold()
    private var modelSettings: SettingsPayload? = null
    private var localModelSelection: LocalModelSelection? = null
    private var activeSessionModelPreset: String? = null
    private var runtimeModelName: String? = null
    private var turnModelName: String? = null
    override val state: StateFlow<ChatUiState> = mutableState.asStateFlow()
    /** 文件预览请求代次；同一会话内的新请求也必须淘汰旧响应。 */
    private var filePreviewGeneration = 0L

    init {
        scope.launch { refreshComposerCatalogs() }
        scope.launch {
            workspaceAccessProvider.workspaces.collectLatest { payload ->
                val current = mutableState.value
                mutableState.value = current.copy(
                    workspaces = payload,
                    workspaceScope = if (current.chatId == null) {
                        current.workspaceScope ?: payload?.defaultScope?.normalized()
                    } else {
                        current.workspaceScope
                    },
                )
            }
        }
        scope.launch { workspaceAccessProvider.refresh() }
        scope.launch { transport.events.collect(::handleEvent) }
        scope.launch { transport.errors.collect(::handleTransportError) }
        scope.launch {
            transport.state.collectLatest { transportState ->
                publishModelSelection()
                if (transportState.needsCanonicalRefresh && mutableState.value.sessionKey != null) {
                    // 只有规范消息成功收敛后才能清除 dirty flag；HTTP 失败时保留
                    // 标记，下一次网络恢复仍会触发 canonical refresh。
                    if (refreshCanonical()) transport.clearCanonicalRefreshFlag()
                }
            }
        }
    }

    override fun reset() {
        // 退出登录时必须同时清理服务端会话标识、规范化消息和所有乐观消息，
        // 同时淘汰所有在途文件预览响应，避免旧账号内容回写到新登录会话。
        filePreviewGeneration += 1
        // 否则下一次登录可能短暂复用上一个账号的聊天内容。
        activeSessionModelPreset = null
        localModelSelection = null
        runtimeModelName = null
        turnModelName = null
        modelSettings = null
        canonical.clear()
        optimistic.clear()
        sideChannelTurnIds.clear()
        clearHandledAcceptanceFailures()
        streamFold.reset()
        mutableState.value = ChatUiState()
    }

    override fun startNewTopic() {
        filePreviewGeneration += 1
        val catalogs = mutableState.value
        activeSessionModelPreset = null
        turnModelName = null
        canonical.clear()
        optimistic.clear()
        sideChannelTurnIds.clear()
        clearHandledAcceptanceFailures()
        streamFold.reset()
        mutableState.value = ChatUiState(
            limits = limitsProvider.currentIngressLimits(),
            slashCommands = catalogs.slashCommands,
            skills = catalogs.skills,
            cliApps = catalogs.cliApps,
            mcpPresets = catalogs.mcpPresets,
            workspaces = catalogs.workspaces,
            workspaceScope = catalogs.workspaces?.defaultScope?.normalized(),
            model = buildModelSelection(scopeKey = NEW_TOPIC_MODEL_SCOPE),
        )
    }

    override fun openSession(sessionKey: String, chatId: String, workspaceScope: WorkspaceScope?, modelPreset: String?) {
        filePreviewGeneration += 1
        val current = mutableState.value
        if (current.sessionKey == sessionKey && current.chatId == chatId) {
            activeSessionModelPreset = modelPreset
            // Sidebar 可能在仓库已打开该会话后才拿到规范化 workspace 信息。
            // 同一会话重开时只接受非空值，避免缺省参数反向清掉当前已知范围。
            current.syncReopenedWorkspaceScope(workspaceScope).let { synchronizedState ->
                if (synchronizedState !== current) mutableState.value = synchronizedState
            }
            publishModelSelection()
            return
        }
        val catalogs = mutableState.value
        activeSessionModelPreset = modelPreset
        turnModelName = null
        canonical.clear()
        optimistic.clear()
        sideChannelTurnIds.clear()
        clearHandledAcceptanceFailures()
        streamFold.reset()
        mutableState.value = ChatUiState(
            sessionKey = sessionKey,
            chatId = chatId,
            loading = true,
            limits = limitsProvider.currentIngressLimits(),
            slashCommands = catalogs.slashCommands,
            skills = catalogs.skills,
            cliApps = catalogs.cliApps,
            mcpPresets = catalogs.mcpPresets,
            workspaces = catalogs.workspaces,
            workspaceScope = workspaceScope?.normalized() ?: catalogs.workspaces?.defaultScope?.normalized(),
            model = buildModelSelection(scopeKey = sessionKey),
        )
        transport.attach(chatId)
        scope.launch { refreshCanonical() }
    }

    override suspend fun newChat(workspaceScope: WorkspaceScope?): String {
        val sourceSessionKey = mutableState.value.sessionKey
        val normalizedScope = (workspaceScope ?: mutableState.value.workspaceScope)?.normalized()
        val chatId = transport.newChat(normalizedScope)
        val key = "websocket:$chatId"
        if (mutableState.value.sessionKey == sourceSessionKey) openSession(key, chatId, normalizedScope, modelPreset = null)
        return key
    }

    override fun setWorkspaceScope(workspaceScope: WorkspaceScope) {
        val current = mutableState.value
        if (current.activeTurnId != null) return
        val normalizedScope = workspaceScope.normalized()
        val chatId = current.chatId
        if (chatId == null) {
            mutableState.value = current.copy(workspaceScope = normalizedScope, error = null)
        } else {
            transport.setWorkspaceScope(chatId, normalizedScope)
        }
    }

    override suspend fun changeModelPreset(name: String) {
        val preset = name.trim()
        if (preset.isEmpty() || preset == mutableState.value.model.activePreset) return
        val previous = localModelSelection
        val scopeKey = mutableState.value.sessionKey ?: NEW_TOPIC_MODEL_SCOPE
        localModelSelection = LocalModelSelection(scopeKey, preset)
        publishModelSelection(pendingPreset = preset, error = null)
        try {
            mutableState.value.chatId?.let { chatId ->
                transport.sendSystemCommand(chatId, "/model $preset", timeoutMs = 5_000)
            }
            publishModelSelection(pendingPreset = null, error = null)
        } catch (error: CancellationException) {
            localModelSelection = previous
            publishModelSelection(pendingPreset = null, error = null)
            throw error
        } catch (error: Exception) {
            localModelSelection = previous
            publishModelSelection(
                pendingPreset = null,
                error = error.message ?: "model_preset_change_failed",
            )
            throw error
        }
    }
    override fun refresh() {
        scope.launch { refreshCanonical() }
    }

    override fun loadOlder() {
        val current = mutableState.value
        val sessionKey = current.sessionKey ?: return
        val chatId = current.chatId
        if (current.loadingOlder || !current.hasMoreBefore || current.beforeCursor == null) return
        mutableState.value = current.copy(loadingOlder = true)
        scope.launch {
            try {
                val page = fetchThread(sessionKey, before = current.beforeCursor, latest = false)
                if (!mutableState.value.matchesSession(sessionKey, chatId)) return@launch
                if (page == null) {
                    mutableState.value = mutableState.value.copy(loadingOlder = false)
                    return@launch
                }
                val merged = prependOlderMessages(canonical, page.messages)
                canonical.clear()
                canonical.addAll(merged)
                streamFold.markCompletedTurns(page.completedTurnIds.orEmpty().toSet())
                publish(
                    loadingOlder = false,
                    hasMore = page.page?.hasMoreBefore == true,
                    before = page.page?.beforeCursor,
                    userMessageOffset = page.page?.userMessageOffset ?: 0,
                )
            } catch (error: CancellationException) {
                if (mutableState.value.matchesSession(sessionKey, chatId)) {
                    mutableState.value = mutableState.value.copy(loadingOlder = false)
                }
                throw error
            } catch (error: Exception) {
                if (mutableState.value.matchesSession(sessionKey, chatId)) {
                    mutableState.value = mutableState.value.copy(
                        loadingOlder = false,
                        error = error.message ?: "thread_load_older_failed",
                    )
                }
            }
        }
    }

    override suspend fun send(
        text: String,
        media: List<OutboundMedia>,
        quotedContext: String?,
        options: ChatSendOptions,
    ): ChatSendOutcome {
        val guard = options.sessionGuard
        if (guard != null) {
            if (guard.chatId == null) {
                // 新主题尚未创建远程 chat 时，切换到任何已有会话都应使原 prompt 失效。
                check(mutableState.value.sessionKey == null && mutableState.value.chatId == null) {
                    "session_changed"
                }
                val createdKey = newChat(options.workspaceScope)
                check(mutableState.value.sessionKey == createdKey) { "session_changed" }
            } else {
                check(
                    mutableState.value.sessionKey == guard.sessionKey &&
                        mutableState.value.chatId == guard.chatId,
                ) { "session_changed" }
            }
        } else if (mutableState.value.chatId == null) {
            newChat(options.workspaceScope)
        }
        val pending = enqueueMessage(text, media, quotedContext, options) ?: error("message_empty")
        return awaitAcceptance(pending)
    }

    override suspend fun retry(messageId: String): ChatSendOutcome {
        check(mutableState.value.activeTurnId == null) { "turn_active" }
        val failedEntry = optimistic.entries.firstOrNull { (_, outgoing) ->
            outgoing.message.id == messageId && outgoing.deliveryState == LocalDeliveryState.FAILED
        } ?: error("message_not_found")
        val failed = failedEntry.value
        check(
            failed.sessionKey == mutableState.value.sessionKey &&
                failed.chatId == mutableState.value.chatId,
        ) { "session_changed" }

        // transport 会为重试生成新的 turnId。先移除旧 FAILED 气泡，新的 optimistic 消息立即接管；
        // 若连 sendMessage 都未能创建请求，则恢复旧记录，避免用户失去唯一可重试副本。
        optimistic.remove(failedEntry.key)
        val pending =
            try {
                enqueueMessage(
                    rawText = failed.rawText,
                    media = failed.media,
                    quotedContext = failed.quotedContext,
                    options =
                        failed.options.copy(
                            retainFailureInTimeline = true,
                            sessionGuard =
                                ChatSessionGuard(
                                    sessionKey = mutableState.value.sessionKey,
                                    chatId = mutableState.value.chatId,
                                ),
                        ),
                ) ?: error("message_empty")
            } catch (error: CancellationException) {
                // 调用方取消不代表服务端拒绝。恢复原 FAILED 记录并继续传播取消，避免把协程
                // 生命周期事件误报成新的发送失败，也不吞掉结构化并发的取消信号。
                optimistic[failedEntry.key] = failed
                publish()
                throw error
            } catch (error: Exception) {
                optimistic[failedEntry.key] = failed
                publish()
                throw error
            }
        return awaitAcceptance(pending)
    }

    override suspend fun fork(beforeUserIndex: Int, title: String?): String {
        val source = mutableState.value
        val chatId = source.chatId ?: error("chat_not_connected")
        val forkedChatId = transport.forkChat(chatId, beforeUserIndex, title)
        val key = "websocket:$forkedChatId"
        if (mutableState.value.sessionKey == source.sessionKey && mutableState.value.chatId == chatId) {
            openSession(key, forkedChatId, source.workspaceScope)
        }
        return key
    }

    private fun enqueueMessage(
        rawText: String,
        media: List<OutboundMedia>,
        quotedContext: String?,
        options: ChatSendOptions = ChatSendOptions(),
    ): PendingLocalSend? {
        val normalizedQuote = normalizeQuotedContext(quotedContext)
        val value = formatQuotedUserMessage(rawText, normalizedQuote)
        val chatId = mutableState.value.chatId ?: return null
        if (value.isEmpty() && media.isEmpty()) return null
        val limits = limitsProvider.currentIngressLimits()
        limits?.message?.maxTextBytes?.let {
            require(value.toByteArray(Charsets.UTF_8).size <= it) { "message_text_too_large" }
        }
        limits?.attachments?.let {
            require(media.size <= it.maxCount) { "attachment_count_exceeded" }
        }
        val explicitPayloads = options.capabilityPayloadsResolved
        val capabilityPayloads = if (explicitPayloads) {
            CapabilityMentionPayloads(options.cliApps, options.mcpPresets)
        } else {
            activeCapabilityMentionPayloads(
                value = rawText,
                cliApps = mutableState.value.cliApps,
                mcpPresets = mutableState.value.mcpPresets,
            )
        }
        val result = transport.sendMessage(
            chatId = chatId,
            content = value,
            media = media,
            cliApps = capabilityPayloads.cliApps,
            mcpPresets = capabilityPayloads.mcpPresets,
            quotedContext = normalizedQuote.takeIf(String::isNotEmpty),
            workspaceScope = (options.workspaceScope ?: mutableState.value.workspaceScope)?.normalized(),
            startsNewRun = !options.sideChannel && !options.continueActiveTurn,
        )
        val local = UiMessage(
            id = "local:${result.turnId}",
            role = "user",
            content = value,
            createdAt = System.currentTimeMillis(),
            turnId = result.turnId,
            turnPhase = "user",
            media =
                media.map { outbound ->
                    UiMediaAttachment(
                        kind =
                            inferTimelineMediaKind(
                                url = outbound.dataUrl,
                                name = outbound.name,
                            ),
                        url = outbound.dataUrl,
                        name = outbound.name,
                    )
                }.ifEmpty { null },
        )
        optimistic[result.turnId] =
            LocalOutgoingMessage(
                sessionKey = mutableState.value.sessionKey,
                chatId = chatId,
                message = local,
                rawText = rawText,
                media = media,
                quotedContext = normalizedQuote.takeIf(String::isNotEmpty),
                options = options,
            )
        if (options.sideChannel) sideChannelTurnIds += result.turnId
        mutableState.value = mutableState.value.copy(
            sendingTurnIds = mutableState.value.sendingTurnIds + result.turnId,
            activeTurnId = if (options.sideChannel || options.continueActiveTurn) {
                mutableState.value.activeTurnId
            } else {
                result.turnId
            },
            error = null,
        )
        publish()
        return PendingLocalSend(result = result)
    }

    /**
     * acceptance 是“服务端已经接管该消息”的边界。只有越过该边界才把本地消息视为正常发送；
     * acceptance 失败时，普通消息保留 FAILED 气泡，而 Queue 自动 flush 仍抛给 ViewModel 重新排队。
     */
    private suspend fun awaitAcceptance(pending: PendingLocalSend): ChatSendOutcome {
        val result = pending.result
        return try {
            result.accepted.await()
            mutableState.value =
                mutableState.value.copy(
                    sendingTurnIds = mutableState.value.sendingTurnIds - result.turnId,
                )
            ChatSendOutcome.Accepted
        } catch (error: CancellationException) {
            // 等待者被取消时，WebSocket 请求可能仍在服务端处理中。这里不能擅自把消息标记为
            // FAILED；后续 acceptance/turn 事件仍由 Repository 的事件收集器继续归并。
            throw error
        } catch (error: Exception) {
            val outgoing = optimistic[result.turnId]
            rememberHandledAcceptanceFailure(result.turnId)
            val retainFailure = outgoing?.options?.retainFailureInTimeline == true
            if (outgoing != null && retainFailure) {
                optimistic[result.turnId] = outgoing.copy(deliveryState = LocalDeliveryState.FAILED)
            } else {
                optimistic.remove(result.turnId)
            }
            sideChannelTurnIds.remove(result.turnId)
            val reason = error.message ?: "message_send_failed"
            mutableState.value =
                mutableState.value.copy(
                    sendingTurnIds = mutableState.value.sendingTurnIds - result.turnId,
                    activeTurnId = mutableState.value.activeTurnId.takeUnless { it == result.turnId },
                    // 普通发送已经通过 FAILED 气泡提供局部反馈，不再额外弹全局 Snackbar；
                    // Queue flush 不保留气泡，异常继续抛给 ViewModel 统一提示并重新入队。
                    error = if (retainFailure) null else reason,
                )
            publish()
            if (retainFailure) {
                ChatSendOutcome.FailedRetained(checkNotNull(outgoing).message.id, reason)
            } else {
                throw error
            }
        }
    }
    override fun stop() {
        mutableState.value.chatId?.let(transport::stopTurn)
    }

    override suspend fun transcribeAudio(dataUrl: String, durationMs: Long): String =
        transport.transcribeAudio(dataUrl, durationMs)

    override suspend fun loadSessionAutomations(sessionKey: String): List<SessionAutomationJob> =
        api.request(
            path = "/api/sessions/${sessionKey.pathEncoded()}/automations",
            deserializer = AutomationsPayload.serializer(),
        ).jobs

    override fun loadFilePreview(path: String) {
        val requestSessionKey = mutableState.value.sessionKey
        if (requestSessionKey.isNullOrBlank()) {
            mutableState.value = mutableState.value.copy(
                filePreview = null,
                filePreviewLoading = false,
                filePreviewError = "chat_session_required",
            )
            return
        }

        // 捕获请求发起时的完整身份和代次。用户切换会话、重新打开同一会话，
        // 或在同一会话中点击另一个文件后，迟到的旧响应都不能回写当前预览。
        val requestGeneration = ++filePreviewGeneration
        mutableState.value = mutableState.value.copy(
            filePreview = null,
            filePreviewLoading = true,
            filePreviewError = null,
        )
        scope.launch {
            runCatching {
                api.request(
                    path = "/api/sessions/${requestSessionKey.pathEncoded()}/file-preview",
                    deserializer = FilePreviewPayload.serializer(),
                    query = mapOf("path" to path),
                )
            }.onSuccess { preview ->
                if (filePreviewGeneration == requestGeneration && mutableState.value.sessionKey == requestSessionKey) {
                    mutableState.value = mutableState.value.copy(
                        filePreview = preview,
                        filePreviewLoading = false,
                        filePreviewError = null,
                    )
                }
            }.onFailure { error ->
                if (filePreviewGeneration == requestGeneration && mutableState.value.sessionKey == requestSessionKey) {
                    mutableState.value = mutableState.value.copy(
                        filePreview = null,
                        filePreviewLoading = false,
                        filePreviewError = error.message ?: "file_preview_failed",
                    )
                }
            }
        }
    }

    override fun clearFilePreview() {
        filePreviewGeneration += 1
        mutableState.value = mutableState.value.copy(
            filePreview = null,
            filePreviewLoading = false,
            filePreviewError = null,
        )
    }

    /** 媒体 URL 解析委托给共享网络客户端，确保与当前登录会话实际 baseUrl 保持一致。 */
    override fun resolveMediaUrl(url: String): String = api.resolveUrl(url)

    override fun clearError() {
        mutableState.value = mutableState.value.copy(
            error = null,
            model = mutableState.value.model.copy(error = null),
        )
    }

    private fun buildModelSelection(
        scopeKey: String = mutableState.value.sessionKey ?: NEW_TOPIC_MODEL_SCOPE,
        pendingPreset: String? = mutableState.value.model.pendingPreset,
        error: String? = mutableState.value.model.error,
    ): ChatModelSelection {
        val settings = modelSettings
        val presets = orderedModelPresets(
            presets = settings?.modelPresets.orEmpty(),
            callOrder = settings?.modelCallOrder.orEmpty(),
        )
        val activePreset = resolveActiveModelPreset(
            scopeKey = scopeKey,
            localSelection = localModelSelection,
            sessionModelPreset = activeSessionModelPreset,
            settingsModelPreset = settings?.agent?.modelPreset,
        )
        return ChatModelSelection(
            activePreset = activePreset,
            displayLabel = resolveModelDisplayLabel(
                activePreset = activePreset,
                presets = presets,
                turnModelName = turnModelName,
                runtimeModelName = runtimeModelName,
                bootstrapModelName = bootstrapProvider.currentBootstrap()?.modelName,
            ),
            presets = presets,
            pendingPreset = pendingPreset,
            error = error,
            enabled = transport.state.value.status == TransportStatus.OPEN,
        )
    }

    private fun publishModelSelection(
        pendingPreset: String? = mutableState.value.model.pendingPreset,
        error: String? = mutableState.value.model.error,
    ) {
        val current = mutableState.value
        mutableState.value = current.copy(
            model = buildModelSelection(
                scopeKey = current.sessionKey ?: NEW_TOPIC_MODEL_SCOPE,
                pendingPreset = pendingPreset,
                error = error,
            ),
        )
    }

    private suspend fun refreshCanonical(): Boolean {
        val sessionState = mutableState.value
        val session = sessionState.sessionKey ?: return false
        val chatId = sessionState.chatId
        try {
            val payload = fetchThread(session, before = null, latest = true)
            // 会话在请求期间切换时，旧响应不能被视为当前会话的成功刷新。
            if (!mutableState.value.matchesSession(session, chatId)) return false
            if (payload == null) {
                canonical.clear()
                publish(
                    loading = false,
                    hasMore = false,
                    before = null,
                    activeTurnId = null,
                    userMessageOffset = 0,
                )
                return true
            }

            val reconciled = mergeLatestMessages(canonical, payload.messages)
            canonical.clear()
            canonical.addAll(reconciled)

            val canonicalTurns = payload.messages.mapNotNullTo(mutableSetOf(), UiMessage::turnId)
            val completedTurns = payload.completedTurnIds.orEmpty().toSet()
            (canonicalTurns + completedTurns).forEach(optimistic::remove)
            streamFold.discardTurns(canonicalAssistantTurnIds(payload.messages))
            streamFold.markCompletedTurns(completedTurns)
            payload.workspaceScope?.let { canonicalScope ->
                mutableState.value = mutableState.value.copy(workspaceScope = canonicalScope.normalized())
            }

            publish(
                loading = false,
                hasMore = payload.page?.hasMoreBefore == true,
                before = payload.page?.beforeCursor,
                activeTurnId = payload.activeTurnId,
                userMessageOffset = payload.page?.userMessageOffset ?: 0,
            )
            return true
        } catch (error: CancellationException) {
            if (mutableState.value.matchesSession(session, chatId)) {
                mutableState.value = mutableState.value.copy(loading = false, loadingOlder = false)
            }
            throw error
        } catch (error: Exception) {
            if (mutableState.value.matchesSession(session, chatId)) {
                mutableState.value = mutableState.value.copy(
                    loading = false,
                    loadingOlder = false,
                    error = error.message ?: "thread_refresh_failed",
                )
            }
            return false
        }
    }

    /**
     * Composer 使用的技能目录必须复用 WebUI 的公开只读路由。
     * 服务端没有注册 `/api/skills`，继续使用旧路径会让聊天页静默丢失技能候选。
     */
    internal companion object {
        const val COMPOSER_SKILLS_PATH = "/api/webui/skills"
    }

    private suspend fun refreshComposerCatalogs() {
        runCatching {
            api.request(
                path = "/api/commands",
                deserializer = SlashCommandsPayload.serializer(),
            )
        }.onSuccess { payload ->
            mutableState.value = mutableState.value.copy(
                slashCommands = payload.commands.filter(SlashCommand::hasSupportedLifecycle),
            )
        }
        runCatching {
            api.request(
                path = COMPOSER_SKILLS_PATH,
                deserializer = SkillsPayload.serializer(),
            )
        }.onSuccess { payload ->
            mutableState.value = mutableState.value.copy(skills = payload.skills)
        }
        runCatching {
            api.request(
                path = "/api/settings/cli-apps",
                deserializer = CliAppsPayload.serializer(),
                query = mapOf("installed_only" to 1),
            )
        }.onSuccess { payload ->
            mutableState.value = mutableState.value.copy(cliApps = payload.apps)
        }
        runCatching {
            api.request(
                path = "/api/settings/mcp-presets",
                deserializer = McpPresetsPayload.serializer(),
            )
        }.onSuccess { payload ->
            mutableState.value = mutableState.value.copy(mcpPresets = payload.presets)
        }
        refreshModelSettings()
    }

    private suspend fun refreshModelSettings() {
        runCatching {
            api.request(
                path = "/api/settings",
                deserializer = SettingsPayload.serializer(),
            )
        }.onSuccess { payload ->
            modelSettings = payload
            publishModelSelection()
        }
    }

    private suspend fun fetchThread(
        sessionKey: String,
        before: String?,
        latest: Boolean,
    ): WebUiThreadPayload? {
        val query = buildMap<String, Any?> {
            put("limit", if (latest) 160 else 120)
            if (latest) put("direction", "latest")
            if (before != null) put("before", before)
        }
        return try {
            api.request(
                path = "/api/sessions/${sessionKey.pathEncoded()}/webui-thread",
                deserializer = WebUiThreadPayload.serializer(),
                query = query,
            )
        } catch (error: GatewayException.Http) {
            if (error.status == 404) null else throw error
        }
    }

    private fun handleEvent(event: InboundEvent) {
        when (event) {
            is InboundEvent.RuntimeModelUpdated -> {
                runtimeModelName = event.modelName.trim().takeIf(String::isNotEmpty)
                publishModelSelection()
                scope.launch { refreshModelSettings() }
                return
            }
            is InboundEvent.TurnModelUpdated -> {
                if (event.chatId == mutableState.value.chatId) {
                    turnModelName = event.modelName.trim().takeIf(String::isNotEmpty)
                    publishModelSelection()
                }
                return
            }
            else -> Unit
        }
        val activeChat = mutableState.value.chatId ?: return
        val eventChat = event.chatIdOrNull() ?: return
        if (eventChat != activeChat) return

        val sideChannelTurnId = event.turnIdOrNull()
            ?.takeIf(sideChannelTurnIds::contains)
        if (sideChannelTurnId != null) {
            handleSideChannelEvent(event, sideChannelTurnId)
            return
        }

        when (event) {
            is InboundEvent.Delta,
            is InboundEvent.ReasoningDelta,
            is InboundEvent.ReasoningEnd,
            is InboundEvent.FileEdit,
            is InboundEvent.Message,
            is InboundEvent.StreamEnd,
            -> {
                streamFold.fold(event)
                publish()
            }

            is InboundEvent.TurnEnd -> {
                streamFold.fold(event)
                event.turnId?.let(optimistic::remove)
                mutableState.value = mutableState.value.copy(
                    activeTurnId = mutableState.value.activeTurnId.takeUnless { activeTurnId ->
                        event.turnId == null || activeTurnId == event.turnId
                    },
                    sendingTurnIds = event.turnId?.let { mutableState.value.sendingTurnIds - it }
                        ?: mutableState.value.sendingTurnIds,
                )
                publish()
                scope.launch {
                    delay(250)
                    refreshCanonical()
                }
            }

            is InboundEvent.Error -> {
                val turnId = event.turnId
                val displayError =
                    listOfNotNull(event.detail, event.reason)
                        .joinToString(": ")
                        .ifBlank { "turn_rejected" }
                mutableState.update { current ->
                    val handledByLocalFailure =
                        turnId != null &&
                            (turnId in current.sendingTurnIds || isHandledAcceptanceFailure(turnId))
                    if (handledByLocalFailure) current else current.copy(error = displayError)
                }
                // acceptance 阶段的错误由 awaitAcceptance 转成 FAILED 或重新排队。events 的收集
                // 可能晚于用户点击重试，因此必须按 turnId 识别旧错误，并使用原子 update 避免
                // 较晚的协程把旧 failedMessageIds/messages 快照重新写回当前时间轴。
            }

            is InboundEvent.SessionUpdated -> {
                event.workspaceScope?.let { canonicalScope ->
                    mutableState.value = mutableState.value.copy(workspaceScope = canonicalScope.normalized())
                }
            }

            else -> Unit
        }
    }

    private fun handleSideChannelEvent(event: InboundEvent, turnId: String) {
        when (event) {
            is InboundEvent.Message -> {
                streamFold.fold(event)
                sideChannelTurnIds.remove(turnId)
                mutableState.value = mutableState.value.copy(
                    sendingTurnIds = mutableState.value.sendingTurnIds - turnId,
                )
                publish()
                scope.launch {
                    delay(250)
                    refreshCanonical()
                }
            }

            is InboundEvent.TurnEnd -> {
                sideChannelTurnIds.remove(turnId)
                optimistic.remove(turnId)
                mutableState.value = mutableState.value.copy(
                    sendingTurnIds = mutableState.value.sendingTurnIds - turnId,
                )
                publish()
                scope.launch {
                    delay(250)
                    refreshCanonical()
                }
            }

            is InboundEvent.Error -> {
                sideChannelTurnIds.remove(turnId)
                val isAwaitingAcceptance = turnId in mutableState.value.sendingTurnIds
                mutableState.value = mutableState.value.copy(
                    // optimistic 必须保留到 awaitAcceptance 决定 FAILED 或删除；先删除会让普通
                    // side-channel 失败丢失可重试气泡，也会破坏 Queue 失败的统一回滚路径。
                    error =
                        if (isAwaitingAcceptance) {
                            mutableState.value.error
                        } else {
                            listOfNotNull(event.detail, event.reason)
                                .joinToString(": ")
                                .ifBlank { "turn_rejected" }
                        },
                )
                publish()
            }

            else -> Unit
        }
    }

    private fun publish(
        loading: Boolean = mutableState.value.loading,
        loadingOlder: Boolean = mutableState.value.loadingOlder,
        hasMore: Boolean = mutableState.value.hasMoreBefore,
        before: String? = mutableState.value.beforeCursor,
        activeTurnId: String? = mutableState.value.activeTurnId,
        userMessageOffset: Int = mutableState.value.userMessageOffset,
    ) {
        val canonicalTurns = canonical.mapNotNullTo(mutableSetOf(), UiMessage::turnId)
        val canonicalAssistantTurns = canonicalAssistantTurnIds(canonical)
        val merged = buildList {
            addAll(canonical)
            addAll(
                optimistic.filterKeys { it !in canonicalTurns }
                    .values
                    .map(LocalOutgoingMessage::message),
            )
            addAll(
                streamFold.snapshot().filterNot { transient ->
                    transient.turnId != null && transient.turnId in canonicalAssistantTurns
                },
            )
        }.sortedBy { it.createdAt }
        mutableState.value = mutableState.value.copy(
            messages = merged,
            failedMessageIds =
                optimistic.values
                    .filter { it.deliveryState == LocalDeliveryState.FAILED }
                    .mapTo(mutableSetOf()) { it.message.id },
            loading = loading,
            loadingOlder = loadingOlder,
            hasMoreBefore = hasMore,
            beforeCursor = before,
            activeTurnId = activeTurnId,
            userMessageOffset = userMessageOffset,
            limits = limitsProvider.currentIngressLimits(),
        )
    }

    private fun handleTransportError(error: TransportError) {
        val activeChat = mutableState.value.chatId
        val relevant = when (error) {
            is TransportError.DeliveryUnknown -> error.chatId == activeChat
            is TransportError.MessageTooBig -> error.chatId == null || error.chatId == activeChat
            is TransportError.TurnRejected -> error.chatId == activeChat
            is TransportError.WorkspaceScopeRejected -> error.chatId == null || error.chatId == activeChat
        }
        if (!relevant) return

        when (error) {
            is TransportError.DeliveryUnknown -> publishTransportError(error.turnId, error.toString())
            is TransportError.MessageTooBig -> publishTransportError(error.turnId, error.toString())
            is TransportError.TurnRejected -> {
                // 相同拒绝还会作为 InboundEvent.Error 到达；UI 错误只由事件链路决定，避免
                // errors/events 两个收集协程互相覆盖状态。acceptance 失败则由 FAILED 气泡反馈。
            }
            is TransportError.WorkspaceScopeRejected -> {
                // Workspace 拒绝仍需刷新权限快照，但错误展示交给 InboundEvent.Error；否则
                // acceptance 失败会同时出现 FAILED 气泡和全局 Snackbar。
                scope.launch { workspaceAccessProvider.refresh() }
            }
        }
    }

    /**
     * Transport 旁路错误可能在 acceptance 已失败、甚至用户已开始重试后才到达。原子更新只
     * 修改 error 字段，并跳过已被 FAILED 气泡接管的 turn，避免旧 ChatUiState 快照回灌。
     */
    private fun publishTransportError(turnId: String?, message: String) {
        mutableState.update { current ->
            val handledByLocalFailure =
                turnId != null &&
                    (turnId in current.sendingTurnIds || isHandledAcceptanceFailure(turnId))
            if (handledByLocalFailure) current else current.copy(error = message)
        }
    }

    /**
     * 集合只承担跨异步链路的短期去重，不是消息历史。限制为最近 64 个失败 turn，防止长时间
     * 运行时无界增长；会话切换、退出登录和新主题都会整体清空。
     */
    private fun rememberHandledAcceptanceFailure(turnId: String) {
        synchronized(locallyHandledAcceptanceFailures) {
            locallyHandledAcceptanceFailures += turnId
            while (locallyHandledAcceptanceFailures.size > MAX_HANDLED_ACCEPTANCE_FAILURES) {
                val oldest = locallyHandledAcceptanceFailures.iterator()
                if (oldest.hasNext()) {
                    oldest.next()
                    oldest.remove()
                }
            }
        }
    }

    private fun isHandledAcceptanceFailure(turnId: String): Boolean =
        synchronized(locallyHandledAcceptanceFailures) {
            turnId in locallyHandledAcceptanceFailures
        }

    private fun clearHandledAcceptanceFailures() {
        synchronized(locallyHandledAcceptanceFailures) {
            locallyHandledAcceptanceFailures.clear()
        }
    }
}

/**
 * 同一会话重开时只同步明确提供的 workspace 范围；缺省参数必须保留当前值。
 */
internal fun ChatUiState.syncReopenedWorkspaceScope(workspaceScope: WorkspaceScope?): ChatUiState =
    workspaceScope
        ?.normalized()
        ?.takeIf { normalizedScope -> normalizedScope != this.workspaceScope }
        ?.let { normalizedScope -> copy(workspaceScope = normalizedScope) }
        ?: this

/**
 * 网络响应只能写回发起请求时对应的完整会话身份；sessionKey 相同但 chatId 不同
 * 仍然代表两个独立的远程聊天，不能让旧响应覆盖当前会话。
 */
internal fun ChatUiState.matchesSession(sessionKey: String?, chatId: String?): Boolean =
    this.sessionKey == sessionKey && this.chatId == chatId

private fun canonicalAssistantTurnIds(messages: List<UiMessage>): Set<String> = messages
    .asSequence()
    .filter { it.role != "user" }
    .mapNotNull(UiMessage::turnId)
    .toSet()

private fun String.pathEncoded(): String = URLEncoder.encode(this, Charsets.UTF_8.name()).replace("+", "%20")

/**
 * 失败消息必须保留重新发送所需的原始载荷，不能只依赖 [UiMessage.content]：后者已经拼入引用文本，
 * 并且不包含 capability、workspace 和原始附件信息。
 */
private data class LocalOutgoingMessage(
    val sessionKey: String?,
    val chatId: String,
    val message: UiMessage,
    val rawText: String,
    val media: List<OutboundMedia>,
    val quotedContext: String?,
    val options: ChatSendOptions,
    val deliveryState: LocalDeliveryState = LocalDeliveryState.SENDING,
)

private enum class LocalDeliveryState {
    SENDING,
    FAILED,
}

private data class PendingLocalSend(
    val result: MessageSendResult,
)

private const val MAX_HANDLED_ACCEPTANCE_FAILURES = 64
private fun InboundEvent.chatIdOrNull(): String? = when (this) {
    is InboundEvent.Ready -> chatId
    is InboundEvent.Attached -> chatId
    is InboundEvent.MessageAccepted -> chatId
    is InboundEvent.Message -> chatId
    is InboundEvent.FileEdit -> chatId
    is InboundEvent.Delta -> chatId
    is InboundEvent.ReasoningDelta -> chatId
    is InboundEvent.ReasoningEnd -> chatId
    is InboundEvent.StreamEnd -> chatId
    is InboundEvent.TurnEnd -> chatId
    is InboundEvent.GoalStatus -> chatId
    is InboundEvent.SessionUpdated -> chatId
    is InboundEvent.TurnModelUpdated -> chatId
    is InboundEvent.GoalState -> chatId
    is InboundEvent.Error -> chatId
    else -> null
}
private fun InboundEvent.turnIdOrNull(): String? = when (this) {
    is InboundEvent.MessageAccepted -> turnId
    is InboundEvent.Message -> turnId
    is InboundEvent.FileEdit -> turnId
    is InboundEvent.Delta -> turnId
    is InboundEvent.ReasoningDelta -> turnId
    is InboundEvent.ReasoningEnd -> turnId
    is InboundEvent.StreamEnd -> turnId
    is InboundEvent.TurnEnd -> turnId
    is InboundEvent.GoalStatus -> turnId
    is InboundEvent.TurnModelUpdated -> null
    is InboundEvent.Error -> turnId
    else -> null
}
