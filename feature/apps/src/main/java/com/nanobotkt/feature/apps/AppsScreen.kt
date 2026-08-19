package com.nanobotkt.feature.apps

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.ArrowBack
import androidx.compose.material.icons.rounded.Refresh
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Checkbox
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.ListItem
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.PrimaryTabRow
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Tab
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.unit.dp
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.nanobotkt.core.designsystem.NanobotEmptyState
import com.nanobotkt.core.designsystem.NanobotErrorState
import com.nanobotkt.core.designsystem.NanobotRowDivider
import com.nanobotkt.core.model.McpPresetInfo

@OptIn(androidx.compose.material3.ExperimentalMaterial3Api::class)
@Composable
fun AppsScreen(onBack: () -> Unit, viewModel: AppsViewModel = hiltViewModel()) {
    val state by viewModel.state.collectAsStateWithLifecycle()
    val mcpFieldValues by viewModel.mcpFieldValues.collectAsStateWithLifecycle()
    val mcpToolSelections by viewModel.mcpToolSelections.collectAsStateWithLifecycle()
    var tab by rememberSaveable { mutableIntStateOf(0) }
    var query by rememberSaveable { mutableStateOf("") }
    var importDialog by remember { mutableStateOf(false) }
    var cursorImportDialog by remember { mutableStateOf(false) }
    var customDialog by remember { mutableStateOf(false) }
    // 搜索结果在列表组合前一次性派生，保证空状态与实际渲染使用完全相同的过滤条件。
    val filteredCliApps = state.cli?.apps.orEmpty().filter { app ->
        query.isBlank() ||
            app.displayName.contains(query, true) ||
            app.description.contains(query, true)
    }
    val filteredMcpPresets = state.mcp?.presets.orEmpty().filter { preset ->
        query.isBlank() ||
            preset.displayName.contains(query, true) ||
            preset.description.contains(query, true)
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("Apps & integrations") },
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
        },
    ) { padding ->
        Column(Modifier.fillMaxSize().padding(padding)) {
            PrimaryTabRow(tab) {
                Tab(
                    selected = tab == 0,
                    onClick = { tab = 0 },
                    text = { Text("CLI apps (${state.cli?.installedCount ?: 0})") },
                )
                Tab(
                    selected = tab == 1,
                    onClick = { tab = 1 },
                    text = { Text("MCP (${state.mcp?.installedCount ?: 0})") },
                )
            }
            OutlinedTextField(
                value = query,
                onValueChange = { query = it },
                modifier = Modifier.fillMaxWidth().padding(12.dp),
                label = { Text("Search") },
                singleLine = true,
            )
            state.error?.let {
                NanobotErrorState(
                    title = "Unable to load apps",
                    message = it,
                    retryLabel = "Retry",
                    onRetry = viewModel::refresh,
                )
            }
            if (state.loading && state.cli == null) {
                Box(Modifier.padding(16.dp)) { CircularProgressIndicator() }
            }
            if (tab == 0) {
                LazyColumn(
                    contentPadding = PaddingValues(12.dp),
                    verticalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    items(
                        filteredCliApps,
                        key = { it.name },
                    ) { app ->
                        val pending = "cli:${app.name}" in state.pending
                        Column(Modifier.fillMaxWidth()) {
                            ListItem(
                                headlineContent = { Text(app.displayName) },
                                supportingContent = { Text("${app.description}\n${app.status}") },
                                trailingContent = {
                                    Column {
                                        if (app.installed) {
                                            // 只有后端 manifest 明确提供可更新的包策略时才显示 Update，
                                            // 避免给 bundled 或 unsupported app 暴露一个无效按钮。
                                            if (viewModel.canUpdateCli(app)) {
                                                TextButton(
                                                    enabled = !pending,
                                                    onClick = { viewModel.cli("update", app.name) },
                                                ) { Text("Update") }
                                            }
                                            TextButton(
                                                enabled = !pending,
                                                onClick = { viewModel.cli("test", app.name) },
                                            ) { Text("Test") }
                                            TextButton(
                                                enabled = !pending,
                                                onClick = { viewModel.cli("uninstall", app.name) },
                                            ) { Text("Remove") }
                                        } else {
                                            Button(
                                                enabled = app.installSupported && !pending,
                                                onClick = { viewModel.cli("install", app.name) },
                                            ) { Text("Install") }
                                        }
                                    }
                                },
                            )
                            NanobotRowDivider()
                        }
                    }
                    if (state.cli != null && filteredCliApps.isEmpty() && state.error == null) {
                        item {
                            NanobotEmptyState(
                                title = if (query.isBlank()) "No CLI apps available" else "No matching CLI apps",
                            )
                        }
                    }
                }
            } else {
                LazyColumn(
                    contentPadding = PaddingValues(12.dp),
                    verticalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    item {
                        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            OutlinedButton(onClick = { customDialog = true }) {
                                Text("Add custom MCP")
                            }
                            OutlinedButton(onClick = { importDialog = true }) {
                                Text("Import MCP config")
                            }
                            OutlinedButton(onClick = { cursorImportDialog = true }) {
                                Text("Import Cursor MCP")
                            }
                        }
                    }
                    items(
                        filteredMcpPresets,
                        key = { it.name },
                    ) { preset ->
                        val values = mcpFieldValues[preset.name].orEmpty()
                        val missingRequiredFields = preset.requiredFields.any { field ->
                            field.required &&
                                !field.configured &&
                                values[field.name].orEmpty().trim().isEmpty()
                        }
                        val showFieldInputs = preset.requiredFields.any { !it.configured }
                        val pending = "mcp:${preset.name}" in state.pending

                        Column(Modifier.fillMaxWidth()) {
                            Column {
                                ListItem(
                                    headlineContent = { Text(preset.displayName) },
                                    supportingContent = {
                                        val tools = preset.enabledTools.orEmpty()
                                            .takeUnless { it.isEmpty() }
                                            ?.joinToString(", ")
                                        Text(
                                            listOf(
                                                preset.description,
                                                preset.connectionSummary.ifBlank { preset.status },
                                                tools?.let { "Enabled tools: $it" },
                                            ).filterNotNull().filter { it.isNotBlank() }.joinToString("\n"),
                                        )
                                    },
                                    trailingContent = {
                                        Column {
                                            if (preset.installed) {
                                                TextButton(
                                                    enabled = !pending && !missingRequiredFields,
                                                    onClick = {
                                                        // 点击 Test 时把当前字段快照传入 VM，由 VM 再做一次必填校验。
                                                        viewModel.mcp("test", preset.name, values)
                                                    },
                                                ) { Text("Test") }
                                                TextButton(
                                                    enabled = !pending,
                                                    onClick = { viewModel.mcp("remove", preset.name) },
                                                ) { Text("Remove") }
                                            } else {
                                                Button(
                                                    enabled =
                                                        preset.installSupported &&
                                                            !pending &&
                                                            !missingRequiredFields,
                                                    onClick = {
                                                        // Enable 与 Test 使用同一套 requiredFields 值和校验路径。
                                                        viewModel.mcp("enable", preset.name, values)
                                                    },
                                                ) { Text("Enable") }
                                            }
                                        }
                                    },
                                )
                                if (showFieldInputs) {
                                    Column(
                                        modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp),
                                        verticalArrangement = Arrangement.spacedBy(8.dp),
                                    ) {
                                        preset.requiredFields.forEach { field ->
                                            OutlinedTextField(
                                                value = values[field.name].orEmpty(),
                                                onValueChange = {
                                                    viewModel.setMcpFieldValue(
                                                        preset.name,
                                                        field.name,
                                                        it,
                                                    )
                                                },
                                                modifier = Modifier.fillMaxWidth(),
                                                label = {
                                                    Text(
                                                        if (field.required && !field.configured) {
                                                            "${field.label} *"
                                                        } else {
                                                            field.label
                                                        },
                                                    )
                                                },
                                                placeholder = field.placeholder?.let { hint ->
                                                    { Text(hint) }
                                                },
                                                supportingText = if (field.required && !field.configured) {
                                                    { Text("Required") }
                                                } else {
                                                    null
                                                },
                                                singleLine = true,
                                                visualTransformation = if (field.secret) {
                                                    PasswordVisualTransformation()
                                                } else {
                                                    VisualTransformation.None
                                                },
                                            )
                                        }
                                    }
                                }
                                McpToolsEditor(
                                    preset = preset,
                                    pending = pending,
                                    selections = mcpToolSelections,
                                    viewModel = viewModel,
                                )
                                NanobotRowDivider(modifier = Modifier.padding(top = 4.dp))
                            }
                        }
                    }
                    if (state.mcp != null && filteredMcpPresets.isEmpty() && state.error == null) {
                        item {
                            NanobotEmptyState(
                                title = if (query.isBlank()) "No MCP integrations available" else "No matching MCP integrations",
                            )
                        }
                    }
                }
            }
        }
    }
    if (importDialog) {
        McpImportDialog(
            title = "Import MCP configuration",
            onDismiss = { importDialog = false },
            onImport = viewModel::importConfig,
        )
    }
    if (cursorImportDialog) {
        McpImportDialog(
            title = "Import Cursor MCP configuration",
            onDismiss = { cursorImportDialog = false },
            onImport = viewModel::importCursorConfig,
        )
    }
    if (customDialog) {
        CustomMcpDialog(
            viewModel = viewModel,
            onDismiss = { customDialog = false },
        )
    }
}

