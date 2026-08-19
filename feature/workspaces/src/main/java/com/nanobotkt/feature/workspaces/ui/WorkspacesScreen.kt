package com.nanobotkt.feature.workspaces.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.ArrowBack
import androidx.compose.material.icons.rounded.Refresh
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.nanobotkt.core.designsystem.NanobotErrorState
import com.nanobotkt.core.designsystem.NanobotSectionHeader
import com.nanobotkt.core.designsystem.NanobotStatusLabel
import com.nanobotkt.core.designsystem.NanobotStatusTone
import com.nanobotkt.core.designsystem.NanobotSummarySurface
import com.nanobotkt.core.model.DefaultAccessMode

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun WorkspacesScreen(
    onBack: () -> Unit,
    viewModel: WorkspacesViewModel = hiltViewModel(),
) {
    val state by viewModel.state.collectAsStateWithLifecycle()
    val payload = state.payload
    var selectedMode by rememberSaveable(payload?.defaultAccessMode) {
        mutableStateOf(payload?.defaultAccessMode ?: DefaultAccessMode.DEFAULT)
    }
    val serverMode = payload?.defaultAccessMode
    val dirty = serverMode != null && selectedMode != serverMode

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("Workspaces") },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(Icons.AutoMirrored.Rounded.ArrowBack, contentDescription = "Back")
                    }
                },
                actions = {
                    IconButton(onClick = viewModel::refresh) {
                        Icon(Icons.Rounded.Refresh, contentDescription = "Refresh")
                    }
                },
            )
        },
    ) { padding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding)
                .padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            if (state.loading && payload == null) {
                CircularProgressIndicator()
            }
            state.error?.let { message ->
                NanobotErrorState(
                    title = "Unable to load workspace",
                    message = message,
                    retryLabel = "Retry",
                    onRetry = viewModel::refresh,
                )
            }
            payload?.let { workspace ->
                NanobotSectionHeader(text = "Default workspace")
                NanobotSummarySurface {
                    Text(
                        workspace.defaultScope.projectName ?: workspace.defaultScope.projectPath,
                        style = MaterialTheme.typography.titleMedium,
                    )
                    Text(
                        workspace.defaultScope.projectPath,
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                    NanobotStatusLabel(
                        label = "Scope: ${workspace.defaultScope.accessMode.wireLabel()}",
                        tone = NanobotStatusTone.Active,
                    )
                    Text(
                        "Restrict to workspace: " +
                            (workspace.defaultScope.restrictToWorkspace ?: false),
                        style = MaterialTheme.typography.bodyMedium,
                    )
                    workspace.defaultScope.sandboxStatus?.let {
                        Text(
                            it.summary,
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                }

                NanobotSectionHeader(text = "Default access mode")
                Text(
                    "This setting applies to new workspace scopes. It does not change the current chat session.",
                    style = MaterialTheme.typography.bodySmall,
                )
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    FilterChip(
                        selected = selectedMode == DefaultAccessMode.DEFAULT,
                        onClick = { selectedMode = DefaultAccessMode.DEFAULT },
                        label = { Text("Restricted") },
                    )
                    FilterChip(
                        selected = selectedMode == DefaultAccessMode.FULL,
                        onClick = { selectedMode = DefaultAccessMode.FULL },
                        enabled = workspace.controls.canUseFullAccess,
                        label = { Text("Full access") },
                    )
                }
                if (!workspace.controls.canUseFullAccess) {
                    Text(
                        "Full access is unavailable for this gateway.",
                        style = MaterialTheme.typography.bodySmall,
                    )
                }
                Button(
                    onClick = { viewModel.updateDefaultAccessMode(selectedMode) },
                    enabled = dirty && !state.loading,
                    modifier = Modifier.fillMaxWidth(),
                ) {
                    Text(if (state.loading) "Saving…" else "Save default access mode")
                }

                Spacer(Modifier.height(4.dp))
                NanobotSectionHeader(text = "Controls")
                Text("Change project: ${workspace.controls.canChangeProject}")
                Text("Full access: ${workspace.controls.canUseFullAccess}")
            }
        }
    }
}

/** 只负责把当前 scope 枚举转换为用户可读文本，避免把默认模式和会话模式混淆。 */
private fun com.nanobotkt.core.model.WorkspaceAccessMode.wireLabel(): String = when (this) {
    com.nanobotkt.core.model.WorkspaceAccessMode.RESTRICTED -> "restricted"
    com.nanobotkt.core.model.WorkspaceAccessMode.FULL -> "full"
}
