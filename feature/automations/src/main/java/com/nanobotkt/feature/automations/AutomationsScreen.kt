package com.nanobotkt.feature.automations

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
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
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ElevatedCard
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.ListItem
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.nanobotkt.core.model.AutomationSchedule
import com.nanobotkt.core.model.AutomationUpdatePayload
import com.nanobotkt.core.model.SessionAutomationJob

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun AutomationsScreen(
    onBack: () -> Unit,
    viewModel: AutomationsViewModel = hiltViewModel(),
) {
    val state by viewModel.state.collectAsStateWithLifecycle()
    var filter by rememberSaveable { mutableStateOf("all") }
    var editJob by remember { mutableStateOf<SessionAutomationJob?>(null) }
    var deleteJob by remember { mutableStateOf<SessionAutomationJob?>(null) }
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

    // 页面离开后协程会自动取消，避免后台页面持续轮询；ViewModel 仍负责 action 后的短刷新。
    LaunchedEffect(Unit) {
        while (true) {
            kotlinx.coroutines.delay(5_000)
            viewModel.refresh()
        }
    }

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
                Text(
                    it,
                    Modifier.padding(horizontal = 16.dp),
                    color = MaterialTheme.colorScheme.error,
                )
            }
            LazyColumn(
                contentPadding = PaddingValues(12.dp),
                verticalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                if (state.loading && state.payload == null) item { CircularProgressIndicator() }
                items(jobs, key = { it.id }) { job ->
                    // 服务端 pending 可能来自另一客户端或后台调度；不能只看本地网络 action。
                    val pending = job.id in state.pending || job.state.pending == true
                    val isLocalTrigger = job.kind == "local" ||
                        job.payload.kind == "local" ||
                        job.schedule.kind == "local"
                    val canManage = job.protected != true
                    val canRun = canManage && job.origin != null && job.enabled && !isLocalTrigger && !pending
                    val canToggle = canManage && (job.enabled || job.origin != null) && !pending
                    ElevatedCard(Modifier.fillMaxWidth()) {
                        Column {
                            ListItem(
                                headlineContent = { Text(job.name) },
                                supportingContent = {
                                    Text("${job.schedule.kind} · ${job.state.lastStatus ?: "not run"}")
                                },
                                trailingContent = {
                                    Row {
                                        IconButton(
                                            enabled = canToggle,
                                            onClick = {
                                                viewModel.action(
                                                    if (job.enabled) "disable" else "enable",
                                                    job.id,
                                                )
                                            },
                                        ) {
                                            Icon(
                                                if (job.enabled) Icons.Rounded.Pause else Icons.Rounded.PlayArrow,
                                                contentDescription = null,
                                            )
                                        }
                                        if (!isLocalTrigger) {
                                            IconButton(
                                                enabled = canRun,
                                                onClick = { viewModel.action("run", job.id) },
                                            ) {
                                                Icon(Icons.Rounded.Bolt, contentDescription = null)
                                            }
                                        }
                                        if (canManage) {
                                            IconButton(
                                                enabled = !pending,
                                                onClick = { editJob = job },
                                            ) {
                                                Icon(Icons.Rounded.Edit, contentDescription = null)
                                            }
                                        }
                                        if (job.protected != true) {
                                            IconButton(
                                                enabled = !pending,
                                                onClick = { deleteJob = job },
                                            ) {
                                                Icon(Icons.Rounded.Delete, contentDescription = null)
                                            }
                                        }
                                    }
                                },
                            )
                            // 详情字段全部来自服务端快照，只读展示，避免客户端臆造运行结果。
                            Text("ID: ${job.id}")
                            Text("Kind: ${job.kind ?: "scheduled"}${if (job.protected == true) " · protected" else ""}")
                            Text("Schedule: ${formatAutomationSchedule(job.schedule)}")
                            job.payload.message.takeIf(String::isNotBlank)?.let {
                                Text("Message: $it")
                            }
                            job.payload.kind?.takeIf(String::isNotBlank)?.let {
                                Text("Payload kind: $it")
                            }
                            job.payload.command?.takeIf(String::isNotBlank)?.let {
                                Text("Command: $it")
                            }
                            job.state.nextRunAtMs?.let { Text("Next run: ${formatAutomationTime(it)}") }
                            job.state.pending?.let { Text("Pending: ${if (it) "yes" else "no"}") }
                            job.createdAtMs?.let { Text("Created: ${formatAutomationTime(it)}") }
                            job.updatedAtMs?.let { Text("Updated: ${formatAutomationTime(it)}") }
                            job.state.lastRunAtMs?.let { Text("Last run: ${formatAutomationTime(it)}") }
                            job.state.lastError?.takeIf(String::isNotBlank)?.let {
                                Text("Last error: $it", color = MaterialTheme.colorScheme.error)
                            }
                            job.origin?.let { origin ->
                                val originText = listOfNotNull(
                                    origin.channel.takeIf(String::isNotBlank)?.let { "channel=$it" },
                                    origin.title?.takeIf(String::isNotBlank)?.let { "title=$it" },
                                    origin.sessionKey?.takeIf(String::isNotBlank)?.let { "session=$it" },
                                    origin.chatId?.takeIf(String::isNotBlank)?.let { "chat=$it" },
                                    origin.preview?.takeIf(String::isNotBlank)?.let { "preview=$it" },
                                ).joinToString(" · ")
                                if (originText.isNotBlank()) Text("Origin: $originText")
                            }
                            job.trigger?.let { trigger ->
                                Text("Local trigger: ${trigger.id} · ${trigger.command}")
                            }
                            if (job.deleteAfterRun == true) Text("Delete after run")
                            job.state.runHistory.orEmpty().takeLast(3).forEach { entry ->
                                val details = listOfNotNull(
                                    entry.durationMs?.let { formatAutomationDuration(it) },
                                    entry.error?.takeIf(String::isNotBlank)?.let { "error=$it" },
                                ).joinToString(" · ")
                                Text(
                                    "History: ${entry.status} · ${formatAutomationTime(entry.runAtMs)}" +
                                        details.takeIf(String::isNotBlank)?.let { " · $it" }.orEmpty(),
                                    color = if (entry.status == "error") MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.onSurface,
                                )
                            }
                        }
                    }
                }
            }
        }
    }

    editJob?.let { job ->
        AutomationEditDialog(
            job = job,
            pending = job.id in state.pending || job.state.pending == true,
            onDismiss = { editJob = null },
            onSave = { values ->
                viewModel.update(job.id, values)
                editJob = null
            },
        )
    }

    deleteJob?.let { job ->
        AlertDialog(
            onDismissRequest = { deleteJob = null },
            title = { Text("Delete automation?") },
            text = { Text("This action cannot be undone: ${job.name}") },
            confirmButton = {
                Button(
                    enabled = job.id !in state.pending,
                    onClick = {
                        viewModel.action("delete", job.id)
                        deleteJob = null
                    },
                ) { Text("Delete") }
            },
            dismissButton = {
                TextButton(onClick = { deleteJob = null }) { Text("Cancel") }
            },
        )
    }
}

