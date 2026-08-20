package com.nanobotkt.feature.chat

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
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
import androidx.compose.material.icons.rounded.Schedule
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
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.nanobotkt.core.designsystem.NanobotThemeDefaults
import com.nanobotkt.core.model.ToolProgressEvent
import com.nanobotkt.core.model.UiCliAppAttachment
import com.nanobotkt.core.model.UiFileEdit
import com.nanobotkt.core.model.UiMcpPresetAttachment
import kotlinx.coroutines.delay

private const val ACTIVITY_COMPLETION_HOLD_MS = 900L

/** Activity 在主时间轴中的视觉等级；成功的纯 reasoning/trace 不再占据独立空间。 */
internal enum class ActivityDisplayMode {
    Hidden,
    Compact,
    Emphasized,
}

private enum class ActivityStatus {
    Running,
    Waiting,
    Failed,
    Completed,
}

private data class ActivityPresentation(
    val reasoning: List<String>,
    val tools: List<ToolProgressEvent>,
    val cliApps: List<UiCliAppAttachment>,
    val mcpPresets: List<UiMcpPresetAttachment>,
    val fileEdits: List<UiFileEdit>,
    val legacyToolResults: List<String>,
    val notes: List<String>,
) {
    val isEmpty: Boolean
        get() = reasoning.isEmpty() && tools.isEmpty() && cliApps.isEmpty() && mcpPresets.isEmpty() &&
            fileEdits.isEmpty() && legacyToolResults.isEmpty() && notes.isEmpty()

    val itemCount: Int
        // 流式轮次可能先收到一个没有正文和结构化事件的 assistant 占位。状态栏仍应显示“1 个步骤”，
        // 不能暴露“0 个步骤”这种实现细节；真实事件到达后会自动替换该占位计数。
        get() =
            if (isEmpty) {
                1
            } else {
                reasoning.size + tools.size + cliApps.size + mcpPresets.size + fileEdits.size +
                    legacyToolResults.size + notes.size
            }

    val failed: Boolean
        get() =
            tools.any { it.error != null || it.phase.isFailurePhase() } ||
                fileEdits.any { !it.error.isNullOrBlank() || it.status.isFailurePhase() }

    val waiting: Boolean
        // 只把服务端明确标记为“等待用户/确认”的 phase 提升为等待状态；普通 pending 仍属于
        // 执行中，避免尚未开始的工具被误报成需要用户操作。
        get() =
            tools.any { it.phase.isWaitingForUserPhase() } ||
                fileEdits.any { it.pending == true || it.phase.isWaitingForUserPhase() }

    val hasDurableActivity: Boolean
        // Reasoning 和普通 trace 只解释内部过程，成功后不值得永久占用时间轴；结构化工具、
        // 旧 role=tool 结果、CLI/MCP 与文件修改则是用户可能需要复查的真实执行记录，应保留
        // 一个可展开的轻量摘要。
        get() =
            tools.isNotEmpty() || legacyToolResults.isNotEmpty() || cliApps.isNotEmpty() ||
                mcpPresets.isNotEmpty() || fileEdits.isNotEmpty()
}

/**
 * 返回 LazyColumn 实际渲染的时间轴单元。
 *
 * 成功的纯 reasoning/trace Activity 不应生成零高度 item，否则不仅会留下额外间距，还会让
 * Prompt 导航和“回到底部”使用错误索引。该过滤保持为共享纯函数，确保所有滚动入口与渲染列表
 * 使用完全一致的索引体系。
 */
internal fun visibleChatTimelineItems(items: List<ChatTimelineItem>): List<ChatTimelineItem> =
    items.filterNot { item ->
        item is ChatTimelineItem.AgentActivity &&
            activityDisplayMode(item) == ActivityDisplayMode.Hidden
    }

/**
 * 决定 Activity 是否应该出现在主时间轴。
 *
 * 该函数同时供共享时间轴过滤策略和组件本身的防御性判断使用，保证隐藏 Activity 不会因为
 * LazyColumn 的 item 间距留下“看不见但占空间”的空洞。
 */
internal fun activityDisplayMode(item: ChatTimelineItem.AgentActivity): ActivityDisplayMode {
    val presentation = buildActivityPresentation(item.messages)
    return when {
        item.isStreaming || presentation.failed || presentation.waiting -> ActivityDisplayMode.Emphasized
        presentation.hasDurableActivity -> ActivityDisplayMode.Compact
        else -> ActivityDisplayMode.Hidden
    }
}

/** 供纯单元测试锁定摘要计数，避免 role=tool 兼容逻辑与实际 UI 文案数量发生漂移。 */
internal fun activityStepCount(item: ChatTimelineItem.AgentActivity): Int =
    buildActivityPresentation(item.messages).itemCount

