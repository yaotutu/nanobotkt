package com.nanobotkt.feature.automations

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.ArrowBack
import androidx.compose.material.icons.rounded.Bolt
import androidx.compose.material.icons.rounded.Delete
import androidx.compose.material.icons.rounded.Edit
import androidx.compose.material.icons.rounded.Pause
import androidx.compose.material.icons.rounded.PlayArrow
import androidx.compose.material.icons.rounded.Refresh
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ElevatedCard
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.ListItem
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
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

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun AutomationsScreen(
    onBack: () -> Unit,
    viewModel: AutomationsViewModel = hiltViewModel(),
) {
    val state by viewModel.state.collectAsStateWithLifecycle()
    var filter by rememberSaveable { mutableStateOf("all") }
    var rename by remember { mutableStateOf<Pair<String, String>?>(null) }
    val jobs = state.payload?.jobs.orEmpty().filter {
        when (filter) {
            "active" -> it.enabled
            "paused" -> !it.enabled
            "attention" -> it.state.lastStatus == "error"
            else -> true
        }
    }
    val filters = listOf(
        "all" to "All",
        "active" to "Active",
        "paused" to "Paused",
        "attention" to "Needs attention",
    )

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("Automations") },
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
        Column(Modifier.fillMaxSize().padding(padding)) {
            LazyRow(
                contentPadding = PaddingValues(horizontal = 12.dp, vertical = 8.dp),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                items(filters, key = { it.first }) { (value, label) ->
                    FilterChip(
                        selected = filter == value,
                        onClick = { filter = value },
                        label = { Text(label, maxLines = 1) },
                    )
                }
            }
            state.error?.let {
                Text(it, Modifier.padding(horizontal = 16.dp), color = androidx.compose.material3.MaterialTheme.colorScheme.error)
            }
            LazyColumn(
                contentPadding = PaddingValues(12.dp),
                verticalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                if (state.loading && state.payload == null) item { CircularProgressIndicator() }
                items(jobs, key = { it.id }) { job ->
                    ElevatedCard(Modifier.fillMaxWidth()) {
                        ListItem(
                            headlineContent = { Text(job.name) },
                            supportingContent = { Text("${job.schedule.kind} · ${job.state.lastStatus ?: "not run"}") },
                            trailingContent = {
                                Row {
                                    IconButton(
                                        enabled = job.id !in state.pending,
                                        onClick = { viewModel.action(if (job.enabled) "disable" else "enable", job.id) },
                                    ) {
                                        Icon(if (job.enabled) Icons.Rounded.Pause else Icons.Rounded.PlayArrow, contentDescription = null)
                                    }
                                    IconButton(onClick = { viewModel.action("run", job.id) }) {
                                        Icon(Icons.Rounded.Bolt, contentDescription = null)
                                    }
                                    IconButton(onClick = { rename = job.id to job.name }) {
                                        Icon(Icons.Rounded.Edit, contentDescription = null)
                                    }
                                    if (job.protected != true) {
                                        IconButton(onClick = { viewModel.action("delete", job.id) }) {
                                            Icon(Icons.Rounded.Delete, contentDescription = null)
                                        }
                                    }
                                }
                            },
                        )
                    }
                }
            }
        }
    }

    rename?.let { (id, initial) ->
        var value by remember(initial) { mutableStateOf(initial) }
        AlertDialog(
            onDismissRequest = { rename = null },
            title = { Text("Rename automation") },
            text = { OutlinedTextField(value, onValueChange = { value = it }) },
            confirmButton = {
                TextButton(
                    onClick = {
                        viewModel.rename(id, value.trim())
                        rename = null
                    },
                    enabled = value.isNotBlank(),
                ) { Text("Save") }
            },
            dismissButton = { TextButton(onClick = { rename = null }) { Text("Cancel") } },
        )
    }
}