/**
 * 复用了网关已支持的三个 schedule 类型。输入保留为原始数值，只有点击 Save 时才构造 payload，
 * 因而非法输入不会被静默转换成错误的默认值。
 */
/** 将服务端毫秒时间转换成稳定、短的本地显示文本；失败时保留原始时间值。 */
internal fun formatAutomationTime(epochMs: Long): String =
    java.text.SimpleDateFormat("yyyy-MM-dd HH:mm", java.util.Locale.getDefault())
        .format(java.util.Date(epochMs))

/** 将毫秒间隔转换成用户可读文本，同时保留不足一秒的精度。 */
internal fun formatAutomationDuration(durationMs: Long): String = when {
    durationMs < 1_000L -> "${durationMs}ms"
    durationMs % 60_000L == 0L -> "${durationMs / 60_000L}m"
    durationMs % 1_000L == 0L -> "${durationMs / 1_000L}s"
    else -> "${durationMs}ms"
}

/** 使用服务端 schedule.kind 的真实 wire contract 生成详情文本。 */
internal fun formatAutomationSchedule(schedule: AutomationSchedule): String = when (schedule.kind) {
    "every" -> "Every ${schedule.everyMs?.let(::formatAutomationDuration) ?: "unknown interval"}"
    "cron" -> "Cron ${schedule.expr.orEmpty()}${schedule.tz?.takeIf(String::isNotBlank)?.let { " ($it)" }.orEmpty()}"
    "at" -> "At ${schedule.atMs?.let(::formatAutomationTime) ?: "unknown time"}"
    else -> schedule.kind
}

