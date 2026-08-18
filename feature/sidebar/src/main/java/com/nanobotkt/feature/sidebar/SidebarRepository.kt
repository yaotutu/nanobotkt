package com.nanobotkt.feature.sidebar

import kotlinx.coroutines.CancellationException
import com.nanobotkt.core.model.ChatSummary
import com.nanobotkt.core.model.InboundEvent
import com.nanobotkt.core.model.SessionDeleteResult
import com.nanobotkt.core.model.SessionRow
import com.nanobotkt.core.model.SessionsPayload
import com.nanobotkt.core.model.SidebarStatePayload
import com.nanobotkt.core.network.GatewayApiClient
import com.nanobotkt.core.transport.NanobotTransport
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.async
import kotlinx.coroutines.delay
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.emptyFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import java.net.URLEncoder
import java.util.concurrent.atomic.AtomicLong
import javax.inject.Inject
import javax.inject.Singleton

interface SidebarRepository {
    val state: StateFlow<SidebarUiState>
    suspend fun refresh()
    suspend fun togglePinned(key: String)
    suspend fun toggleArchived(key: String)
    suspend fun renameSession(key: String, title: String)
    suspend fun renameProject(projectKey: String, title: String)
    suspend fun setShowArchived(show: Boolean)
    suspend fun toggleGroup(groupId: String)
    suspend fun deleteSession(key: String, deleteAutomations: Boolean = false): Boolean
    /** 更新当前正在阅读的会话；选中会话必须立即清除未读标记。 */
    fun markRead(chatId: String?)
    fun clearError()
    fun reset()
}

data class SidebarUiState(
    val sessions: List<ChatSummary> = emptyList(),
    val sidebar: SidebarStatePayload = SidebarStatePayload(),
    /** 是否已经完成至少一次会话列表加载，用于区分冷启动空列表和真实空列表。 */
    val loaded: Boolean = false,
    val loading: Boolean = false,
    val refreshing: Boolean = false,
    val pendingKeys: Set<String> = emptySet(),
    /** 当前正在执行 agent turn 的 chatId，用于列表展示运行中 spinner。 */
    val runningChatIds: Set<String> = emptySet(),
    /** 非当前会话在 turn 结束后留下的活动标记；进入该会话后立即清除。 */
    val unreadChatIds: Set<String> = emptySet(),
    val error: String? = null,
)

