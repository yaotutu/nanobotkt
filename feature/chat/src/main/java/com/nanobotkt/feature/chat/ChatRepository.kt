package com.nanobotkt.feature.chat

import kotlinx.coroutines.CancellationException
import com.nanobotkt.core.model.AutomationsPayload
import com.nanobotkt.core.model.BootstrapSnapshotProvider
import com.nanobotkt.core.model.CliAppInfo
import com.nanobotkt.core.model.CliAppsPayload
import com.nanobotkt.core.model.InboundEvent
import com.nanobotkt.core.model.McpPresetInfo
import com.nanobotkt.core.model.ModelPresetInfo
import com.nanobotkt.core.model.McpPresetsPayload
import com.nanobotkt.core.model.IngressLimitsProvider
import com.nanobotkt.core.model.OutboundMedia
import com.nanobotkt.core.model.SlashCommand
import com.nanobotkt.core.model.SkillSummary
import com.nanobotkt.core.model.SettingsPayload
import com.nanobotkt.core.model.SkillsPayload
import com.nanobotkt.core.model.SlashCommandsPayload
import com.nanobotkt.core.model.UiCliAppAttachment
import com.nanobotkt.core.model.UiMcpPresetAttachment
import com.nanobotkt.core.model.WebUiIngressLimits
import com.nanobotkt.core.model.UiMessage
import com.nanobotkt.core.model.WebUiThreadPayload
import com.nanobotkt.core.model.WorkspaceScope
import com.nanobotkt.core.model.WorkspacesPayload
import com.nanobotkt.core.model.SessionAutomationJob
import com.nanobotkt.core.model.normalized
import com.nanobotkt.core.network.GatewayApiClient
import com.nanobotkt.core.network.GatewayException
import com.nanobotkt.core.transport.MessageSendResult
import com.nanobotkt.core.transport.NanobotTransport
import com.nanobotkt.core.transport.TransportError
import com.nanobotkt.core.transport.TransportStatus
import com.nanobotkt.feature.workspaces.WorkspacesRepository
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.launch
import java.net.URLEncoder
import javax.inject.Inject
import javax.inject.Singleton

interface ChatRepository {
    val state: StateFlow<ChatUiState>
    fun startNewTopic()
    fun openSession(sessionKey: String, chatId: String, workspaceScope: WorkspaceScope? = null, modelPreset: String? = null)
    suspend fun newChat(workspaceScope: WorkspaceScope? = null): String
    fun setWorkspaceScope(workspaceScope: WorkspaceScope)
    suspend fun changeModelPreset(name: String)
    fun refresh()
    fun loadOlder()
    suspend fun send(text: String, media: List<OutboundMedia> = emptyList(), quotedContext: String? = null, options: ChatSendOptions = ChatSendOptions())
    suspend fun retry(messageId: String)
    suspend fun fork(beforeUserIndex: Int, title: String? = null): String
    fun stop()
    suspend fun transcribeAudio(dataUrl: String, durationMs: Long): String
    suspend fun loadSessionAutomations(sessionKey: String): List<SessionAutomationJob>
    fun clearError()
}

data class ChatSendOptions(
    val sideChannel: Boolean = false,
    val continueActiveTurn: Boolean = false,
    val cliApps: List<UiCliAppAttachment> = emptyList(),
    val mcpPresets: List<UiMcpPresetAttachment> = emptyList(),
    val capabilityPayloadsResolved: Boolean = false,
    val workspaceScope: WorkspaceScope? = null,
)

data class ChatUiState(
    val sessionKey: String? = null,
    val chatId: String? = null,
    val messages: List<UiMessage> = emptyList(),
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
)