@Composable
private fun AutomationEditDialog(
    job: SessionAutomationJob,
    pending: Boolean,
    onDismiss: () -> Unit,
    onSave: (AutomationUpdatePayload) -> Unit,
) {
    var name by remember(job.id) { mutableStateOf(job.name) }
    var message by remember(job.id) { mutableStateOf(job.payload.message) }
    val localTrigger = job.kind == "local" || job.payload.kind == "local" || job.schedule.kind == "local"
    var scheduleKind by remember(job.id) {
        mutableStateOf(job.schedule.kind.takeIf { it in setOf("every", "cron", "at") } ?: "every")
    }
    var everyMs by remember(job.id) {
        mutableStateOf((job.schedule.everyMs ?: 3_600_000L).toString())
    }
    var cron by remember(job.id) { mutableStateOf(job.schedule.expr.orEmpty()) }
    var timezone by remember(job.id) { mutableStateOf(job.schedule.tz.orEmpty()) }
    var atMs by remember(job.id) {
        mutableStateOf((job.schedule.atMs ?: System.currentTimeMillis()).toString())
    }
    var validation by remember(job.id) { mutableStateOf<String?>(null) }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Edit automation") },
        text = {
            LazyColumn(
                modifier = Modifier.heightIn(max = 560.dp),
                verticalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                item {
                    OutlinedTextField(
                        value = name,
                        onValueChange = { name = it; validation = null },
                        label = { Text("Name") },
                        singleLine = true,
                        modifier = Modifier.fillMaxWidth(),
                    )
                }
                if (!localTrigger) {
                    item {
                        OutlinedTextField(
                            value = message,
                            onValueChange = { message = it; validation = null },
                            label = { Text("Message") },
                            modifier = Modifier.fillMaxWidth(),
                            minLines = 3,
                        )
                    }
                    item {
                        Text("Schedule type", style = MaterialTheme.typography.labelLarge)
                        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            listOf("every" to "Every", "cron" to "Cron", "at" to "Once").forEach { (kind, label) ->
                                FilterChip(
                                    selected = scheduleKind == kind,
                                    onClick = { scheduleKind = kind; validation = null },
                                    label = { Text(label) },
                                )
                            }
                        }
                    }
                    when (scheduleKind) {
                    "every" -> item {
                        OutlinedTextField(
                            value = everyMs,
                            onValueChange = { everyMs = it; validation = null },
                            label = { Text("Interval (milliseconds)") },
                            singleLine = true,
                            modifier = Modifier.fillMaxWidth(),
                        )
                    }
                    "cron" -> {
                        item {
                            OutlinedTextField(
                                value = cron,
                                onValueChange = { cron = it; validation = null },
                                label = { Text("Cron expression") },
                                singleLine = true,
                                modifier = Modifier.fillMaxWidth(),
                            )
                        }
                        item {
                            OutlinedTextField(
                                value = timezone,
                                onValueChange = { timezone = it },
                                label = { Text("Timezone (optional)") },
                                singleLine = true,
                                modifier = Modifier.fillMaxWidth(),
                            )
                        }
                    }
                    "at" -> item {
                        OutlinedTextField(
                            value = atMs,
                            onValueChange = { atMs = it; validation = null },
                            label = { Text("Run time (Unix milliseconds)") },
                            singleLine = true,
                            modifier = Modifier.fillMaxWidth(),
                        )
                    }
                }
                }
                validation?.let { messageText ->
                    item { Text(messageText, color = MaterialTheme.colorScheme.error) }
                }
            }
        },
        confirmButton = {
            TextButton(
                enabled = !pending,
                onClick = {
                    if (localTrigger) {
                        if (name.isBlank()) validation = "Name is required."
                        else onSave(AutomationUpdatePayload(name = name.trim()))
                        return@TextButton
                    }
                    val schedule = when (scheduleKind) {
                        "every" -> everyMs.toLongOrNull()?.takeIf { it > 0 }?.let {
                            AutomationSchedule(kind = "every", everyMs = it)
                        }
                        "cron" -> cron.trim().takeIf(String::isNotEmpty)?.let {
                            AutomationSchedule(
                                kind = "cron",
                                expr = it,
                                tz = timezone.trim().ifEmpty { null },
                            )
                        }
                        else -> atMs.toLongOrNull()?.takeIf { it > 0 }?.let {
                            AutomationSchedule(kind = "at", atMs = it)
                        }
                    }
                    when {
                        name.isBlank() -> validation = "Name is required."
                        message.isBlank() -> validation = "Message is required."
                        schedule == null -> validation = "Schedule values are invalid."
                        else -> onSave(
                            AutomationUpdatePayload(
                                name = name.trim(),
                                message = message.trim(),
                                schedule = schedule,
                            ),
                        )
                    }
                },
            ) { Text("Save") }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Cancel") } },
    )
}
