package com.nanobotkt.core.model

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

@Serializable enum class WorkspaceAccessMode { @SerialName("restricted") RESTRICTED, @SerialName("full") FULL }
@Serializable enum class DefaultAccessMode { @SerialName("default") DEFAULT, @SerialName("full") FULL }

@Serializable
data class WorkspaceSandboxStatus(
    @SerialName("restrict_to_workspace") val restrictToWorkspace: Boolean,
    @SerialName("workspace_root") val workspaceRoot: String,
    val level: String,
    val enforced: Boolean,
    val provider: String,
    @SerialName("provider_label") val providerLabel: String,
    val summary: String,
)

@Serializable
data class WorkspaceScope(
    @SerialName("project_path") val projectPath: String,
    @SerialName("project_name") val projectName: String? = null,
    @SerialName("access_mode") val accessMode: WorkspaceAccessMode,
    @SerialName("restrict_to_workspace") val restrictToWorkspace: Boolean? = null,
    @SerialName("sandbox_status") val sandboxStatus: WorkspaceSandboxStatus? = null,
)

@Serializable
data class WorkspacesPayload(
    @SerialName("schema_version") val schemaVersion: Int,
    @SerialName("default_access_mode") val defaultAccessMode: DefaultAccessMode,
    @SerialName("default_scope") val defaultScope: WorkspaceScope,
    val controls: WorkspaceControls,
)

@Serializable data class WorkspaceControls(@SerialName("can_change_project") val canChangeProject: Boolean, @SerialName("can_use_full_access") val canUseFullAccess: Boolean)
