package com.nanobotkt.feature.chat

import com.nanobotkt.core.model.GatewayRuntimeSnapshotProvider
import com.nanobotkt.core.model.CliAppInfo
import com.nanobotkt.core.model.FilePreviewPayload
import com.nanobotkt.core.model.InboundEvent
import com.nanobotkt.core.model.IngressLimitsProvider
import com.nanobotkt.core.model.McpPresetInfo
import com.nanobotkt.core.model.ModelPresetInfo
import com.nanobotkt.core.model.OutboundMedia
import com.nanobotkt.core.model.SessionAutomationJob
import com.nanobotkt.core.model.SettingsPayload
import com.nanobotkt.core.model.SkillSummary
import com.nanobotkt.core.model.SlashCommand
import com.nanobotkt.core.model.UiCliAppAttachment
import com.nanobotkt.core.model.UiMediaAttachment
import com.nanobotkt.core.model.UiMcpPresetAttachment
import com.nanobotkt.core.model.UiMessage
import com.nanobotkt.core.model.WebUiIngressLimits
import com.nanobotkt.core.model.WorkspaceScope
import com.nanobotkt.core.model.WorkspacesPayload
import com.nanobotkt.core.model.normalized
import com.nanobotkt.core.network.GatewayApiClient
import com.nanobotkt.core.transport.MessageSendResult
import com.nanobotkt.core.transport.NanobotTransport
import com.nanobotkt.core.transport.TransportError
import com.nanobotkt.core.transport.TransportState
import com.nanobotkt.core.transport.TransportStatus
import com.nanobotkt.core.workspace.WorkspaceAccessProvider
import java.util.concurrent.atomic.AtomicLong
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

interface ChatRepository {
    val state: StateFlow<ChatUiState>
    /**
     * 通知 Repository 一个认证会话已经建立。
     *
     * Composer 目录和 Workspace 都依赖认证 Token，不能在 Singleton 构造阶段抢跑。
     * [sessionEpoch] 由认证层生成，同一登录会话内的 Token 续期不会重复加载已经成功的目录；
     * 目录加载失败时，后续 Ready 通知仍可触发重试。
     */
    fun onAuthenticated(sessionEpoch: Long) = Unit
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
    fun stop(): Boolean
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
     * 普通调用默认在时间轴保留 FAILED 气泡。Composer 直发会关闭该选项，因为失败时完整 Draft
     * 仍留在输入框中；同一正文若再生成失败气泡，会形成两份可重试入口并增加重复发送风险。
     */
    val retainFailureInTimeline: Boolean = true,
    /**
     * 发送开始时捕获的会话身份。切换会话后，旧 prompt 必须失败而不是发到新会话。
     */
    val sessionGuard: ChatSessionGuard? = null,
)

sealed interface ChatSendOutcome {
    data object Accepted : ChatSendOutcome

