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
    private val mutableState = MutableStateFlow(SidebarUiState())
    override val state: StateFlow<SidebarUiState> = mutableState.asStateFlow()

    override suspend fun refresh() {
        val previous = mutableState.value
        val hadData = previous.sessions.isNotEmpty()
        mutableState.value = previous.copy(
            loading = !hadData,
            refreshing = hadData,
            error = null,
        )
        try {
            val (sessions, sidebar) = coroutineScope {
                val sessionsRequest = async { api.get<SessionsPayload>("/api/sessions") }
                val sidebarRequest = async { api.get<SidebarStatePayload>("/api/webui/sidebar-state") }
                sessionsRequest.await() to sidebarRequest.await()
            }
            mutableState.value = mutableState.value.copy(
                sessions = sessions.sessions.map(SessionRow::toSummary),
                sidebar = sidebar,
                loading = false,
                refreshing = false,
                error = null,
            )
        } catch (error: CancellationException) {
            mutableState.value = previous
            throw error
        } catch (error: Exception) {
            mutableState.value = previous.copy(
                loading = false,
                refreshing = false,
                error = error.message ?: "sidebar_refresh_failed",
            )
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
        setPending(key, true)
        try {
            val result = api.request(
                path = "/api/sessions/${key.pathEncoded()}/delete",
                deserializer = SessionDeleteResult.serializer(),
                query = if (deleteAutomations) mapOf("delete_automations" to "true") else emptyMap(),
            )
            if (result.deleted) {
                val cleaned = mutableState.value.sidebar.withoutSession(key)
                api.request(
                    path = "/api/webui/sidebar-state/update",
                    deserializer = SidebarStatePayload.serializer(),
                    query = mapOf("state" to api.encode(cleaned, SidebarStatePayload.serializer())),
                )
                refresh()
            }
            result.deleted
        } catch (error: CancellationException) {
            throw error
        } catch (error: Exception) {
            mutableState.value = mutableState.value.copy(error = error.message ?: "session_delete_failed")
            false
        } finally {
            setPending(key, false)
        }
    }

    override fun clearError() {
        mutableState.value = mutableState.value.copy(error = null)
    }

    override fun reset() {
        mutableState.value = SidebarUiState()
    }

    private suspend fun mutate(key: String, transform: (SidebarStatePayload) -> SidebarStatePayload) {
        mutationMutex.withLock {
            setPending(key, true)
            try {
                val proposed = transform(mutableState.value.sidebar).copy(updatedAt = null)
                api.request(
                    path = "/api/webui/sidebar-state/update",
                    deserializer = SidebarStatePayload.serializer(),
                    query = mapOf("state" to api.encode(proposed, SidebarStatePayload.serializer())),
                )
                refresh()
            } catch (error: CancellationException) {
                throw error
            } catch (error: Exception) {
                mutableState.value = mutableState.value.copy(error = error.message ?: "sidebar_update_failed")
            } finally {
                setPending(key, false)
            }
        }
    }

    private fun setPending(key: String, pending: Boolean) {
        mutableState.value = mutableState.value.copy(
            pendingKeys = mutableState.value.pendingKeys.toMutableSet().apply {
                if (pending) add(key) else remove(key)
            },
        )
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
