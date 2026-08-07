package com.nanobotkt.core.model

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

@Serializable
data class ChatSummary(
    val key: String,
    val channel: String,
    @SerialName("chatId") val chatId: String,
    @SerialName("createdAt") val createdAt: String? = null,
    @SerialName("updatedAt") val updatedAt: String? = null,
    val title: String? = null,
    val preview: String = "",
    @SerialName("modelPreset") val modelPreset: String? = null,
    @SerialName("runStartedAt") val runStartedAt: Long? = null,
    @SerialName("workspaceScope") val workspaceScope: WorkspaceScope? = null,
)

@Serializable enum class SidebarDensity { @SerialName("comfortable") COMFORTABLE, @SerialName("compact") COMPACT }
@Serializable enum class SidebarSortMode { @SerialName("updated_desc") UPDATED_DESC, @SerialName("created_desc") CREATED_DESC, @SerialName("title_asc") TITLE_ASC }

@Serializable
data class SidebarView(
    val density: SidebarDensity = SidebarDensity.COMFORTABLE,
    @SerialName("show_previews") val showPreviews: Boolean = true,
    @SerialName("show_timestamps") val showTimestamps: Boolean = true,
    @SerialName("show_archived") val showArchived: Boolean = false,
    val sort: SidebarSortMode = SidebarSortMode.UPDATED_DESC,
)

@Serializable
data class SidebarStatePayload(
    @SerialName("schema_version") val schemaVersion: Int = 1,
    @SerialName("pinned_keys") val pinnedKeys: List<String> = emptyList(),
    @SerialName("archived_keys") val archivedKeys: List<String> = emptyList(),
    @SerialName("title_overrides") val titleOverrides: Map<String, String> = emptyMap(),
    @SerialName("project_name_overrides") val projectNameOverrides: Map<String, String> = emptyMap(),
    @SerialName("tags_by_key") val tagsByKey: Map<String, List<String>> = emptyMap(),
    @SerialName("collapsed_groups") val collapsedGroups: Map<String, Boolean> = emptyMap(),
    val view: SidebarView = SidebarView(),
    @SerialName("updated_at") val updatedAt: String? = null,
)

@Serializable data class SessionsPayload(val sessions: List<SessionRow> = emptyList())
@Serializable
data class SessionRow(
    val key: String,
    val channel: String = "webui",
    @SerialName("chat_id") val chatId: String? = null,
    @SerialName("created_at") val createdAt: String? = null,
    @SerialName("updated_at") val updatedAt: String? = null,
    val title: String? = null,
    val preview: String? = null,
    @SerialName("model_preset") val modelPreset: String? = null,
    @SerialName("run_started_at") val runStartedAt: Long? = null,
    @SerialName("workspace_scope") val workspaceScope: WorkspaceScope? = null,
)

@Serializable data class SessionDeleteResult(val deleted: Boolean = false, @SerialName("blocked_by_automations") val blockedByAutomations: Boolean = false)