    /** acceptance 失败；messageId 供保留 FAILED 气泡的调用方定位消息，Composer 直发只使用 reason。 */
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
    /** 正在向服务端提交停止请求的 turn；非空时 UI 必须禁用停止按钮，防止重复 `/stop`。 */
    val stoppingTurnId: String? = null,
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
    private val runtimeSnapshotProvider: GatewayRuntimeSnapshotProvider,
    private val workspaceAccessProvider: WorkspaceAccessProvider,
) : ChatRepository {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val mutableState = MutableStateFlow(ChatUiState())
    /**
     * canonical、optimistic、stream fold 与其 StateFlow 投影必须作为一个事务串行修改。
     *
     * Repository 同时接收主线程操作、HTTP 回调和 WebSocket 事件；只依赖 session guard 不能阻止
     * 同一会话内的 read-copy-write 交错。使用一把窄范围 JVM 锁即可建立单写入者，不引入 Actor 框架，
     * 且普通 runBlocking 单元测试不需要安装 Dispatchers.Main。
     */
    private val timelineWriterLock = Any()
    private val canonical = mutableListOf<UiMessage>()
    /** 服务端明确确认已完成的 turn；只有这些 turn 才能安全淘汰同 turn 的流式快照。 */
    private val canonicalCompletedTurnIds = mutableSetOf<String>()
    /**
     * 同一 session/chat 内每次 latest 快照提交都会推进时间线代次。
     * loadOlder 必须携带发起时的代次，避免旧 cursor 响应插入一次较新的 canonical reset。
     */
    private var canonicalLineageGeneration = 0L
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
    /**
     * 认证生命周期只保护 Composer 目录请求，不取代聊天会话自身的 sessionKey/chatId guard。
     *
     * AppViewModel、logout 清理和 IO 回调可能位于不同线程，因此这里用锁协调 Job 引用与加载结果，
     * 再用原子代次阻止已经无法及时取消的 HTTP 响应写回新账号状态。
     */
    private val authenticatedLifecycleLock = Any()
    private val composerCatalogGeneration = AtomicLong(0L)
    @Volatile
    private var authenticatedSessionEpoch: Long? = null
    private var composerCatalogLoaded = false
    private var composerCatalogJob: Job? = null
    /** 具体 HTTP 协议下沉到 feature 内部数据源，Repository 只保留认证与 UI 写回边界。 */
    private val composerCatalogLoader = ComposerCatalogLoader(api)
    private val sessionLoader = ChatSessionLoader(api)
    private val filePreviewLoader = ChatFilePreviewLoader(api)
    /** 多个 TurnEnd、手动刷新和恢复 dirty 信号可能同时到达；HTTP latest 请求统一串行化。 */
    private val canonicalRefreshMutex = Mutex()

    init {
        scope.launch {
            workspaceAccessProvider.workspaces.collectLatest { payload ->
                // Workspace 数据可能与 openSession、HTTP 回包和 WebSocket 事件并发到达。
                // 必须用原子 update 基于提交时的最新状态计算，禁止旧的 ChatUiState 快照覆盖刚打开的会话。
                mutableState.update { current ->
                    current.copy(
                        workspaces = payload,
                        workspaceScope = if (current.chatId == null) {
                            current.workspaceScope ?: payload?.defaultScope?.normalized()
                        } else {
                            current.workspaceScope
                        },
                    )
                }
            }
        }
        scope.launch { transport.events.collect(::handleEvent) }
        scope.launch { transport.errors.collect(::handleTransportError) }
        scope.launch {
            // Model 的 enabled 状态仍需跟随连接状态更新，但不能把 canonical HTTP 请求放在这个
            // 高频流中；每个 delta 都会更新 lastActivityAt，collectLatest 会因此反复取消请求。
            transport.state.collect { publishModelSelection() }
        }
        scope.launch {
            transport.state
                .map(TransportState::toCanonicalRefreshTrigger)
                .distinctUntilChanged()
                .collectLatest { trigger ->
                    if (!trigger.canRefresh || mutableState.value.sessionKey == null) return@collectLatest

                    // active turn 在断线窗口内可能继续运行，而 reconnect attach 不会补发 TurnEnd。
                    // 因此 active 快照只能暂时收敛 UI，不能确认 dirty；保持低频重试直到服务端明确
                    // 返回 settled 快照。相关状态变化会取消本循环，但 lastActivityAt 不再会取消它。
                    while (isCurrentCanonicalRefreshTrigger(trigger)) {
                        val result = refreshCanonical()
                        if (result.applied && result.settled) {
                            transport.acknowledgeCanonicalRefresh(trigger.generation)
                            break
                        }
                        delay(CANONICAL_RECOVERY_RETRY_MS)
                    }
                }
        }
    }

    override fun onAuthenticated(sessionEpoch: Long) {
        val generation: Long
        val job: Job
        synchronized(authenticatedLifecycleLock) {
            val sameSession = authenticatedSessionEpoch == sessionEpoch
            if (sameSession && (composerCatalogLoaded || composerCatalogJob?.isActive == true)) return

            // 新账号登录或上一轮目录加载失败时，提升代次并取消旧 Job。即使 OkHttp 仍返回旧响应，
            // refreshComposerCatalogs 内的双重代次检查也会阻止其写入当前 StateFlow。
            authenticatedSessionEpoch = sessionEpoch
            composerCatalogLoaded = false
            composerCatalogJob?.cancel()
            generation = composerCatalogGeneration.incrementAndGet()
            job = scope.launch(start = CoroutineStart.LAZY) {
                var completed = false
                try {
                    completed = refreshComposerCatalogs(sessionEpoch, generation)
                } finally {
                    synchronized(authenticatedLifecycleLock) {
                        if (isCurrentAuthenticatedSession(sessionEpoch, generation)) {
                            composerCatalogLoaded = completed
                            composerCatalogJob = null
                        }
                    }
                }
            }
            composerCatalogJob = job
        }

        // Workspace 自身已经使用 session/refresh 双代次保护写回；这里只负责把首次认证刷新
        // 从 Repository 构造阶段迁移到明确的 Ready 边界，避免无 Token 请求。
        scope.launch { workspaceAccessProvider.refresh() }
        job.start()
    }

    override fun reset() {
        // 退出登录时必须同时清理服务端会话标识、规范化消息和所有乐观消息，
        // 同时淘汰所有在途文件预览响应，避免旧账号内容回写到新登录会话。
        filePreviewLoader.invalidate()
        synchronized(authenticatedLifecycleLock) {
            authenticatedSessionEpoch = null
            composerCatalogLoaded = false
            composerCatalogGeneration.incrementAndGet()
            composerCatalogJob?.cancel()
            composerCatalogJob = null
        }
        // 否则下一次登录可能短暂复用上一个账号的聊天内容。
        activeSessionModelPreset = null
        localModelSelection = null
        runtimeModelName = null
        turnModelName = null
        modelSettings = null
        synchronized(timelineWriterLock) {
            clearTimelineLocked()
            mutableState.value = ChatUiState()
        }
    }

    override fun startNewTopic() {
        filePreviewLoader.invalidate()
        val catalogs = mutableState.value
        activeSessionModelPreset = null
        turnModelName = null
        synchronized(timelineWriterLock) {
            clearTimelineLocked()
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
    }

    override fun openSession(sessionKey: String, chatId: String, workspaceScope: WorkspaceScope?, modelPreset: String?) {
        filePreviewLoader.invalidate()
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
        synchronized(timelineWriterLock) {
            clearTimelineLocked()
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
        }
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
        val request = synchronized(timelineWriterLock) {
            val current = mutableState.value
            val sessionKey = current.sessionKey ?: return@synchronized null
            val beforeCursor = current.beforeCursor
            if (current.loadingOlder || !current.hasMoreBefore || beforeCursor == null) return@synchronized null

            // loadingOlder 与 lineage 必须在同一把时间线锁内捕获。否则 latest refresh 可能在
            // “读 cursor”和“标记加载中”之间提交新快照，使请求从一开始就属于错误边界。
            mutableState.value = current.copy(loadingOlder = true)
            OlderPageRequest(
                sessionKey = sessionKey,
                chatId = current.chatId,
                beforeCursor = beforeCursor,
                lineageGeneration = canonicalLineageGeneration,
            )
        } ?: return
        scope.launch {
            try {
                val page = sessionLoader.loadThread(request.sessionKey, before = request.beforeCursor, latest = false)
                if (page == null) {
                    synchronized(timelineWriterLock) {
                        if (isCurrentOlderPageRequestLocked(request)) {
                            mutableState.value = mutableState.value.copy(loadingOlder = false)
                        }
                    }
                    return@launch
                }
                synchronized(timelineWriterLock) {
                    // 除完整会话身份外还必须核对 latest lineage。同一会话在锁屏恢复后可能已经
                    // 提交了新窗口，旧 cursor 响应此时只能丢弃，不能污染新窗口的分页边界。
                    if (!isCurrentOlderPageRequestLocked(request)) return@synchronized
                    val merged = prependOlderMessages(canonical, page.messages)
                    canonical.clear()
                    canonical.addAll(merged)
                    val completedTurns = page.completedTurnIds.orEmpty().toSet()
                    canonicalCompletedTurnIds.addAll(completedTurns)
                    streamFold.markCompletedTurns(completedTurns)
                    publishLocked(
                        loadingOlder = false,
                        hasMore = page.page?.hasMoreBefore == true,
                        before = page.page?.beforeCursor,
                        userMessageOffset = page.page?.userMessageOffset ?: 0,
                    )
                }
            } catch (error: CancellationException) {
                synchronized(timelineWriterLock) {
                    if (isCurrentOlderPageRequestLocked(request)) {
                        mutableState.value = mutableState.value.copy(loadingOlder = false)
                    }
                }
                throw error
            } catch (error: Exception) {
                synchronized(timelineWriterLock) {
                    if (isCurrentOlderPageRequestLocked(request)) {
                        mutableState.value = mutableState.value.copy(
                            loadingOlder = false,
                            error = error.message ?: "thread_load_older_failed",
                        )
                    }
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
        val failedEntry = synchronized(timelineWriterLock) {
            val entry = optimistic.entries.firstOrNull { (_, outgoing) ->
                outgoing.message.id == messageId && outgoing.deliveryState == LocalDeliveryState.FAILED
            } ?: error("message_not_found")
            check(
                entry.value.sessionKey == mutableState.value.sessionKey &&
                    entry.value.chatId == mutableState.value.chatId,
            ) { "session_changed" }
            // transport 会为重试生成新的 turnId。先移除旧 FAILED 气泡，新的 optimistic 消息立即接管；
            // 若连 sendMessage 都未能创建请求，则恢复旧记录，避免用户失去唯一可重试副本。
            optimistic.remove(entry.key)
            entry.key to entry.value
        }
        val failed = failedEntry.second
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
                synchronized(timelineWriterLock) {
                    optimistic[failedEntry.first] = failed
                    publishLocked()
                }
                throw error
            } catch (error: Exception) {
                synchronized(timelineWriterLock) {
                    optimistic[failedEntry.first] = failed
                    publishLocked()
                }
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
        if (value.isEmpty() && media.isEmpty()) return null
        return synchronized(timelineWriterLock) {
            val current = mutableState.value
            val chatId = current.chatId ?: return@synchronized null
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
                    cliApps = current.cliApps,
                    mcpPresets = current.mcpPresets,
                )
            }
            // sendMessage 只构造并排队 WebSocket 帧，不执行阻塞 I/O；放在写锁内可以保证
            // 捕获的会话身份、本地 optimistic 记录和 transport 请求属于同一个原子入口。
            val result = transport.sendMessage(
                chatId = chatId,
                content = value,
                media = media,
                cliApps = capabilityPayloads.cliApps,
                mcpPresets = capabilityPayloads.mcpPresets,
                quotedContext = normalizedQuote.takeIf(String::isNotEmpty),
                workspaceScope = (options.workspaceScope ?: current.workspaceScope)?.normalized(),
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
                sessionKey = current.sessionKey,
                chatId = chatId,
                message = local,
                rawText = rawText,
                media = media,
                quotedContext = normalizedQuote.takeIf(String::isNotEmpty),
                options = options,
                )
            if (options.sideChannel) sideChannelTurnIds += result.turnId
            mutableState.value = current.copy(
            sendingTurnIds = current.sendingTurnIds + result.turnId,
            activeTurnId = if (options.sideChannel || options.continueActiveTurn) {
                current.activeTurnId
            } else {
                result.turnId
            },
            error = null,
            )
            publishLocked()
            PendingLocalSend(result = result)
        }
    }

    /**
     * acceptance 是“服务端已经接管该消息”的边界。只有越过该边界才把本地消息视为正常发送；
     * acceptance 失败时是否保留 FAILED 气泡由调用方决定；Composer 直发关闭气泡并保留原 Draft。
     */
    private suspend fun awaitAcceptance(pending: PendingLocalSend): ChatSendOutcome {
        val result = pending.result
        return try {
            result.accepted.await()
            mutableState.update { current ->
                current.copy(sendingTurnIds = current.sendingTurnIds - result.turnId)
            }
            ChatSendOutcome.Accepted
        } catch (error: CancellationException) {
            // 等待者被取消时，WebSocket 请求可能仍在服务端处理中。这里不能擅自把消息标记为
            // FAILED；后续 acceptance/turn 事件仍由 Repository 的事件收集器继续归并。
            throw error
        } catch (error: Exception) {
            val reason = error.message ?: "message_send_failed"
            val outgoing: LocalOutgoingMessage?
            val retainFailure: Boolean
            synchronized(timelineWriterLock) {
                outgoing = optimistic[result.turnId]
                rememberHandledAcceptanceFailureLocked(result.turnId)
                retainFailure = outgoing?.options?.retainFailureInTimeline == true
                if (outgoing != null && retainFailure) {
                    optimistic[result.turnId] = outgoing.copy(deliveryState = LocalDeliveryState.FAILED)
                } else {
                    optimistic.remove(result.turnId)
                }
                sideChannelTurnIds.remove(result.turnId)
                val current = mutableState.value
                mutableState.value = current.copy(
                    sendingTurnIds = current.sendingTurnIds - result.turnId,
                    activeTurnId = current.activeTurnId.takeUnless { it == result.turnId },
                    // 保留 FAILED 气泡时由时间轴提供局部反馈，不再额外弹全局 Snackbar；
                    // Composer 直发不保留气泡，由仍在输入框中的 Draft 和底部错误统一反馈。
                    error = if (retainFailure) null else reason,
                )
                publishLocked()
            }
            if (retainFailure) {
                ChatSendOutcome.FailedRetained(checkNotNull(outgoing).message.id, reason)
            } else {
                throw error
            }
        }
    }
    override fun stop(): Boolean {
        val request = synchronized(timelineWriterLock) {
            val current = mutableState.value
            val chatId = current.chatId ?: return false
            val activeTurnId = current.activeTurnId ?: return false
            // 第一次点击同步写入 pending；后续点击即使发生在网络协程真正启动前，也会在这里被拒绝，
            // 从而保证一个活动 turn 最多发送一条 `/stop`，并避免重复执行停止后的状态收敛。
            if (current.stoppingTurnId == activeTurnId) return false
            mutableState.value = current.copy(
                stoppingTurnId = activeTurnId,
                error = null,
            )
            StopTurnRequest(
                sessionKey = current.sessionKey,
                chatId = chatId,
                turnId = activeTurnId,
            )
        }
        scope.launch {
            try {
                transport.stopTurn(request.chatId)
                // 命令被 Gateway 接受不代表原 turn 已经结束。pending 保持到对应 turn_end 或
                // canonical 快照确认 activeTurnId 消失，避免确认窗口内再次点击产生重复取消消息。
            } catch (error: CancellationException) {
                clearStoppingRequest(request)
                throw error
            } catch (error: Exception) {
                synchronized(timelineWriterLock) {
                    val current = mutableState.value
                    if (current.sessionKey == request.sessionKey &&
                        current.chatId == request.chatId &&
                        current.stoppingTurnId == request.turnId
                    ) {
                        mutableState.value = current.copy(
                            stoppingTurnId = null,
                            error = error.message ?: "stop_turn_failed",
                        )
                    }
                }
            }
        }
        return true
    }

    /** 仅允许原停止请求清理自己的 pending，避免切换会话后迟到失败回调覆盖新会话状态。 */
    private fun clearStoppingRequest(request: StopTurnRequest) {
        synchronized(timelineWriterLock) {
            val current = mutableState.value
            if (current.sessionKey == request.sessionKey &&
                current.chatId == request.chatId &&
                current.stoppingTurnId == request.turnId
            ) {
                mutableState.value = current.copy(stoppingTurnId = null)
            }
        }
    }

    override suspend fun transcribeAudio(dataUrl: String, durationMs: Long): String =
        transport.transcribeAudio(dataUrl, durationMs)

    override suspend fun loadSessionAutomations(sessionKey: String): List<SessionAutomationJob> =
        sessionLoader.loadAutomations(sessionKey)

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
        val requestGeneration = filePreviewLoader.beginRequest()
        mutableState.update { current ->
            current.copy(
                filePreview = null,
                filePreviewLoading = true,
                filePreviewError = null,
            )
        }
        scope.launch {
            try {
                val preview = filePreviewLoader.load(requestSessionKey, path)
                if (filePreviewLoader.isCurrent(requestGeneration) && mutableState.value.sessionKey == requestSessionKey) {
                    mutableState.update { current ->
                        current.copy(
                            filePreview = preview,
                            filePreviewLoading = false,
                            filePreviewError = null,
                        )
                    }
                }
            } catch (error: CancellationException) {
                // Repository scope 被取消时必须传播取消，不能把它伪装成文件预览失败。
                throw error
            } catch (error: Exception) {
                if (filePreviewLoader.isCurrent(requestGeneration) && mutableState.value.sessionKey == requestSessionKey) {
                    mutableState.update { current ->
                        current.copy(
                            filePreview = null,
                            filePreviewLoading = false,
                            filePreviewError = error.message ?: "file_preview_failed",
                        )
                    }
                }
            }
        }
    }

    override fun clearFilePreview() {
        filePreviewLoader.invalidate()
        // 清理预览只负责三个预览字段，使用原子更新避免与会话加载结果互相覆盖。
        mutableState.update { current ->
            current.copy(
                filePreview = null,
                filePreviewLoading = false,
                filePreviewError = null,
            )
        }
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
                bootstrapModelName = runtimeSnapshotProvider.currentRuntimeSnapshot()?.modelName,
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
        // Transport 状态由后台线程持续发布；模型投影计算期间 openSession 可能已经替换整个会话状态。
        // MutableStateFlow.update 会在 CAS 冲突时基于最新会话重算，只更新 model 字段，不回灌旧 sessionKey/chatId。
        mutableState.update { current ->
            current.copy(
                model = buildModelSelection(
                    scopeKey = current.sessionKey ?: NEW_TOPIC_MODEL_SCOPE,
                    pendingPreset = pendingPreset,
                    error = error,
                ),
            )
        }
    }

    private suspend fun refreshCanonical(): CanonicalRefreshResult = canonicalRefreshMutex.withLock {
        val sessionState = mutableState.value
        val session = sessionState.sessionKey ?: return@withLock CanonicalRefreshResult.Ignored
        val chatId = sessionState.chatId
        try {
            val payload = sessionLoader.loadThread(session, before = null, latest = true)
            // 会话在请求期间切换时，旧响应既不能写回，也不能被恢复协调器当成成功确认。
            if (!mutableState.value.matchesSession(session, chatId)) {
                return@withLock CanonicalRefreshResult.Ignored
            }
            if (payload == null) {
                var applied = false
                synchronized(timelineWriterLock) {
                    if (mutableState.value.matchesSession(session, chatId)) {
                        canonicalLineageGeneration += 1L
                        canonical.clear()
                        canonicalCompletedTurnIds.clear()
                        publishLocked(
                            loading = false,
                            loadingOlder = false,
                            hasMore = false,
                            before = null,
                            activeTurnId = null,
                            userMessageOffset = 0,
                        )
                        applied = true
                    }
                }
                return@withLock if (applied) {
                    CanonicalRefreshResult.AppliedSettled
                } else {
                    CanonicalRefreshResult.Ignored
                }
            }

            var applied = false
            synchronized(timelineWriterLock) {
                if (mutableState.value.matchesSession(session, chatId)) {
                    // latest 是新的权威窗口。先推进 lineage，让任何使用旧 cursor 的分页响应失效，
                    // 再一次性提交规范消息、完成 turn 集合和分页元数据。
                    canonicalLineageGeneration += 1L
                    val reconciled = mergeLatestMessages(canonical, payload.messages)
                    canonical.clear()
                    canonical.addAll(reconciled)

                    val canonicalTurns = payload.messages.mapNotNullTo(mutableSetOf(), UiMessage::turnId)
                    val completedTurns = payload.completedTurnIds.orEmpty().toSet()
                    canonicalCompletedTurnIds.addAll(completedTurns)
                    (canonicalTurns + completedTurns).forEach(optimistic::remove)

                    // active/incomplete canonical 可能落后于已接收的 WebSocket delta，不能仅因 canonical
                    // 中出现同 turn assistant 就丢弃 transient。只有服务端明确完成的 turn 才能淘汰。
                    streamFold.markCompletedTurns(completedTurns)
                    payload.workspaceScope?.let { canonicalScope ->
                        mutableState.update { current ->
                            current.copy(workspaceScope = canonicalScope.normalized())
                        }
                    }

                    publishLocked(
                        loading = false,
                        loadingOlder = false,
                        hasMore = payload.page?.hasMoreBefore == true,
                        before = payload.page?.beforeCursor,
                        activeTurnId = payload.activeTurnId,
                        userMessageOffset = payload.page?.userMessageOffset ?: 0,
                    )
                    applied = true
                }
            }
            if (!applied) return@withLock CanonicalRefreshResult.Ignored

            // activeTurnId 非空表示快照只是运行中截面。断线恢复协调器必须保留 dirty 并重试，
            // 直到 HTTP 明确返回 settled；普通手动/TurnEnd 刷新则可以忽略此返回值。
            if (payload.activeTurnId == null) {
                CanonicalRefreshResult.AppliedSettled
            } else {
                CanonicalRefreshResult.AppliedActive
            }
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
                    error = error.message ?: "thread_load_failed",
                )
            }
            CanonicalRefreshResult.Failed
        }
    }

    private suspend fun refreshComposerCatalogs(sessionEpoch: Long, generation: Long): Boolean {
        val result = composerCatalogLoader.load()
        if (!isCurrentAuthenticatedSession(sessionEpoch, generation)) return false

        // 目录允许部分成功：失败分区保留上一轮可用值，避免一个非关键接口抖动清空整个 Composer。
        mutableState.update { current ->
            current.copy(
                slashCommands = result.slashCommands ?: current.slashCommands,
                skills = result.skills ?: current.skills,
                cliApps = result.cliApps ?: current.cliApps,
                mcpPresets = result.mcpPresets ?: current.mcpPresets,
            )
        }
        result.settings?.let { settings ->
            modelSettings = settings
            publishModelSelection()
        }
        return result.complete && isCurrentAuthenticatedSession(sessionEpoch, generation)
    }

    private suspend fun refreshModelSettings(sessionEpoch: Long, generation: Long): Boolean {
        val payload = composerCatalogLoader.loadModelSettings() ?: return false
        if (!isCurrentAuthenticatedSession(sessionEpoch, generation)) return false
        modelSettings = payload
        publishModelSelection()
        return true
    }

    /**
     * 同时核对认证 epoch 与请求代次：epoch 防止账号间串写，generation 防止同一账号的
     * 失败重试或显式 reset 后旧响应覆盖更新的一轮目录结果。
     */
    private fun isCurrentAuthenticatedSession(sessionEpoch: Long, generation: Long): Boolean =
        authenticatedSessionEpoch == sessionEpoch && composerCatalogGeneration.get() == generation

    /** Runtime 模型变化后只允许在当前认证代次内重读 Settings，未登录时不发起请求。 */
    private fun refreshModelSettingsForCurrentSession() {
        val session = synchronized(authenticatedLifecycleLock) {
            authenticatedSessionEpoch?.let { epoch -> epoch to composerCatalogGeneration.get() }
        } ?: return
        scope.launch { refreshModelSettings(session.first, session.second) }
    }

    private fun handleEvent(event: InboundEvent) {
        when (event) {
            is InboundEvent.RuntimeModelUpdated -> {
                runtimeModelName = event.modelName.trim().takeIf(String::isNotEmpty)
                publishModelSelection()
                refreshModelSettingsForCurrentSession()
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

        val sideChannelTurnId = synchronized(timelineWriterLock) {
            event.turnIdOrNull()?.takeIf { turnId -> turnId in sideChannelTurnIds }
        }
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
                synchronized(timelineWriterLock) {
                    streamFold.fold(event)
                    publishLocked()
                }
            }

            is InboundEvent.TurnEnd -> {
                synchronized(timelineWriterLock) {
                    streamFold.fold(event)
                    event.turnId?.let(optimistic::remove)
                    val current = mutableState.value
                    mutableState.value = current.copy(
                        activeTurnId = current.activeTurnId.takeUnless { activeTurnId ->
                            event.turnId == null || activeTurnId == event.turnId
                        },
                        stoppingTurnId = current.stoppingTurnId.takeUnless { stoppingTurnId ->
                            event.turnId == null || stoppingTurnId == event.turnId
                        },
                        sendingTurnIds = event.turnId?.let { current.sendingTurnIds - it }
                            ?: current.sendingTurnIds,
                    )
                    publishLocked()
                }
                scope.launch {
                    delay(250)
                    refreshCanonical()
                }
            }

            is InboundEvent.GoalStatus -> {
                if (!event.status.equals("idle", ignoreCase = true)) return
                synchronized(timelineWriterLock) {
                    val current = mutableState.value
                    // 服务端明确把 goal_status:idle 定义为终态兜底：取消或直接运行可能不会再发送
                    // turn_end。这里必须按 turnId 收敛 active/stopping，否则 `/stop` 已成功但按钮会
                    // 永久停在 loading；旧协议缺少 turnId 时，只能清理当前会话唯一的活动 turn。
                    mutableState.value = current.copy(
                        activeTurnId = current.activeTurnId.takeUnless { activeTurnId ->
                            event.turnId == null || activeTurnId == event.turnId
                        },
                        stoppingTurnId = current.stoppingTurnId.takeUnless { stoppingTurnId ->
                            event.turnId == null || stoppingTurnId == event.turnId
                        },
                        sendingTurnIds = event.turnId?.let { current.sendingTurnIds - it }
                            ?: current.sendingTurnIds,
                    )
                }
                // idle 只负责结束本地运行态，最终消息仍以规范 HTTP 快照为准；延迟一点等待服务端
                // 完成落盘，并与 turn_end 路径共用串行 refresh，避免并发快照互相覆盖。
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
                    // SessionUpdated 与会话切换可能在不同线程交错，只允许修改最新状态的 workspace 字段。
                    mutableState.update { current ->
                        current.copy(workspaceScope = canonicalScope.normalized())
                    }
                }
            }

            else -> Unit
        }
    }

    private fun handleSideChannelEvent(event: InboundEvent, turnId: String) {
        when (event) {
            is InboundEvent.Message -> {
                synchronized(timelineWriterLock) {
                    streamFold.fold(event)
                    sideChannelTurnIds.remove(turnId)
                    mutableState.update { current ->
                        current.copy(sendingTurnIds = current.sendingTurnIds - turnId)
                    }
                    publishLocked()
                }
                scope.launch {
                    delay(250)
                    refreshCanonical()
                }
            }

            is InboundEvent.TurnEnd -> {
                synchronized(timelineWriterLock) {
                    sideChannelTurnIds.remove(turnId)
                    optimistic.remove(turnId)
                    mutableState.update { current ->
                        current.copy(sendingTurnIds = current.sendingTurnIds - turnId)
                    }
                    publishLocked()
                }
                scope.launch {
                    delay(250)
                    refreshCanonical()
                }
            }

            is InboundEvent.Error -> {
                synchronized(timelineWriterLock) {
                    sideChannelTurnIds.remove(turnId)
                    val current = mutableState.value
                    val isAwaitingAcceptance = turnId in current.sendingTurnIds
                    mutableState.value = current.copy(
                        // optimistic 必须保留到 awaitAcceptance 决定 FAILED 或删除；先删除会让普通
                        // side-channel 失败丢失可重试气泡，也会破坏 acceptance 失败的统一回滚路径。
                        error =
                            if (isAwaitingAcceptance) {
                                current.error
                            } else {
                                listOfNotNull(event.detail, event.reason)
                                    .joinToString(": ")
                                    .ifBlank { "turn_rejected" }
                            },
                    )
                    publishLocked()
                }
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
    ) = synchronized(timelineWriterLock) {
        publishLocked(loading, loadingOlder, hasMore, before, activeTurnId, userMessageOffset)
    }

    /** 调用方必须持有 [timelineWriterLock]，保证集合快照与 StateFlow 写入不可被另一事件插队。 */
    private fun publishLocked(
        loading: Boolean = mutableState.value.loading,
        loadingOlder: Boolean = mutableState.value.loadingOlder,
        hasMore: Boolean = mutableState.value.hasMoreBefore,
        before: String? = mutableState.value.beforeCursor,
        activeTurnId: String? = mutableState.value.activeTurnId,
        userMessageOffset: Int = mutableState.value.userMessageOffset,
    ) {
        val projection = projectChatTimeline(
            ChatTimelineInput(
                canonical = canonical.toList(),
                optimistic = optimistic.values.map(LocalOutgoingMessage::message),
                failedMessageIds = optimistic.values
                    .filter { outgoing -> outgoing.deliveryState == LocalDeliveryState.FAILED }
                    .mapTo(mutableSetOf()) { outgoing -> outgoing.message.id },
                transient = streamFold.snapshot(),
                canonicalCompletedTurnIds = canonicalCompletedTurnIds.toSet(),
            ),
        )
        mutableState.update { current ->
            reduceChatTimeline(
                current = current,
                projection = projection,
                metadata = ChatTimelineMetadata(
                    loading = loading,
                    loadingOlder = loadingOlder,
                    hasMoreBefore = hasMore,
                    beforeCursor = before,
                    activeTurnId = activeTurnId,
                    userMessageOffset = userMessageOffset,
                ),
                limits = limitsProvider.currentIngressLimits(),
            )
        }
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
    private fun rememberHandledAcceptanceFailureLocked(turnId: String) {
        locallyHandledAcceptanceFailures += turnId
        while (locallyHandledAcceptanceFailures.size > MAX_HANDLED_ACCEPTANCE_FAILURES) {
            val oldest = locallyHandledAcceptanceFailures.iterator()
            if (oldest.hasNext()) {
                oldest.next()
                oldest.remove()
            }
        }
    }

    private fun isHandledAcceptanceFailure(turnId: String): Boolean =
        synchronized(timelineWriterLock) {
            turnId in locallyHandledAcceptanceFailures
        }

    /** 调用方持有时间线写锁；会话切换必须一次性清空所有可变时间线结构。 */
    private fun clearTimelineLocked() {
        // 会话切换/退出同样属于 lineage reset；在途分页即使 sessionKey 恰好复用，也不能写入。
        canonicalLineageGeneration += 1L
        canonical.clear()
        canonicalCompletedTurnIds.clear()
        optimistic.clear()
        sideChannelTurnIds.clear()
        locallyHandledAcceptanceFailures.clear()
        streamFold.reset()
    }

    /** 调用方持有 [timelineWriterLock]，用于分页响应提交前的最终身份与 lineage 核验。 */
    private fun isCurrentOlderPageRequestLocked(request: OlderPageRequest): Boolean =
        mutableState.value.matchesSession(request.sessionKey, request.chatId) &&
            canonicalLineageGeneration == request.lineageGeneration

    /** 只比较会影响恢复请求有效性的状态，过滤每个入站事件产生的 lastActivityAt 高频更新。 */
    private fun isCurrentCanonicalRefreshTrigger(trigger: CanonicalRefreshTrigger): Boolean {
        val current = transport.state.value.toCanonicalRefreshTrigger()
        return current == trigger && trigger.canRefresh && mutableState.value.sessionKey != null
    }
}

private data class OlderPageRequest(
    val sessionKey: String,
    val chatId: String?,
    val beforeCursor: String,
    val lineageGeneration: Long,
)

private data class CanonicalRefreshTrigger(
    val needed: Boolean,
    val generation: Long,
    val status: TransportStatus,
    val networkAvailable: Boolean,
    val appForeground: Boolean,
) {
    /**
     * HTTP latest 是 WebSocket 事件缺口的独立恢复通道，不能再要求 WebSocket 已经 OPEN。
     * 只要应用在前台且网络可用就持续尝试规范快照，从而打破“WS 重连失败
     * → HTTP 永远不跑 → activeTurn 永远不清”的假死环。
     */
    val canRefresh: Boolean
        get() = needed && networkAvailable && appForeground
}

private fun TransportState.toCanonicalRefreshTrigger(): CanonicalRefreshTrigger =
    CanonicalRefreshTrigger(
        needed = needsCanonicalRefresh,
        generation = canonicalRefreshGeneration,
        status = status,
        networkAvailable = networkAvailable,
        appForeground = appForeground,
    )

private data class CanonicalRefreshResult(
    val applied: Boolean,
    val settled: Boolean,
) {
    companion object {
        val Ignored = CanonicalRefreshResult(applied = false, settled = false)
        val Failed = CanonicalRefreshResult(applied = false, settled = false)
        val AppliedActive = CanonicalRefreshResult(applied = true, settled = false)
        val AppliedSettled = CanonicalRefreshResult(applied = true, settled = true)
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

/** 停止请求携带完整会话身份，用于隔离切换会话、reset 与迟到失败回调。 */
private data class StopTurnRequest(
    val sessionKey: String?,
    val chatId: String,
    val turnId: String,
)

private const val MAX_HANDLED_ACCEPTANCE_FAILURES = 64
/** active 恢复期间的低频 HTTP 补偿间隔，避免 busy loop，同时不依赖缺失的 reconnect TurnEnd。 */
private const val CANONICAL_RECOVERY_RETRY_MS = 2_000L
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
