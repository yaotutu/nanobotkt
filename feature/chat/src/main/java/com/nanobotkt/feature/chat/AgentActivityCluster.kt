package com.nanobotkt.feature.chat

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Build
import androidx.compose.material.icons.rounded.Check
import androidx.compose.material.icons.rounded.ErrorOutline
import androidx.compose.material.icons.rounded.ExpandLess
import androidx.compose.material.icons.rounded.ExpandMore
import androidx.compose.material.icons.rounded.Extension
import androidx.compose.material.icons.rounded.Psychology
import androidx.compose.material.icons.rounded.Terminal
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.nanobotkt.core.model.ToolProgressEvent
import com.nanobotkt.core.model.UiCliAppAttachment
import com.nanobotkt.core.model.UiFileEdit
import com.nanobotkt.core.model.UiMcpPresetAttachment
import kotlinx.coroutines.delay

private const val ACTIVITY_COMPLETION_HOLD_MS = 900L

private data class ActivityPresentation(
    val reasoning: List<String>,
    val tools: List<ToolProgressEvent>,
    val cliApps: List<UiCliAppAttachment>,
    val mcpPresets: List<UiMcpPresetAttachment>,
    val fileEdits: List<UiFileEdit>,
    val notes: List<String>,
) {
    val isEmpty: Boolean
        get() = reasoning.isEmpty() && tools.isEmpty() && cliApps.isEmpty() && mcpPresets.isEmpty() &&
            fileEdits.isEmpty() && notes.isEmpty()

    val itemCount: Int
        // 流式轮次可能先收到一个没有正文和结构化事件的 assistant 占位。状态栏仍应显示“1 项活动”，
        // 不能暴露“0 activities”这种实现细节；真实事件到达后会自动替换该占位计数。
        get() = if (isEmpty) 1 else reasoning.size + tools.size + cliApps.size + mcpPresets.size + fileEdits.size + notes.size

    val failed: Boolean
        get() =
            tools.any { it.error != null || it.phase.isFailurePhase() } ||
                fileEdits.any { !it.error.isNullOrBlank() || it.status.isFailurePhase() }
}

/**
 * Reasoning、Tool、CLI/MCP 与文件修改的统一活动组。
 *
 * 自动展开只在用户从未手动干预时生效：执行中展开，完成后保留 900ms 再折叠。一旦用户点击，
 * [userExpandedOverride] 会固定为显式布尔值，后续流式状态变化不得抢回控制权。这条竞态保护避免
 * 用户正在阅读工具结果时卡片突然收起。
 */