@Singleton
class DefaultSidebarRepository private constructor(
    private val api: GatewayApiClient,
    activityEvents: Flow<InboundEvent>,
    @Suppress("UNUSED_PARAMETER") collectorMarker: Unit,
) : SidebarRepository {
    @Inject
    constructor(api: GatewayApiClient, transport: NanobotTransport) : this(api, transport.events, Unit)

    /** 测试可注入可控 Flow；默认空 Flow 保持纯 HTTP 测试不需要构造 WebSocket。 */
    internal constructor(
        api: GatewayApiClient,
        activityEvents: Flow<InboundEvent> = emptyFlow(),
    ) : this(api, activityEvents, Unit)

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val mutationMutex = Mutex()
    /** 只在 reset 时递增，用于隔离退出前已经发出的 mutation/delete 请求。 */
    private val sessionGeneration = AtomicLong(0)
    /** 用于保证并发 refresh 只有最新一代可以提交结果。 */
    private val refreshGeneration = AtomicLong(0)
    /** 每次实时活动事件递增；HTTP refresh 不能用旧请求覆盖期间到达的新运行状态。 */
    private val activityGeneration = AtomicLong(0)
    /**
     * 保存实时事件尚未被 `/api/sessions` 规范快照确认的运行状态覆盖。
     *
     * `/api/sessions` 的 `run_started_at` 可能在 turn 开始或结束边界短暂滞后；一旦某个 chatId
     * 已收到 message_accepted/goal_status/turn_end，就先以事件状态为准，避免 HTTP 快照让
     * spinner 提前消失或在 turn_end 后重新出现。等 HTTP 与事件结论一致后立即移除覆盖，
     * 这样后续由其他客户端启动的新 turn 仍能通过 run_started_at 被发现，不会被旧事件永久压制。
     */
    private val pendingActivityOverrides = mutableMapOf<String, Boolean>()
    /** 让 reset 与“检查 generation 后写入状态”保持原子性，避免边界竞态污染新会话。 */
    private val stateLock = Any()
    private val mutableState = MutableStateFlow(SidebarUiState())
    override val state: StateFlow<SidebarUiState> = mutableState.asStateFlow()
    /** 仅在 [stateLock] 内读写，确保“选择会话并清未读”与 turn_end 判定不可交错。 */
    private var selectedChatId: String? = null
    private var activityRefreshJob: Job? = null

    init {
        scope.launch { activityEvents.collect(::handleActivityEvent) }
    }

    override suspend fun refresh() {
        refreshForSession(sessionGeneration.get())
    }

    private suspend fun refreshForSession(expectedSessionGeneration: Long) {
        // reset 之后，旧 mutation/delete 即使继续完成，也不能再启动新的 refresh。
        if (!isCurrentSession(expectedSessionGeneration)) return

        val requestGeneration = refreshGeneration.incrementAndGet()
        val requestActivityGeneration = activityGeneration.get()
        val previous = mutableState.value
        val hadData = previous.sessions.isNotEmpty()
        updateStateIfCurrent(expectedSessionGeneration) { current ->
            current.copy(
                loading = !hadData,
                refreshing = hadData,
                error = null,
            )
        }
        try {
            val (sessions, sidebar) = coroutineScope {
                val sessionsRequest = async { api.get<SessionsPayload>("/api/sessions") }
                val sidebarRequest = async { api.get<SidebarStatePayload>("/api/webui/sidebar-state") }
                sessionsRequest.await() to sidebarRequest.await()
            }
            // refresh 可以由启动、mutation 和手动下拉同时触发；旧请求返回后
            // 不能覆盖较新的会话列表和 sidebar 状态，只允许最后一代写入。
            updateStateIfCurrent(
                expectedSessionGeneration,
                canWrite = { requestGeneration == refreshGeneration.get() },
            ) { current ->
                current.copy(
                    sessions = sessions.sessions.map(SessionRow::toSummary),
                    // mutation pending 期间服务端 GET 可能仍是旧值，不能把已在本地生效的 optimistic
                    // Sidebar 快照顶回去；mutation 响应会随后提交服务端规范化结果。
                    sidebar = if (current.pendingKeys.isEmpty()) sidebar else current.sidebar,
                    // 未收到实时事件的会话可由 run_started_at 补齐冷启动状态；尚未被 HTTP 确认的
                    // 实时结论继续覆盖快照。两者一旦一致便释放覆盖，避免旧终态阻止未来外部 turn
                    // 通过 run_started_at 重新显示运行中状态。
                    runningChatIds = if (requestActivityGeneration == activityGeneration.get()) {
                        val summaries = sessions.sessions.map(SessionRow::toSummary)
                        val serverRunningIds = summaries
                            .filterTo(mutableSetOf()) { it.runStartedAt != null }
                            .mapTo(mutableSetOf(), ChatSummary::chatId)
                        val resolvedRunningIds = serverRunningIds.toMutableSet()
                        pendingActivityOverrides.forEach { (chatId, running) ->
                            if (running) resolvedRunningIds += chatId else resolvedRunningIds -= chatId
                        }
                        val convergedChatIds = pendingActivityOverrides
                            .filter { (chatId, running) -> (chatId in serverRunningIds) == running }
                            .keys
                        pendingActivityOverrides.keys.removeAll(convergedChatIds)
                        resolvedRunningIds
                    } else {
                        current.runningChatIds
                    },
                    loaded = true,
                    loading = false,
                    refreshing = false,
                    error = null,
                )
            }
        } catch (error: CancellationException) {
            updateStateIfCurrent(
                expectedSessionGeneration,
                canWrite = { requestGeneration == refreshGeneration.get() },
            ) { current ->
                // refresh 等待期间可能已经收到实时事件或 optimistic mutation；取消只能收起加载态，
                // 不能把整个状态恢复成请求前快照，否则会抹掉刚到达的 running/unread 更新。
                current.copy(loading = false, refreshing = false)
            }
            throw error
        } catch (error: Exception) {
            updateStateIfCurrent(
                expectedSessionGeneration,
                canWrite = { requestGeneration == refreshGeneration.get() },
            ) { current ->
                // 错误同样基于当前状态追加，保留请求期间到达的事件状态和用户操作。
                current.copy(
                    loading = false,
                    refreshing = false,
                    error = error.message ?: "sidebar_refresh_failed",
                )
            }
        }
    }

    override suspend fun togglePinned(key: String) = mutate(key) { current ->
        val pinned = current.pinnedKeys.toMutableSet().apply {
            if (!add(key)) remove(key)
        }
        current.copy(pinnedKeys = pinned.toList(), archivedKeys = current.archivedKeys - key)
    }

    override suspend fun toggleArchived(key: String) = mutate(key) { current ->
        val archived = current.archivedKeys.toMutableSet().apply {
            if (!add(key)) remove(key)
        }
        current.copy(
            archivedKeys = archived.toList(),
            pinnedKeys = if (key in archived) current.pinnedKeys - key else current.pinnedKeys,
        )
    }

    override suspend fun renameSession(key: String, title: String) = mutate(key) { current ->
        current.copy(titleOverrides = current.titleOverrides.toMutableMap().apply {
            title.trim().takeIf(String::isNotEmpty)?.let { put(key, it) } ?: remove(key)
        })
    }

    override suspend fun renameProject(projectKey: String, title: String) = mutate(projectKey) { current ->
        current.copy(projectNameOverrides = current.projectNameOverrides.toMutableMap().apply {
            title.trim().takeIf(String::isNotEmpty)?.let { put(projectKey, it) } ?: remove(projectKey)
        })
    }

    override suspend fun setShowArchived(show: Boolean) = mutate("view:archived") { current ->
        current.copy(view = current.view.copy(showArchived = show))
    }

    override suspend fun toggleGroup(groupId: String) = mutate(groupId) { current ->
        current.copy(collapsedGroups = current.collapsedGroups.toMutableMap().apply {
            put(groupId, !(get(groupId) ?: false))
        })
    }

    override suspend fun deleteSession(key: String, deleteAutomations: Boolean): Boolean = mutationMutex.withLock {
        val expectedSessionGeneration = sessionGeneration.get()
        if (!isCurrentSession(expectedSessionGeneration)) return@withLock false

        setPending(key, true, expectedSessionGeneration)
        try {
            if (!isCurrentSession(expectedSessionGeneration)) return@withLock false
            val result = api.request(
                path = "/api/sessions/${key.pathEncoded()}/delete",
                deserializer = SessionDeleteResult.serializer(),
                query = if (deleteAutomations) mapOf("delete_automations" to "true") else emptyMap(),
            )
            // reset 可能发生在 delete 请求等待期间；此时不能继续用旧账号的 sidebar
            // 状态发起 update，也不能触发 refresh 污染 reset 后的新状态。
            if (result.deleted && isCurrentSession(expectedSessionGeneration)) {
                val cleaned = mutableState.value.sidebar.withoutSession(key)
                if (!isCurrentSession(expectedSessionGeneration)) return@withLock result.deleted
                api.request(
                    path = "/api/webui/sidebar-state/update",
                    deserializer = SidebarStatePayload.serializer(),
                    query = mapOf("state" to api.encode(cleaned, SidebarStatePayload.serializer())),
                )
                if (isCurrentSession(expectedSessionGeneration)) {
                    refreshForSession(expectedSessionGeneration)
                }
            }
            result.deleted
        } catch (error: CancellationException) {
            // CancellationException 仍然必须透传，交给调用方决定如何结束协程。
            throw error
        } catch (error: Exception) {
            if (isCurrentSession(expectedSessionGeneration)) {
                updateStateIfCurrent(expectedSessionGeneration) { current ->
                    current.copy(error = error.message ?: "session_delete_failed")
                }
            }
            false
        } finally {
            // 旧请求的 finally 不能清除 reset 后新会话的 pending 标记。
            setPending(key, false, expectedSessionGeneration)
        }
    }

    override fun clearError() {
        mutableState.value = mutableState.value.copy(error = null)
    }

    override fun reset() {
        synchronized(stateLock) {
            // 让退出登录前已经发出的 refresh、mutation 和 delete 响应全部失效，
            // 并与下面的状态清空保持原子性，避免旧协程在 reset 边界写入新会话状态。
            sessionGeneration.incrementAndGet()
            refreshGeneration.incrementAndGet()
            activityGeneration.incrementAndGet()
            activityRefreshJob?.cancel()
            activityRefreshJob = null
            selectedChatId = null
            pendingActivityOverrides.clear()
            mutableState.value = SidebarUiState()
        }
    }

    private suspend fun mutate(key: String, transform: (SidebarStatePayload) -> SidebarStatePayload) {
        mutationMutex.withLock {
            val expectedSessionGeneration = sessionGeneration.get()
            if (!isCurrentSession(expectedSessionGeneration)) return@withLock

            val previousSidebar = mutableState.value.sidebar
            val proposed = transform(previousSidebar).copy(updatedAt = null)
            // 先把目标状态和 pending 一次性提交，列表立即移动/更新；用户能看到操作已受理，
            // 同一行也会被禁用，避免重复触发。网络失败时再按身份边界回滚。
            updateStateIfCurrent(expectedSessionGeneration) { current ->
                current.copy(
                    sidebar = proposed,
                    pendingKeys = current.pendingKeys + key,
                    error = null,
                )
            }
            try {
                val canonical = api.request(
                    path = "/api/webui/sidebar-state/update",
                    deserializer = SidebarStatePayload.serializer(),
                    query = mapOf("state" to api.encode(proposed, SidebarStatePayload.serializer())),
                )
                updateStateIfCurrent(expectedSessionGeneration) { current ->
                    current.copy(sidebar = canonical)
                }
            } catch (error: CancellationException) {
                // 协程取消也要回滚仍由本请求持有的 optimistic 快照，然后继续传播取消。
                rollbackSidebarMutation(expectedSessionGeneration, proposed, previousSidebar, error = null)
                throw error
            } catch (error: Exception) {
                rollbackSidebarMutation(
                    expectedSessionGeneration = expectedSessionGeneration,
                    proposed = proposed,
                    previous = previousSidebar,
                    error = error.message ?: "sidebar_update_failed",
                )
            } finally {
                // reset 后旧 mutation 的 finally 不能触碰新会话的 pending 状态。
                setPending(key, false, expectedSessionGeneration)
            }
        }
    }

    /**
     * 只在当前状态仍等于本请求的 optimistic 快照时回滚；若并发 refresh 已提交更权威的新状态，
     * 失败请求只能报告错误，不能用旧 previous 覆盖新快照。
     */
    private fun rollbackSidebarMutation(
        expectedSessionGeneration: Long,
        proposed: SidebarStatePayload,
        previous: SidebarStatePayload,
        error: String?,
    ) {
        updateStateIfCurrent(expectedSessionGeneration) { current ->
            current.copy(
                sidebar = if (current.sidebar == proposed) previous else current.sidebar,
                error = error ?: current.error,
            )
        }
    }

    override fun markRead(chatId: String?) {
        synchronized(stateLock) {
            selectedChatId = chatId
            if (chatId != null) {
                mutableState.value = mutableState.value.copy(
                    unreadChatIds = mutableState.value.unreadChatIds - chatId,
                )
            }
        }
    }

    /** 实时事件只维护列表级活动，不读取 Chat feature 的时间线状态。 */
    private fun handleActivityEvent(event: InboundEvent) {
        when (event) {
            is InboundEvent.MessageAccepted -> {
                updateActivity(event.chatId, running = true, completed = false)
                scheduleActivityRefresh()
            }
            is InboundEvent.GoalStatus -> {
                val running = !event.status.equals("idle", ignoreCase = true)
                updateActivity(event.chatId, running = running, completed = !running)
                if (!running) scheduleActivityRefresh()
            }
            is InboundEvent.TurnEnd -> {
                updateActivity(event.chatId, running = false, completed = true)
                scheduleActivityRefresh()
            }
            is InboundEvent.SessionUpdated -> {
                // session_updated 会广播给所有 WebUI 连接，是未 attach 会话更新列表顺序的关键事件。
                // thread 表示消息时间线已落盘，等价于一次终态活动；metadata 仅刷新标题/配置，
                // 不能错误地点亮未读或清除可能仍在运行的 spinner。
                if (event.scope.equals("thread", ignoreCase = true)) {
                    updateActivity(event.chatId, running = false, completed = true)
                }
                scheduleActivityRefresh()
            }
            else -> Unit
        }
    }

    /** running 与 unread 在同一锁内转换，避免用户选中和 turn_end 同时到达时留下幽灵未读点。 */
    private fun updateActivity(chatId: String, running: Boolean, completed: Boolean) {
        synchronized(stateLock) {
            activityGeneration.incrementAndGet()
            pendingActivityOverrides[chatId] = running
            val current = mutableState.value
            val runningIds = if (running) current.runningChatIds + chatId else current.runningChatIds - chatId
            val unreadIds = when {
                running -> current.unreadChatIds - chatId
                completed && selectedChatId != chatId -> current.unreadChatIds + chatId
                else -> current.unreadChatIds - chatId
            }
            mutableState.value = current.copy(
                runningChatIds = runningIds,
                unreadChatIds = unreadIds,
            )
        }
    }

    /** 合并同一 turn 的 goal_status/turn_end，避免为一次完成连续刷新会话列表。 */
    private fun scheduleActivityRefresh() {
        synchronized(stateLock) {
            activityRefreshJob?.cancel()
            val expectedSessionGeneration = sessionGeneration.get()
            activityRefreshJob = scope.launch {
                delay(200)
                refreshForSession(expectedSessionGeneration)
            }
        }
    }

    private fun isCurrentSession(expectedSessionGeneration: Long): Boolean =
        sessionGeneration.get() == expectedSessionGeneration

    private fun updateStateIfCurrent(
        expectedSessionGeneration: Long,
        canWrite: () -> Boolean = { true },
        transform: (SidebarUiState) -> SidebarUiState,
    ): Boolean = synchronized(stateLock) {
        if (sessionGeneration.get() != expectedSessionGeneration || !canWrite()) {
            false
        } else {
            mutableState.value = transform(mutableState.value)
            true
        }
    }

    private fun setPending(key: String, pending: Boolean, expectedSessionGeneration: Long) {
        updateStateIfCurrent(expectedSessionGeneration) { current ->
            current.copy(
                pendingKeys = current.pendingKeys.toMutableSet().apply {
                    if (pending) add(key) else remove(key)
                },
            )
        }
    }
}

private fun SessionRow.toSummary(): ChatSummary {
    val split = key.indexOf(':')
    val derivedChannel = if (split < 0) "" else key.substring(0, split)
    val derivedChatId = if (split < 0) key else key.substring(split + 1)
    return ChatSummary(
        key = key,
        channel = channel.takeIf { it.isNotBlank() && it != "webui" } ?: derivedChannel,
        chatId = chatId ?: derivedChatId,
        createdAt = createdAt,
        updatedAt = updatedAt,
        title = title,
        preview = preview.orEmpty(),
        modelPreset = modelPreset,
        runStartedAt = runStartedAt,
        workspaceScope = workspaceScope,
    )
}

private fun SidebarStatePayload.withoutSession(key: String) = copy(
    pinnedKeys = pinnedKeys - key,
    archivedKeys = archivedKeys - key,
    titleOverrides = titleOverrides - key,
    tagsByKey = tagsByKey - key,
)

private fun String.pathEncoded(): String = URLEncoder.encode(this, Charsets.UTF_8.name()).replace("+", "%20")
