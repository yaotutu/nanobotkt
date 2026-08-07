package com.nanobotkt.feature.apps

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.ArrowBack
import androidx.compose.material.icons.rounded.Refresh
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun AppsScreen(onBack: () -> Unit, viewModel: AppsViewModel = hiltViewModel()) {
    val state by viewModel.state.collectAsStateWithLifecycle()
    var tab by rememberSaveable { mutableIntStateOf(0) }
    var query by rememberSaveable { mutableStateOf("") }
    var importDialog by remember { mutableStateOf(false) }
    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("Apps & integrations") },
                navigationIcon = { IconButton(onClick = onBack) { Icon(Icons.AutoMirrored.Rounded.ArrowBack, null) } },
                actions = { IconButton(onClick = viewModel::refresh) { Icon(Icons.Rounded.Refresh, "Refresh") } },
            )
        },
    ) { padding ->
        Column(Modifier.fillMaxSize().padding(padding)) {
            PrimaryTabRow(tab) {
                Tab(tab == 0, { tab = 0 }, text = { Text("CLI apps (${state.cli?.installedCount ?: 0})") })
                Tab(tab == 1, { tab = 1 }, text = { Text("MCP (${state.mcp?.installedCount ?: 0})") })
            }
            OutlinedTextField(query, { query = it }, Modifier.fillMaxWidth().padding(12.dp), label = { Text("Search") }, singleLine = true)
            state.error?.let { Text(it, Modifier.padding(horizontal = 16.dp), color = MaterialTheme.colorScheme.error) }
            if (state.loading && state.cli == null) Box(Modifier.padding(16.dp)) { CircularProgressIndicator() }
            if (tab == 0) {
                LazyColumn(contentPadding = PaddingValues(12.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    items(state.cli?.apps.orEmpty().filter { query.isBlank() || it.displayName.contains(query, true) || it.description.contains(query, true) }, key = { it.name }) { app ->
                        ElevatedCard(Modifier.fillMaxWidth()) {
                            ListItem(
                                headlineContent = { Text(app.displayName) },
                                supportingContent = { Text("${app.description}\n${app.status}") },
                                trailingContent = {
                                    Column {
                                        if (app.installed) {
                                            TextButton(enabled = "cli:${app.name}" !in state.pending, onClick = { viewModel.cli("test", app.name) }) { Text("Test") }
                                            TextButton(enabled = "cli:${app.name}" !in state.pending, onClick = { viewModel.cli("uninstall", app.name) }) { Text("Remove") }
                                        } else {
                                            Button(enabled = app.installSupported && "cli:${app.name}" !in state.pending, onClick = { viewModel.cli("install", app.name) }) { Text("Install") }
                                        }
                                    }
                                },
                            )
                        }
                    }
                }
            } else {
                LazyColumn(contentPadding = PaddingValues(12.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    item { OutlinedButton(onClick = { importDialog = true }) { Text("Import MCP config") } }
                    items(state.mcp?.presets.orEmpty().filter { query.isBlank() || it.displayName.contains(query, true) || it.description.contains(query, true) }, key = { it.name }) { preset ->
                        ElevatedCard(Modifier.fillMaxWidth()) {
                            ListItem(
                                headlineContent = { Text(preset.displayName) },
                                supportingContent = { Text("${preset.description}\n${preset.connectionSummary.ifBlank { preset.status }}") },
                                trailingContent = {
                                    Column {
                                        if (preset.installed) {
                                            TextButton(enabled = "mcp:${preset.name}" !in state.pending, onClick = { viewModel.mcp("test", preset.name) }) { Text("Test") }
                                            TextButton(enabled = "mcp:${preset.name}" !in state.pending, onClick = { viewModel.mcp("remove", preset.name) }) { Text("Remove") }
                                        } else {
                                            Button(enabled = preset.installSupported && "mcp:${preset.name}" !in state.pending, onClick = { viewModel.mcp("enable", preset.name) }) { Text("Enable") }
                                        }
                                    }
                                },
                            )
                        }
                    }
                }
            }
        }
    }
    if (importDialog) {
        var config by remember { mutableStateOf("") }
        AlertDialog(
            onDismissRequest = { importDialog = false },
            title = { Text("Import MCP configuration") },
            text = { OutlinedTextField(config, { config = it }, minLines = 6, label = { Text("JSON configuration") }) },
            confirmButton = { TextButton(enabled = config.isNotBlank(), onClick = { viewModel.importConfig(config); importDialog = false }) { Text("Import") } },
            dismissButton = { TextButton(onClick = { importDialog = false }) { Text("Cancel") } },
        )
    }
}

