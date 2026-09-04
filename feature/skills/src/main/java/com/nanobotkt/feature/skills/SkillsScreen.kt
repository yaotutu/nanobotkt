package com.nanobotkt.feature.skills

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.ArrowBack
import androidx.compose.material.icons.rounded.Refresh
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.nanobotkt.core.designsystem.NanobotEmptyState
import com.nanobotkt.core.designsystem.NanobotErrorState
import com.nanobotkt.core.designsystem.NanobotNavigationRow
import com.nanobotkt.core.designsystem.NanobotRowDivider
import com.nanobotkt.core.designsystem.NanobotStatusLabel
import com.nanobotkt.core.designsystem.NanobotStatusTone

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SkillsScreen(onBack: () -> Unit, viewModel: SkillsViewModel = hiltViewModel()) {
    val state by viewModel.state.collectAsStateWithLifecycle()
    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("Skills") },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(Icons.AutoMirrored.Rounded.ArrowBack, null)
                    }
                },
                actions = {
                    IconButton(onClick = viewModel::refresh) {
                        Icon(Icons.Rounded.Refresh, "Refresh")
                    }
                },
            )
        }
    ) { p ->
        LazyColumn(
            Modifier.fillMaxSize().padding(p),
            // ListItem 自带标准内边距；与 Divider 配合时不再额外插入整行间距，
            // 避免出现“空白—分隔线—空白”的松散节奏。
            contentPadding = PaddingValues(vertical = 8.dp),
        ) {
            if (state.loading && state.skills == null) {
                item {
                    // 加载状态独立占据一行并居中，避免进度指示器贴在列表左上角。
                    Box(
                        modifier = Modifier.fillMaxWidth().padding(vertical = 32.dp),
                        contentAlignment = Alignment.Center,
                    ) {
                        CircularProgressIndicator()
                    }
                }
            }
            state.error?.let {
                item {
                    NanobotErrorState(
                        title = "Unable to load skills",
                        message = it,
                        retryLabel = "Retry",
                        onRetry = viewModel::refresh,
                    )
                }
            }
            items(state.skills?.skills.orEmpty(), key = { it.name }) { skill ->
                NanobotNavigationRow(
                    headline = skill.name,
                    supportingText = skill.description,
                    onClick = { viewModel.select(skill.name) },
                    trailingContent = {
                        NanobotStatusLabel(
                            label = if (skill.available) "Available" else "Unavailable",
                            tone = if (skill.available) NanobotStatusTone.Success else NanobotStatusTone.Warning,
                        )
                    },
                )
                NanobotRowDivider()
            }
            if (!state.loading && state.error == null && state.skills?.skills?.isEmpty() == true) {
                item { NanobotEmptyState(title = "No skills available") }
            }
        }
    }
    state.selected?.let { s ->
        AlertDialog(
            onDismissRequest = viewModel::closeDetail,
            title = { Text(s.name) },
            text = {
                LazyColumn(Modifier.heightIn(max = 520.dp)) {
                    item {
                        Text(s.description)
                        Spacer(Modifier.height(12.dp))
                        Text("Source: ${s.source}")
                        Text("Binaries: ${s.requirements.bins.joinToString()}")
                        Text("Environment: ${s.requirements.env.joinToString()}")
                        Spacer(Modifier.height(12.dp))
                        Text(s.rawMarkdown)
                    }
                }
            },
            confirmButton = { TextButton(onClick = viewModel::closeDetail) { Text("Close") } },
        )
    }
}