/**
 * 统一承载两种 MCP 导入表单，但由调用方注入不同的服务端 endpoint。
 * 这样普通导入与 Cursor 导入在 UI 上保持一致，同时不会错误复用协议路径。
 */
@Composable
private fun McpImportDialog(
    title: String,
    onDismiss: () -> Unit,
    onImport: (String) -> Unit,
) {
    var config by rememberSaveable { mutableStateOf("") }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(title) },
        text = {
            OutlinedTextField(
                value = config,
                onValueChange = { config = it },
                minLines = 6,
                label = { Text("JSON configuration") },
            )
        },
        confirmButton = {
            TextButton(
                enabled = config.isNotBlank(),
                onClick = {
                    onImport(config)
                    onDismiss()
                },
            ) { Text("Import") }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) { Text("Cancel") }
        },
    )
}

@Composable
private fun McpToolsEditor(
    preset: McpPresetInfo,
    pending: Boolean,
    selections: Map<String, Set<String>>,
    viewModel: AppsViewModel,
) {
    val tools = preset.toolNames.orEmpty()
    if (!preset.installed || tools.isEmpty()) return

    Column(
        modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp),
        verticalArrangement = Arrangement.spacedBy(2.dp),
    ) {
        Text("Tools", style = MaterialTheme.typography.titleSmall)
        tools.forEach { tool ->
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Checkbox(
                    checked = tool in (selections[preset.name] ?: viewModel.selectedMcpTools(preset)),
                    onCheckedChange = { selected ->
                        viewModel.setMcpToolSelected(preset, tool, selected)
                    },
                    enabled = !pending,
                )
                Text(tool)
            }
        }
        TextButton(
            enabled = !pending,
            onClick = { viewModel.updateTools(preset.name) },
        ) {
            Text("Save tools")
        }
    }
}