@Composable
internal fun AgentActivityCluster(
    item: ChatTimelineItem.AgentActivity,
    onPreview: (String) -> Unit,
    modifier: Modifier = Modifier,
) {
    val presentation = remember(item.messages) { buildActivityPresentation(item.messages) }
    var userExpandedOverride by
        rememberSaveable(item.key) { mutableStateOf<Boolean?>(null) }
    var completionHoldOpen by remember(item.key) { mutableStateOf(false) }
    var wasStreaming by remember(item.key) { mutableStateOf(item.isStreaming) }
    var nowMs by remember(item.key) { mutableLongStateOf(System.currentTimeMillis()) }

    LaunchedEffect(item.isStreaming, userExpandedOverride) {
        val completedNow = wasStreaming && !item.isStreaming
        wasStreaming = item.isStreaming
        when {
            item.isStreaming -> completionHoldOpen = false
            completedNow && userExpandedOverride == null -> {
                completionHoldOpen = true
                delay(ACTIVITY_COMPLETION_HOLD_MS)
                completionHoldOpen = false
            }
        }
    }
    LaunchedEffect(item.isStreaming) {
        while (item.isStreaming) {
            nowMs = System.currentTimeMillis()
            delay(1_000L)
        }
    }

    val expanded = userExpandedOverride ?: (item.isStreaming || completionHoldOpen)
    val durationMs = activityDurationMs(item, nowMs)
    val statusText =
        when {
            presentation.failed ->
                pluralStringResource(
                    R.plurals.activity_failed_count,
                    presentation.itemCount,
                    presentation.itemCount,
                )
            item.isStreaming ->
                pluralStringResource(
                    R.plurals.activity_running_count,
                    presentation.itemCount,
                    presentation.itemCount,
                )
            else ->
                pluralStringResource(
                    R.plurals.activity_completed_count,
                    presentation.itemCount,
                    presentation.itemCount,
                )
        }

    Surface(
        modifier = modifier.fillMaxWidth(),
        color = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.48f),
        shape = MaterialTheme.shapes.large,
    ) {
        Column(modifier = Modifier.fillMaxWidth()) {
            Row(
                modifier =
                    Modifier.fillMaxWidth().clickable {
                        userExpandedOverride = !expanded
                    }.padding(horizontal = 14.dp, vertical = 11.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(10.dp),
            ) {
                ActivityStateIcon(running = item.isStreaming, failed = presentation.failed)
                Column(modifier = Modifier.weight(1f)) {
                    Text(
                        text = statusText,
                        style = MaterialTheme.typography.bodyMedium,
                        fontWeight = FontWeight.Medium,
                    )
                    if (durationMs > 0L) {
                        Text(
                            text = formatMessageLatency(durationMs),
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            style = MaterialTheme.typography.labelSmall,
                        )
                    }
                }
                Icon(
                    imageVector = if (expanded) Icons.Rounded.ExpandLess else Icons.Rounded.ExpandMore,
                    contentDescription =
                        stringResource(
                            if (expanded) R.string.activity_collapse else R.string.activity_expand
                        ),
                    tint = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }

            AnimatedVisibility(expanded) {
                Column(
                    modifier = Modifier.fillMaxWidth().padding(start = 14.dp, end = 14.dp, bottom = 12.dp),
                    verticalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
                    presentation.reasoning.forEach { reasoning ->
                        ActivityTextRow(
                            icon = Icons.Rounded.Psychology,
                            title = stringResource(R.string.reasoning),
                            detail = reasoning,
                        )
                    }
                    presentation.tools.forEach { tool -> ToolActivityRow(tool) }
                    presentation.cliApps.forEach { app ->
                        ActivityTextRow(
                            icon = Icons.Rounded.Terminal,
                            title = app.displayName?.takeIf(String::isNotBlank) ?: app.name,
                            detail = app.category ?: stringResource(R.string.activity_cli),
                        )
                    }
                    presentation.mcpPresets.forEach { preset ->
                        ActivityTextRow(
                            icon = Icons.Rounded.Extension,
                            title = preset.displayName?.takeIf(String::isNotBlank) ?: preset.name,
                            detail = preset.status ?: preset.transport ?: stringResource(R.string.activity_mcp),
                        )
                    }
                    if (presentation.isEmpty) {
                        // WebSocket 在首个 reasoning/tool delta 前会短暂产生空活动占位。显式渲染“准备中”行，
                        // 既给用户即时反馈，也避免展开卡片后只看到一条分隔线。
                        ActivityTextRow(
                            icon = Icons.Rounded.Build,
                            title = stringResource(R.string.activity_step),
                            detail = stringResource(
                                if (item.isStreaming) R.string.activity_in_progress
                                else R.string.activity_completed
                            ),
                        )
                    }
                    presentation.notes.forEach { note ->
                        ActivityTextRow(
                            icon = Icons.Rounded.Build,
                            title = stringResource(R.string.activity_step),
                            detail = note,
                        )
                    }
                    presentation.fileEdits.forEach { edit ->
                        FileEditItem(edit = edit, onPreview = onPreview)
                    }
                }
            }
        }
    }
}

@Composable
private fun ActivityStateIcon(running: Boolean, failed: Boolean) {
    when {
        running ->
            CircularProgressIndicator(
                modifier = Modifier.size(18.dp),
                strokeWidth = 2.dp,
                color = MaterialTheme.colorScheme.primary,
            )
        failed ->
            Icon(
                Icons.Rounded.ErrorOutline,
                contentDescription = null,
                modifier = Modifier.size(20.dp),
                tint = MaterialTheme.colorScheme.error,
            )
        else ->
            Icon(
                Icons.Rounded.Check,
                contentDescription = null,
                modifier = Modifier.size(20.dp),
                tint = MaterialTheme.colorScheme.primary,
            )
    }
}

@Composable
private fun ToolActivityRow(tool: ToolProgressEvent) {
    val failed = tool.error != null || tool.phase.isFailurePhase()
    val running = tool.phase.isRunningPhase()
    val detail =
        when {
            tool.error != null -> tool.error.toString()
            // Kotlin 无法跨 JsonElement 分支稳定推断 phase 非空；这里显式收敛为 String，
            // 同时防止未来服务端把空 phase 传入 Text 导致可空类型泄漏到 Compose。
            !tool.phase.isNullOrBlank() -> tool.phase.orEmpty()
            tool.result != null -> stringResource(R.string.activity_completed)
            else -> stringResource(R.string.activity_tool)
        }
    val icon = if (failed) Icons.Rounded.ErrorOutline else Icons.Rounded.Build

    Row(
        modifier = Modifier.fillMaxWidth(),
        verticalAlignment = Alignment.Top,
        horizontalArrangement = Arrangement.spacedBy(9.dp),
    ) {
        if (running) {
            CircularProgressIndicator(
                modifier = Modifier.padding(top = 2.dp).size(16.dp),
                strokeWidth = 2.dp,
            )
        } else {
            Icon(
                icon,
                contentDescription = null,
                modifier = Modifier.padding(top = 1.dp).size(18.dp),
                tint = if (failed) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.primary,
            )
        }
        Column(modifier = Modifier.weight(1f)) {
            Text(
                text = tool.name?.takeIf(String::isNotBlank) ?: stringResource(R.string.activity_tool),
                style = MaterialTheme.typography.bodyMedium,
                fontWeight = FontWeight.Medium,
            )
            Text(
                text = detail,
                maxLines = 3,
                overflow = TextOverflow.Ellipsis,
                color = if (failed) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.onSurfaceVariant,
                style = MaterialTheme.typography.bodySmall,
            )
        }
    }
}

@Composable
private fun ActivityTextRow(
    icon: ImageVector,
    title: String,
    detail: String,
) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        verticalAlignment = Alignment.Top,
        horizontalArrangement = Arrangement.spacedBy(9.dp),
    ) {
        Icon(
            icon,
            contentDescription = null,
            modifier = Modifier.padding(top = 1.dp).size(18.dp),
            tint = MaterialTheme.colorScheme.primary,
        )
        Column(modifier = Modifier.weight(1f)) {
            Text(
                text = title,
                style = MaterialTheme.typography.bodyMedium,
                fontWeight = FontWeight.Medium,
            )
            Text(
                text = detail,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                style = MaterialTheme.typography.bodySmall,
            )
        }
    }
}