@Singleton
class DefaultChatRepository @Inject constructor(
    private val api: GatewayApiClient,
    private val transport: NanobotTransport,
    private val limitsProvider: IngressLimitsProvider,
    private val bootstrapProvider: BootstrapSnapshotProvider,
    private val workspacesRepository: WorkspacesRepository,
) : ChatRepository {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val mutableState = MutableStateFlow(ChatUiState())
    private val canonical = mutableListOf<UiMessage>()
    private val optimistic = linkedMapOf<String, UiMessage>()
    private val sideChannelTurnIds = mutableSetOf<String>()
    private val streamFold = ChatStreamFold()
    private var modelSettings: SettingsPayload? = null
    private var localModelSelection: LocalModelSelection? = null
    private var activeSessionModelPreset: String? = null
    private var runtimeModelName: String? = null
    private var turnModelName: String? = null
    override val state: StateFlow<ChatUiState> = mutableState.asStateFlow()

    init {
        scope.launch { refreshComposerCatalogs() }
        scope.launch {
            workspacesRepository.state.collectLatest { workspaceState ->
                val payload = workspaceState.payload
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
        scope.launch { workspacesRepository.refresh() }
        scope.launch { transport.events.collect(::handleEvent) }
        scope.launch { transport.errors.collect(::handleTransportError) }
        scope.launch {
            transport.state.collectLatest { transportState ->
                publishModelSelection()
                if (transportState.needsCanonicalRefresh && mutableState.value.sessionKey != null) {
                    refreshCanonical()
                    transport.clearCanonicalRefreshFlag()
                }
            }
        }
    }

    override fun startNewTopic() {
        val catalogs = mutableState.value
        activeSessionModelPreset = null
        turnModelName = null
        canonical.clear()
        optimistic.clear()
        sideChannelTurnIds.clear()
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
        if (mutableState.value.sessionKey == sessionKey && mutableState.value.chatId == chatId) {
            activeSessionModelPreset = modelPreset
            publishModelSelection()
            return
        }
        val catalogs = mutableState.value
        activeSessionModelPreset = modelPreset
        turnModelName = null
        canonical.clear()
        optimistic.clear()
        sideChannelTurnIds.clear()
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
        if (current.loadingOlder || !current.hasMoreBefore || current.beforeCursor == null) return
        mutableState.value = current.copy(loadingOlder = true)
        scope.launch {
            try {
                val page = fetchThread(sessionKey, before = current.beforeCursor, latest = false)
                if (mutableState.value.sessionKey != sessionKey) return@launch
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
                if (mutableState.value.sessionKey == sessionKey) {
                    mutableState.value = mutableState.value.copy(loadingOlder = false)
                }
                throw error
            } catch (error: Exception) {
                if (mutableState.value.sessionKey == sessionKey) {
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
    ) {
        if (mutableState.value.chatId == null) newChat(options.workspaceScope)
        val result = enqueueMessage(text, media, quotedContext, options) ?: error("message_empty")
        result.accepted.await()
    }

    override suspend fun retry(messageId: String) {
        check(mutableState.value.activeTurnId == null) { "turn_active" }
        val message = mutableState.value.messages.firstOrNull { it.id == messageId }
            ?: error("message_not_found")
        val result = enqueueMessage(message.content, emptyList(), null)
            ?: error("message_empty")
        result.accepted.await()
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
    ): MessageSendResult? {
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
        )
        optimistic[result.turnId] = local
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
        scope.launch {
            runCatching { result.accepted.await() }
                .onSuccess {
                    mutableState.value = mutableState.value.copy(
                        sendingTurnIds = mutableState.value.sendingTurnIds - result.turnId,
                    )
                }
                .onFailure { error ->
                    optimistic.remove(result.turnId)
                    sideChannelTurnIds.remove(result.turnId)
                    mutableState.value = mutableState.value.copy(
                        sendingTurnIds = mutableState.value.sendingTurnIds - result.turnId,
                        activeTurnId = mutableState.value.activeTurnId.takeUnless { it == result.turnId },
                        error = error.message ?: "message_send_failed",
                    )
                    publish()
                }
        }
        return result
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

    private suspend fun refreshCanonical() {
        val session = mutableState.value.sessionKey ?: return
        try {
            val payload = fetchThread(session, before = null, latest = true)
            if (mutableState.value.sessionKey != session) return
            if (payload == null) {
                canonical.clear()
                publish(
                    loading = false,
                    hasMore = false,
                    before = null,
                    activeTurnId = null,
                    userMessageOffset = 0,
                )
                return
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
        } catch (error: CancellationException) {
            if (mutableState.value.sessionKey == session) {
                mutableState.value = mutableState.value.copy(loading = false, loadingOlder = false)
            }
            throw error
        } catch (error: Exception) {
            if (mutableState.value.sessionKey == session) {
                mutableState.value = mutableState.value.copy(
                    loading = false,
                    loadingOlder = false,
                    error = error.message ?: "thread_refresh_failed",
                )
            }
        }
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
                path = "/api/skills",
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
                mutableState.value = mutableState.value.copy(
                    error = listOfNotNull(event.detail, event.reason)
                        .joinToString(": ")
                        .ifBlank { "turn_rejected" },
                )
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
                optimistic.remove(turnId)
                mutableState.value = mutableState.value.copy(
                    sendingTurnIds = mutableState.value.sendingTurnIds - turnId,
                    error = listOfNotNull(event.detail, event.reason)
                        .joinToString(": ")
                        .ifBlank { "turn_rejected" },
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
            addAll(optimistic.filterKeys { it !in canonicalTurns }.values)
            addAll(
                streamFold.snapshot().filterNot { transient ->
                    transient.turnId != null && transient.turnId in canonicalAssistantTurns
                },
            )
        }.sortedBy { it.createdAt }
        mutableState.value = mutableState.value.copy(
            messages = merged,
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
        if (error is TransportError.WorkspaceScopeRejected) {
            mutableState.value = mutableState.value.copy(error = "workspace_scope_rejected")
            scope.launch { workspacesRepository.refresh() }
        } else {
            mutableState.value = mutableState.value.copy(error = error.toString())
        }
    }
}

private fun canonicalAssistantTurnIds(messages: List<UiMessage>): Set<String> = messages
    .asSequence()
    .filter { it.role != "user" }
    .mapNotNull(UiMessage::turnId)
    .toSet()

private fun String.pathEncoded(): String = URLEncoder.encode(this, Charsets.UTF_8.name()).replace("+", "%20")
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
