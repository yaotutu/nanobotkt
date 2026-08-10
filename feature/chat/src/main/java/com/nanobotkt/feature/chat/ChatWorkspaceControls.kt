package com.nanobotkt.feature.chat

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.ExpandMore
import androidx.compose.material.icons.rounded.Folder
import androidx.compose.material.icons.rounded.WarningAmber
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.AssistChip
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.nanobotkt.core.model.WorkspaceAccessMode
import com.nanobotkt.core.model.WorkspaceControls
import com.nanobotkt.core.model.WorkspaceScope
import com.nanobotkt.core.model.WorkspacesPayload
import com.nanobotkt.core.model.isAbsoluteWorkspacePath
import com.nanobotkt.core.model.projectNameFromPath
import com.nanobotkt.core.model.selectedProjectScope
import com.nanobotkt.core.model.shortWorkspacePath
import com.nanobotkt.core.model.withAccessMode

/** 会话工作区范围选择控件；仅表达现有 WorkspaceScope 状态转换。 */
@Composable
internal fun WorkspaceControls(
    scope: WorkspaceScope?,
    catalog: WorkspacesPayload?,
    error: String?,
    isHero: Boolean,
    disabled: Boolean,
    onChange: (WorkspaceScope) -> Unit,
    modifier: Modifier = Modifier,
    compact: Boolean = false,
    heroStyle: Boolean = false,
) {
    val defaultScope = catalog?.defaultScope
    val controls = catalog?.controls
    val selectedProject = selectedProjectScope(scope, defaultScope)
    var projectDialogOpen by remember { mutableStateOf(false) }
    var accessDialogOpen by remember { mutableStateOf(false) }
    var pathDraft by remember { mutableStateOf("") }
    var pathError by remember { mutableStateOf<String?>(null) }

    if (scope == null && defaultScope == null) return

    Row(
        modifier = modifier,
        horizontalArrangement = Arrangement.spacedBy(8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        if (isHero && !compact && defaultScope != null && controls?.canChangeProject != false) {
            val label =
                selectedProject?.projectName
                    ?: selectedProject?.projectPath?.let(::projectNameFromPath)
                    ?: stringResource(R.string.workspace_project_placeholder)
            AssistChip(
                onClick = {
                    pathDraft = selectedProject?.projectPath.orEmpty()
                    pathError = null
                    projectDialogOpen = true
                },
                enabled = !disabled,
                label = { Text(label, maxLines = 1, overflow = TextOverflow.Ellipsis) },
                leadingIcon = {
                    Icon(Icons.Rounded.Folder, contentDescription = null, Modifier.size(18.dp))
                },
                trailingIcon = {
                    Icon(Icons.Rounded.ExpandMore, contentDescription = null, Modifier.size(18.dp))
                },
                modifier = Modifier.weight(1f).semantics { contentDescription = label },
            )
        }
        val activeScope = scope ?: defaultScope
        if (activeScope != null) {
            val full = activeScope.accessMode == WorkspaceAccessMode.FULL
            val accessLabel =
                stringResource(
                    if (full) R.string.workspace_access_full_short
                    else R.string.workspace_access_default_short
                )
            if (compact) {
                val controlColor =
                    if (heroStyle) {
                        MaterialTheme.colorScheme.surfaceContainerHigh
                    } else {
                        MaterialTheme.colorScheme.surface
                    }
                // FULL 权限仍然保留警示语义，但使用主题 tertiary 角色，不重新引入旧橙色。
                val accentColor = MaterialTheme.colorScheme.tertiary
                val labelColor =
                    if (heroStyle && full) {
                        accentColor
                    } else {
                        MaterialTheme.colorScheme.onSurface
                    }
                Surface(
                    onClick = { accessDialogOpen = true },
                    enabled = !disabled,
                    shape = CircleShape,
                    color = controlColor,
                    tonalElevation = if (heroStyle) 2.dp else 1.dp,
                    shadowElevation = 0.dp,
                    modifier =
                        Modifier.then(if (heroStyle) Modifier.fillMaxSize() else Modifier)
                            .semantics { contentDescription = accessLabel },
                ) {
                    Row(
                        modifier =
                            if (heroStyle) {
                                Modifier.fillMaxSize().padding(horizontal = 4.dp)
                            } else {
                                Modifier.padding(horizontal = 7.dp, vertical = 7.dp)
                            },
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(3.dp),
                    ) {
                        if (full) {
                            Icon(
                                Icons.Rounded.WarningAmber,
                                contentDescription = null,
                                modifier = Modifier.size(14.dp),
                                tint =
                                    if (heroStyle) accentColor
                                    else MaterialTheme.colorScheme.tertiary,
                            )
                        }
                        Text(
                            text = if (heroStyle) "$accessLabel …" else accessLabel,
                            maxLines = 1,
                            overflow = TextOverflow.Clip,
                            style =
                                if (heroStyle) {
                                    MaterialTheme.typography.labelMedium.copy(
                                        fontWeight = FontWeight.Medium
                                    )
                                } else {
                                    MaterialTheme.typography.labelMedium
                                },
                            color = labelColor,
                        )
                        Icon(
                            Icons.Rounded.ExpandMore,
                            contentDescription = null,
                            modifier = Modifier.size(12.dp),
                            tint =
                                if (heroStyle) labelColor else MaterialTheme.colorScheme.onSurface,
                        )
                    }
                }
            } else {
                AssistChip(
                    onClick = { accessDialogOpen = true },
                    enabled = !disabled,
                    label = { Text(accessLabel) },
                    leadingIcon = {
                        if (full)
                            Icon(
                                Icons.Rounded.WarningAmber,
                                contentDescription = null,
                                modifier = Modifier.size(18.dp),
                                tint = MaterialTheme.colorScheme.tertiary,
                            )
                    },
                    trailingIcon = {
                        Icon(
                            Icons.Rounded.ExpandMore,
                            contentDescription = null,
                            Modifier.size(18.dp),
                        )
                    },
                    modifier = Modifier.semantics { contentDescription = accessLabel },
                )
            }
        }
    }

    if (error != null) {
        Text(
            stringResource(R.string.workspace_scope_rejected),
            color = MaterialTheme.colorScheme.error,
            style = MaterialTheme.typography.labelMedium,
        )
    }

    if (projectDialogOpen && defaultScope != null) {
        fun applyPath(path: String, projectName: String? = null) {
            val trimmed = path.trim()
            if (!isAbsoluteWorkspacePath(trimmed)) {
                pathError = "absolute_path_required"
                return
            }
            val base = scope ?: defaultScope
            onChange(
                base.copy(
                    projectPath = trimmed,
                    projectName = projectName ?: projectNameFromPath(trimmed),
                    restrictToWorkspace = base.accessMode == WorkspaceAccessMode.RESTRICTED,
                )
            )
            pathError = null
            projectDialogOpen = false
        }
        AlertDialog(
            onDismissRequest = { projectDialogOpen = false },
            icon = { Icon(Icons.Rounded.Folder, contentDescription = null) },
            title = { Text(stringResource(R.string.workspace_project_title)) },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                    Surface(
                        onClick = { applyPath(defaultScope.projectPath, defaultScope.projectName) },
                        shape = MaterialTheme.shapes.medium,
                        color = MaterialTheme.colorScheme.surfaceContainerHigh,
                        modifier = Modifier.fillMaxWidth(),
                    ) {
                        Column(
                            Modifier.padding(12.dp),
                            verticalArrangement = Arrangement.spacedBy(2.dp),
                        ) {
                            Text(
                                stringResource(R.string.workspace_default_project),
                                style = MaterialTheme.typography.labelLarge,
                            )
                            Text(
                                shortWorkspacePath(defaultScope.projectPath),
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis,
                            )
                        }
                    }
                    OutlinedTextField(
                        value = pathDraft,
                        onValueChange = {
                            pathDraft = it
                            pathError = null
                        },
                        modifier = Modifier.fillMaxWidth(),
                        enabled = !disabled,
                        singleLine = true,
                        label = { Text(stringResource(R.string.workspace_manual_path)) },
                        placeholder = { Text(stringResource(R.string.workspace_path_example)) },
                        isError = pathError != null,
                        supportingText =
                            pathError?.let {
                                { Text(stringResource(R.string.workspace_absolute_path_required)) }
                            },
                        keyboardOptions = KeyboardOptions(imeAction = ImeAction.Done),
                        keyboardActions = KeyboardActions(onDone = { applyPath(pathDraft) }),
                    )
                }
            },
            confirmButton = {
                TextButton(
                    enabled = !disabled && pathDraft.isNotBlank(),
                    onClick = { applyPath(pathDraft) },
                ) {
                    Text(stringResource(R.string.workspace_use_path))
                }
            },
            dismissButton = {
                TextButton(onClick = { projectDialogOpen = false }) {
                    Text(stringResource(R.string.cancel))
                }
            },
        )
    }

    val activeScope = scope ?: defaultScope
    if (accessDialogOpen && activeScope != null) {
        WorkspaceAccessDialog(
            scope = activeScope,
            controls = controls,
            disabled = disabled,
            onChange = onChange,
            onDismiss = { accessDialogOpen = false },
        )
    }
}