/**
 * Reasoning、Tool、CLI/MCP 与文件修改的统一活动组。
 *
 * 执行中、失败或等待用户时自动展开并使用状态容器；普通成功记录降级为 40dp 左右的摘要行。
 * 用户手动展开/折叠后始终尊重用户选择，流式状态变化不能抢回控制权。
 */
@Composable
internal fun AgentActivityCluster(
    item: ChatTimelineItem.AgentActivity,
    onPreview: (String) -> Unit,
    modifier: Modifier = Modifier,
) {
    val presentation = remember(item.messages) { buildActivityPresentation(item.messages) }
    val displayMode = activityDisplayMode(item)
    if (displayMode == ActivityDisplayMode.Hidden) return

    val status =
        when {
            presentation.failed -> ActivityStatus.Failed
            presentation.waiting -> ActivityStatus.Waiting
            item.isStreaming -> ActivityStatus.Running
            else -> ActivityStatus.Completed
        }
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
                // 完成瞬间保留详情 900ms，让用户能确认最后一步结果；随后折叠为轻量摘要，避免
                // 页面在每次回复结束后永久留下大卡片。
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

    val expanded =
        userExpandedOverride
            ?: (displayMode == ActivityDisplayMode.Emphasized || completionHoldOpen)
    val durationMs = activityDurationMs(item, nowMs)
    val statusText =
        when (status) {
            ActivityStatus.Failed ->
                pluralStringResource(
                    R.plurals.activity_failed_count,
                    presentation.itemCount,
                    presentation.itemCount,
                )
            ActivityStatus.Waiting ->
                pluralStringResource(
                    R.plurals.activity_waiting_count,
                    presentation.itemCount,
                    presentation.itemCount,
                )
            ActivityStatus.Running ->
                pluralStringResource(
                    R.plurals.activity_running_count,
                    presentation.itemCount,
                    presentation.itemCount,
                )
            ActivityStatus.Completed ->
                pluralStringResource(
                    R.plurals.activity_completed_count,
                    presentation.itemCount,
                    presentation.itemCount,
                )
        }
    val summaryText =
        if (durationMs > 0L) {
            stringResource(
                R.string.activity_status_with_duration,
                statusText,
                formatMessageLatency(durationMs),
            )
        } else {
            statusText
        }
    val emphasized = displayMode == ActivityDisplayMode.Emphasized
    val compactExpanded = displayMode == ActivityDisplayMode.Compact && expanded
    val statusColors = NanobotThemeDefaults.statusColors
    val containerColor =
        when (status) {
            ActivityStatus.Failed -> MaterialTheme.colorScheme.errorContainer.copy(alpha = 0.56f)
            ActivityStatus.Waiting -> statusColors.warningContainer.copy(alpha = 0.56f)
            ActivityStatus.Running -> MaterialTheme.colorScheme.primaryContainer.copy(alpha = 0.48f)
            // 已完成且折叠的 Activity 只是可复查的执行元数据，不再使用整块 tonal 容器抢占
            // Assistant 正文层级；用户主动展开后才恢复轻量背景，以界定详情内容的阅读边界。
            ActivityStatus.Completed ->
                if (compactExpanded) {
                    MaterialTheme.colorScheme.surfaceContainerLowest
                } else {
                    Color.Transparent
                }
        }

    Surface(
        modifier = modifier.fillMaxWidth(),
        color = containerColor,
        shape = if (emphasized) MaterialTheme.shapes.large else MaterialTheme.shapes.small,
        tonalElevation = 0.dp,
        shadowElevation = 0.dp,
    ) {
        Column(modifier = Modifier.fillMaxWidth()) {
            Row(
                modifier =
                    Modifier.fillMaxWidth()
                        // 强调状态保留稳定的 40dp 状态入口；完成摘要压缩为 36dp 左右的元数据行，
                        // 但整行仍可点击展开，不依赖较小的尾部箭头作为唯一触控目标。
                        .heightIn(min = if (emphasized) 40.dp else 36.dp)
                        .clickable { userExpandedOverride = !expanded }
                        .padding(
                            horizontal =
                                when {
                                    emphasized -> 12.dp
                                    compactExpanded -> 8.dp
                                    else -> 2.dp
                                },
                            vertical = if (emphasized) 8.dp else 2.dp,
                        ),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                ActivityStateIcon(status = status)
                Text(
                    text = summaryText,
                    modifier = Modifier.weight(1f),
                    color =
                        if (status == ActivityStatus.Failed) {
                            MaterialTheme.colorScheme.onErrorContainer
                        } else {
                            MaterialTheme.colorScheme.onSurfaceVariant
                        },
                    style = MaterialTheme.typography.labelLarge,
                    fontWeight = if (emphasized) FontWeight.Medium else FontWeight.Normal,
                )
                Icon(
                    imageVector = if (expanded) Icons.Rounded.ExpandLess else Icons.Rounded.ExpandMore,
                    contentDescription =
                        stringResource(
                            if (expanded) R.string.activity_collapse else R.string.activity_expand
                        ),
                    modifier = Modifier.size(20.dp),
                    tint = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }

            AnimatedVisibility(expanded) {
                Column(
                    modifier =
                        Modifier.fillMaxWidth()
                            .padding(
                                start = if (emphasized) 12.dp else 8.dp,
                                end = if (emphasized) 12.dp else 8.dp,
                                bottom = 10.dp,
                            ),
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
                    presentation.legacyToolResults.forEach { result ->
                        ActivityTextRow(
                            icon = Icons.Rounded.Build,
                            title = stringResource(R.string.activity_tool),
                            // 旧历史可能只保留空工具结果；此时仍保留该次执行计数，但不把空白行
                            // 暴露给用户，而是使用稳定的完成态文案。
                            detail =
                                result.takeIf(String::isNotBlank)
                                    ?: stringResource(R.string.activity_completed),
                        )
                    }
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
                        ActivityTextRow(
                            icon = Icons.Rounded.Build,
                            title = stringResource(R.string.activity_step),
                            detail = stringResource(R.string.activity_in_progress),
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
private fun ActivityStateIcon(status: ActivityStatus) {
    when (status) {
        ActivityStatus.Running ->
            CircularProgressIndicator(
                modifier = Modifier.size(17.dp),
                strokeWidth = 2.dp,
                color = MaterialTheme.colorScheme.primary,
            )
        ActivityStatus.Waiting ->
            Icon(
                Icons.Rounded.Schedule,
                contentDescription = null,
                modifier = Modifier.size(19.dp),
                tint = NanobotThemeDefaults.statusColors.warning,
            )
        ActivityStatus.Failed ->
            Icon(
                Icons.Rounded.ErrorOutline,
                contentDescription = null,
                modifier = Modifier.size(19.dp),
                tint = MaterialTheme.colorScheme.error,
            )
        ActivityStatus.Completed ->
            Icon(
                Icons.Rounded.Check,
                contentDescription = null,
                modifier = Modifier.size(16.dp),
                // 完成图标保留成功语义，但降低饱和度，避免历史 Activity 与正在执行的状态竞争。
                tint = NanobotThemeDefaults.statusColors.success.copy(alpha = 0.78f),
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
    // 只有缺少结构化载荷的 role=tool 才属于旧工具结果；新协议同样使用 role=tool，但会同时
    // 携带 toolEvents/fileEdits 等字段，若再次按 content 计数会把一次调用重复显示为两个步骤。
    // 对真正的旧记录不做 distinct/filter：相同或空结果仍然分别代表一次真实工具执行。
    val legacyToolResults =
        messages.filter { message ->
            message.role.equals("tool", ignoreCase = true) &&
                message.toolEvents.isNullOrEmpty() &&
                message.fileEdits.isNullOrEmpty() &&
                message.cliApps.isNullOrEmpty() &&
                message.mcpPresets.isNullOrEmpty()
        }.map { it.content.trim() }
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
        toolByKey.isNotEmpty() || fileByKey.isNotEmpty() || cliByName.isNotEmpty() ||
            mcpByName.isNotEmpty() || legacyToolResults.isNotEmpty()
    val notes =
        buildList {
            messages.filterNot { it.role.equals("tool", ignoreCase = true) }
                .flatMap { it.traces.orEmpty() }
                .map(String::trim)
                .filter(String::isNotBlank)
                .forEach(::add)
            // 某些旧历史只有 trace.content，没有 toolEvents。只有缺少结构化数据时才显示该文本，
            // 避免把工具 hint 与同一工具行重复展示。
            if (!hasStructuredActivity) {
                messages.filter { it.kind == "trace" && !it.role.equals("tool", ignoreCase = true) }
                    .map { it.content.trim() }
                    .filter(String::isNotBlank).forEach(::add)
            }
        }.distinct()

    return ActivityPresentation(
        reasoning = reasoning,
        tools = toolByKey.values.toList(),
        cliApps = cliByName.values.toList(),
        mcpPresets = mcpByName.values.toList(),
        fileEdits = fileByKey.values.toList(),
        legacyToolResults = legacyToolResults,
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

/** 仅识别服务端明确要求用户介入的阶段；普通 pending 仍归入运行状态。 */
private fun String?.isWaitingForUserPhase(): Boolean =
    this?.lowercase() in
        setOf(
            "waiting_for_user",
            "awaiting_user",
            "awaiting_confirmation",
            "requires_action",
            "requires_confirmation",
            "blocked",
        )