@Composable
private fun CustomMcpDialog(viewModel: AppsViewModel, onDismiss: () -> Unit) {
    var name by remember { mutableStateOf("") }
    var transport by remember { mutableStateOf("stdio") }
    var command by remember { mutableStateOf("") }
    var url by remember { mutableStateOf("") }
    var args by remember { mutableStateOf("") }
    var env by remember { mutableStateOf("") }
    var cwd by remember { mutableStateOf("") }
    var headers by remember { mutableStateOf("") }
    var toolTimeout by remember { mutableStateOf("") }
    var enabledTools by remember { mutableStateOf("*") }

    val values = mapOf(
        "name" to name,
        "transport" to transport,
        "command" to command,
        "url" to url,
        "args" to args,
        "env" to env,
        "cwd" to cwd,
        "headers" to headers,
        "tool_timeout" to toolTimeout,
        "enabled_tools" to enabledTools,
    )
    val validationError = viewModel.customConfigError(values)

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Add custom MCP") },
        text = {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .heightIn(max = 520.dp)
                    .verticalScroll(rememberScrollState()),
                verticalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                Text(
                    "Use stdio for a local command, or sse/streamableHttp for a remote URL.",
                    style = MaterialTheme.typography.bodySmall,
                )
                OutlinedTextField(
                    value = name,
                    onValueChange = { name = it },
                    modifier = Modifier.fillMaxWidth(),
                    label = { Text("Name *") },
                    singleLine = true,
                )
                OutlinedTextField(
                    value = transport,
                    onValueChange = { transport = it },
                    modifier = Modifier.fillMaxWidth(),
                    label = { Text("Transport *") },
                    supportingText = { Text("stdio, sse, or streamableHttp") },
                    singleLine = true,
                )
                OutlinedTextField(
                    value = command,
                    onValueChange = { command = it },
                    modifier = Modifier.fillMaxWidth(),
                    label = { Text("Command") },
                    placeholder = { Text("npx") },
                    singleLine = true,
                )
                OutlinedTextField(
                    value = url,
                    onValueChange = { url = it },
                    modifier = Modifier.fillMaxWidth(),
                    label = { Text("URL") },
                    singleLine = true,
                )
                OutlinedTextField(
                    value = args,
                    onValueChange = { args = it },
                    modifier = Modifier.fillMaxWidth(),
                    label = { Text("Args JSON array") },
                    placeholder = { Text("[\"-y\", \"@modelcontextprotocol/server\"]") },
                    singleLine = false,
                )
                OutlinedTextField(
                    value = env,
                    onValueChange = { env = it },
                    modifier = Modifier.fillMaxWidth(),
                    label = { Text("Environment JSON object") },
                    placeholder = { Text("{\"API_KEY\": \"...\"}") },
                    singleLine = false,
                )
                OutlinedTextField(
                    value = cwd,
                    onValueChange = { cwd = it },
                    modifier = Modifier.fillMaxWidth(),
                    label = { Text("Working directory") },
                    singleLine = true,
                )
                OutlinedTextField(
                    value = headers,
                    onValueChange = { headers = it },
                    modifier = Modifier.fillMaxWidth(),
                    label = { Text("Headers JSON object") },
                    singleLine = false,
                )
                OutlinedTextField(
                    value = toolTimeout,
                    onValueChange = { toolTimeout = it },
                    modifier = Modifier.fillMaxWidth(),
                    label = { Text("Tool timeout (seconds)") },
                    singleLine = true,
                )
                OutlinedTextField(
                    value = enabledTools,
                    onValueChange = { enabledTools = it },
                    modifier = Modifier.fillMaxWidth(),
                    label = { Text("Enabled tools") },
                    supportingText = { Text("* or a JSON string array") },
                    singleLine = true,
                )
                validationError?.let { Text(it, color = MaterialTheme.colorScheme.error) }
            }
        },
        confirmButton = {
            TextButton(
                enabled = validationError == null,
                onClick = {
                    viewModel.saveCustom(values)
                    onDismiss()
                },
            ) { Text("Save") }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) { Text("Cancel") }
        },
    )
}