@Composable
internal fun WorkspaceAccessDialog(
    scope: WorkspaceScope,
    controls: WorkspaceControls?,
    disabled: Boolean,
    onChange: (WorkspaceScope) -> Unit,
    onDismiss: () -> Unit,
) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(R.string.workspace_access_title)) },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                Surface(
                    onClick = {
                        onChange(scope.withAccessMode(WorkspaceAccessMode.RESTRICTED))
                        onDismiss()
                    },
                    modifier = Modifier.fillMaxWidth(),
                    enabled = !disabled,
                    color =
                        if (scope.accessMode == WorkspaceAccessMode.RESTRICTED) {
                            MaterialTheme.colorScheme.secondaryContainer
                        } else {
                            MaterialTheme.colorScheme.surface
                        },
                    shape = MaterialTheme.shapes.medium,
                ) {
                    Column(Modifier.padding(12.dp)) {
                        Text(
                            stringResource(R.string.workspace_access_default),
                            style = MaterialTheme.typography.labelLarge,
                        )
                        Text(
                            stringResource(R.string.workspace_access_default_description),
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                }
                Surface(
                    onClick = {
                        if (controls?.canUseFullAccess != false) {
                            onChange(scope.withAccessMode(WorkspaceAccessMode.FULL))
                            onDismiss()
                        }
                    },
                    modifier = Modifier.fillMaxWidth(),
                    enabled = !disabled && controls?.canUseFullAccess != false,
                    color =
                        if (scope.accessMode == WorkspaceAccessMode.FULL) {
                            MaterialTheme.colorScheme.tertiaryContainer
                        } else {
                            MaterialTheme.colorScheme.surface
                        },
                    shape = MaterialTheme.shapes.medium,
                ) {
                    Column(Modifier.padding(12.dp)) {
                        Text(
                            stringResource(R.string.workspace_access_full),
                            style = MaterialTheme.typography.labelLarge,
                            color = MaterialTheme.colorScheme.tertiary,
                        )
                        Text(
                            stringResource(R.string.workspace_access_full_description),
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                }
            }
        },
        confirmButton = {
            TextButton(onClick = onDismiss) { Text(stringResource(R.string.cancel)) }
        },
    )
}
