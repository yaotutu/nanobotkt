package com.nanobotkt.feature.sidebar

import kotlinx.coroutines.CancellationException
import com.nanobotkt.core.model.ChatSummary
import com.nanobotkt.core.model.SessionDeleteResult
import com.nanobotkt.core.model.SessionRow
import com.nanobotkt.core.model.SessionsPayload
import com.nanobotkt.core.model.SidebarStatePayload
import com.nanobotkt.core.network.GatewayApiClient
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
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
    val error: String? = null,
)

@Singleton
class DefaultSidebarRepository @Inject constructor(
    private val api: GatewayApiClient,
) : SidebarRepository {
    private val mutationMutex = Mutex()
    /** 只在 reset 时递增，用于隔离退出前已经发出的 mutation/delete 请求。 */
    private val sessionGeneration = AtomicLong(0)
    /** 用于保证并发 refresh 只有最新一代可以提交结果。 */
    private val refreshGeneration = AtomicLong(0)
    /** 让 reset 与“检查 generation 后写入状态”保持原子性，避免边界竞态污染新会话。 */
    private val stateLock = Any()
    private val mutableState = MutableStateFlow(SidebarUiState())
    override val state: StateFlow<SidebarUiState> = mutableState.asStateFlow()

    override suspend fun refresh() {
        refreshForSession(sessionGeneration.get())
    }

    private suspend fun refreshForSession(expectedSessionGeneration: Long) {
        // reset 之后，旧 mutation/delete 即使继续完成，也不能再启动新的 refresh。
        if (!isCurrentSession(expectedSessionGeneration)) return

        val requestGeneration = refreshGeneration.incrementAndGet()
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
                    sidebar = sidebar,
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
            ) { previous }
            throw error
        } catch (error: Exception) {
            updateStateIfCurrent(
                expectedSessionGeneration,
                canWrite = { requestGeneration == refreshGeneration.get() },
            ) {
                previous.copy(
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
            mutableState.value = SidebarUiState()
        }
    }

    private suspend fun mutate(key: String, transform: (SidebarStatePayload) -> SidebarStatePayload) {
        mutationMutex.withLock {
            val expectedSessionGeneration = sessionGeneration.get()
            if (!isCurrentSession(expectedSessionGeneration)) return@withLock

            setPending(key, true, expectedSessionGeneration)
            try {
                if (!isCurrentSession(expectedSessionGeneration)) return@withLock
                val proposed = transform(mutableState.value.sidebar).copy(updatedAt = null)
                api.request(
                    path = "/api/webui/sidebar-state/update",
                    deserializer = SidebarStatePayload.serializer(),
                    query = mapOf("state" to api.encode(proposed, SidebarStatePayload.serializer())),
                )
                if (isCurrentSession(expectedSessionGeneration)) {
                    refreshForSession(expectedSessionGeneration)
                }
            } catch (error: CancellationException) {
                // CancellationException 仍然必须透传，不能被普通失败处理吞掉。
                throw error
            } catch (error: Exception) {
                if (isCurrentSession(expectedSessionGeneration)) {
                    updateStateIfCurrent(expectedSessionGeneration) { current ->
                        current.copy(error = error.message ?: "sidebar_update_failed")
                    }
                }
            } finally {
                // reset 后旧 mutation 的 finally 不能触碰新会话的 pending 状态。
                setPending(key, false, expectedSessionGeneration)
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