private fun buildActivityPresentation(messages: List<com.nanobotkt.core.model.UiMessage>): ActivityPresentation {
    val reasoning = messages.mapNotNull { it.reasoning?.trim()?.takeIf(String::isNotBlank) }.distinct()
    val toolByKey = linkedMapOf<String, ToolProgressEvent>()
    val fileByKey = linkedMapOf<String, UiFileEdit>()
    val cliByName = linkedMapOf<String, UiCliAppAttachment>()
    val mcpByName = linkedMapOf<String, UiMcpPresetAttachment>()

    messages.forEach { message ->
        message.toolEvents.orEmpty().forEachIndexed { index, event ->
            // callId 在 start/end 更新之间稳定；旧服务端缺失 callId 时退化到 name+index，仍保持确定顺序。
            val key = event.callId ?: "${event.name.orEmpty()}:$index"
            toolByKey[key] = event
        }
        message.fileEdits.orEmpty().forEach { edit ->
            fileByKey["${edit.callId}:${edit.path}"] = edit
        }
        message.cliApps.orEmpty().forEach { cliByName[it.name] = it }
        message.mcpPresets.orEmpty().forEach { mcpByName[it.name] = it }
    }

    val hasStructuredActivity =
        toolByKey.isNotEmpty() || fileByKey.isNotEmpty() || cliByName.isNotEmpty() || mcpByName.isNotEmpty()
    val notes =
        buildList {
            messages.flatMap { it.traces.orEmpty() }
                .map(String::trim)
                .filter(String::isNotBlank)
                .forEach(::add)
            // 某些旧历史只有 trace.content，没有 toolEvents。只有缺少结构化数据时才显示该文本，
            // 避免把工具 hint 与同一工具行重复展示。
            if (!hasStructuredActivity) {
                messages.filter { it.kind == "trace" }.map { it.content.trim() }
                    .filter(String::isNotBlank).forEach(::add)
            }
        }.distinct()

    return ActivityPresentation(
        reasoning = reasoning,
        tools = toolByKey.values.toList(),
        cliApps = cliByName.values.toList(),
        mcpPresets = mcpByName.values.toList(),
        fileEdits = fileByKey.values.toList(),
        notes = notes,
    )
}

private fun activityDurationMs(item: ChatTimelineItem.AgentActivity, nowMs: Long): Long {
    item.turnLatencyMs?.takeIf { it >= 0L }?.let { return it }
    if (!item.isStreaming) {
        val completedAt = item.messages.mapNotNull { it.completedAt }.maxOrNull()
        val startedAt = item.startedAtMs ?: item.messages.minOfOrNull { it.createdAt }
        if (completedAt != null && startedAt != null) return (completedAt - startedAt).coerceAtLeast(0L)
        return 0L
    }
    val startedAt = item.startedAtMs ?: item.messages.minOfOrNull { it.createdAt } ?: nowMs
    return (nowMs - startedAt).coerceAtLeast(0L)
}

private fun String?.isRunningPhase(): Boolean =
    this?.lowercase() in setOf("start", "started", "running", "pending", "in_progress")

private fun String?.isFailurePhase(): Boolean =
    this?.lowercase() in setOf("error", "failed", "failure", "cancelled", "canceled")
