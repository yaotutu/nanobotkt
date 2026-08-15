package com.nanobotkt.feature.chat

import android.content.ClipData
import android.content.ClipboardManager
import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.border
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.ErrorOutline
import androidx.compose.material.icons.rounded.FormatQuote
import androidx.compose.material.icons.rounded.Refresh
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.layout.boundsInWindow
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.nanobotkt.core.model.UiMessage

// 用户气泡只用于区分说话方，不应成为占满时间轴的大色块。76% 在手机端仍能容纳正常段落，
// 同时为 Assistant 的文档式内容保留明显的宽度层级。
private const val USER_BUBBLE_MAX_WIDTH_FRACTION = 0.76f
private const val COLLAPSIBLE_USER_MESSAGE_CHARS = 900
private const val COLLAPSIBLE_USER_MESSAGE_LINES = 14

internal data class StructuredTimelineError(val detail: String)

/**
 * 仅把“整条消息就是 error envelope”的服务端降级内容识别成状态块。
 *
 * 普通 Markdown、代码示例或正文中偶然出现的 <error> 片段必须继续按文档展示，因此这里要求开始、
 * 结束标签完整包裹全部内容；UI 只移除协议标签，原始详情仍保留给用户判断问题。
 */
internal fun parseStructuredTimelineError(content: String): StructuredTimelineError? {
    val match = Regex("(?is)^\\s*<error>\\s*(.*?)\\s*</error>\\s*$").matchEntire(content) ?: return null
    return match.groupValues[1].trim().takeIf(String::isNotBlank)?.let(::StructuredTimelineError)
}

/**
 * 用户消息使用右侧轻量气泡，正常状态不显示头像、用户名、时间或“已发送”。
 *
 * 排队和失败属于单条消息状态，因此紧贴气泡展示；失败时只有右侧 Retry 是常驻操作。复制、引用、
 * Fork 与查看统一收进长按悬浮菜单，避免低频动作长期占据时间轴。
 */
@OptIn(ExperimentalFoundationApi::class)
@Composable
internal fun UserTimelineMessage(
    message: UiMessage,
    deliveryState: UserMessageDeliveryState,
    resolveUrl: (String) -> String,
    playbackCoordinator: TimelinePlaybackCoordinator,
    onQuote: () -> Unit,
    onFork: (() -> Unit)?,
    onRetry: (() -> Unit)?,
    menuDismissSignal: Int,
    highlighted: Boolean,
    modifier: Modifier = Modifier,
) {
    val copyText = rememberClipboardCopy()
    val parsed = remember(message.content) { parseQuotedUserMessage(message.content) }
    val hasText = parsed.content.isNotBlank() || !parsed.quotedContext.isNullOrBlank()
    val hasMedia = !message.images.isNullOrEmpty() || !message.media.isNullOrEmpty()
    val haptics = LocalHapticFeedback.current
    val density = LocalDensity.current
    var actionsExpanded by rememberSaveable(message.id) { mutableStateOf(false) }
    var detailOpen by rememberSaveable(message.id) { mutableStateOf(false) }
    var messageTopPx by remember(message.id) { mutableFloatStateOf(Float.MAX_VALUE) }
    var expanded by rememberSaveable(message.id) { mutableStateOf(false) }
    val shouldCollapse =
        parsed.content.length > COLLAPSIBLE_USER_MESSAGE_CHARS ||
            parsed.content.lineSequence().count() > COLLAPSIBLE_USER_MESSAGE_LINES
    val actions =
        remember(deliveryState, message.isStreaming, onFork, hasText) {
            availableMessageActions(
                role = "user",
                deliveryState = deliveryState,
                streaming = message.isStreaming == true,
                canFork = onFork != null,
                hasContent = hasText,
            )
        }
    // Prompt/Queue 导航落点需要明显但短暂。仅切换相近容器色在动态主题下可能难以辨认，
    // 因此同时动画过渡背景并增加 primary 描边；状态结束后恢复普通用户气泡，不改变布局。
    val bubbleColor by
        animateColorAsState(
            targetValue =
                if (highlighted) {
                    MaterialTheme.colorScheme.primaryContainer
                } else {
                    MaterialTheme.colorScheme.secondaryContainer
                },
            animationSpec = tween(durationMillis = 180),
            label = "user-message-highlight",
        )

    LaunchedEffect(menuDismissSignal) { actionsExpanded = false }

    BoxWithConstraints(modifier = modifier.fillMaxWidth()) {
        val maxBubbleWidth = maxWidth * USER_BUBBLE_MAX_WIDTH_FRACTION
        val placeMenuBelow = with(density) { messageTopPx < 112.dp.toPx() }
        Column(
            modifier = Modifier.fillMaxWidth(),
            horizontalAlignment = Alignment.End,
            verticalArrangement = Arrangement.spacedBy(4.dp),
        ) {
            message.source?.takeIf { it.kind.equals("automation", ignoreCase = true) }?.let { source ->
                Text(
                    text = stringResource(R.string.automation_source, source.label ?: source.kind),
                    color = MaterialTheme.colorScheme.primary,
                    style = MaterialTheme.typography.labelSmall,
                )
            }
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.End,
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Box {
                    Surface(
                        modifier =
                            Modifier.widthIn(min = 48.dp, max = maxBubbleWidth)
                                .border(
                                    width = if (highlighted) 2.dp else 0.dp,
                                    color = MaterialTheme.colorScheme.primary,
                                    shape = MaterialTheme.shapes.large,
                                )
                                .onGloballyPositioned { coordinates ->
                                    messageTopPx = coordinates.boundsInWindow().top
                                }
                                .combinedClickable(
                                    onClick = {},
                                    onLongClick = {
                                        if (actions.isNotEmpty()) {
                                            haptics.performHapticFeedback(HapticFeedbackType.LongPress)
                                            actionsExpanded = true
                                        }
                                    },
                                ),
                        shape = MaterialTheme.shapes.large,
                        color = bubbleColor,
                        tonalElevation = 0.dp,
                        shadowElevation = 0.dp,
                    ) {
                        Column(
                            modifier = Modifier.padding(horizontal = 14.dp, vertical = 9.dp),
                            verticalArrangement = Arrangement.spacedBy(8.dp),
                        ) {
                            parsed.quotedContext?.takeIf(String::isNotBlank)?.let { quote ->
                                Surface(
                                    color = MaterialTheme.colorScheme.surface.copy(alpha = 0.72f),
                                    shape = MaterialTheme.shapes.medium,
                                ) {
                                    Row(
                                        modifier = Modifier.padding(horizontal = 10.dp, vertical = 8.dp),
                                        horizontalArrangement = Arrangement.spacedBy(6.dp),
                                        verticalAlignment = Alignment.Top,
                                    ) {
                                        Icon(
                                            Icons.Rounded.FormatQuote,
                                            contentDescription = null,
                                            modifier = Modifier.size(16.dp),
                                            tint = MaterialTheme.colorScheme.onSurfaceVariant,
                                        )
                                        Text(
                                            quote,
                                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                                            style = MaterialTheme.typography.bodySmall,
                                            maxLines = 4,
                                            overflow = TextOverflow.Ellipsis,
                                        )
                                    }
                                }
                            }
                            if (parsed.content.isNotBlank()) {
                                Text(
                                    text = parsed.content,
                                    color = MaterialTheme.colorScheme.onSecondaryContainer,
                                    style = MaterialTheme.typography.bodyLarge,
                                    maxLines =
                                        if (shouldCollapse && !expanded) COLLAPSIBLE_USER_MESSAGE_LINES
                                        else Int.MAX_VALUE,
                                    overflow = TextOverflow.Ellipsis,
                                )
                                if (shouldCollapse) {
                                    TextButton(
                                        onClick = { expanded = !expanded },
                                        modifier = Modifier.align(Alignment.End),
                                    ) {
                                        Text(
                                            if (expanded) stringResource(R.string.collapse)
                                            else stringResource(R.string.expand)
                                        )
                                    }
                                }
                            }
                            if (hasMedia) {
                                MessageMediaBlock(
                                    message = message,
                                    resolveUrl = resolveUrl,
                                    playbackCoordinator = playbackCoordinator,
                                )
                            }
                        }
                    }
                    MessageFloatingActionMenu(
                        expanded = actionsExpanded,
                        actions = actions,
                        placeBelow = placeMenuBelow,
                        onDismiss = { actionsExpanded = false },
                        onAction = { action ->
                            actionsExpanded = false
                            when (action) {
                                MessageAction.COPY -> copyText(message.content)
                                MessageAction.QUOTE -> onQuote()
                                MessageAction.FORK -> onFork?.invoke()
                                MessageAction.VIEW -> detailOpen = true
                            }
                        },
                    )
                }
                if (deliveryState == UserMessageDeliveryState.FAILED && onRetry != null) {
                    IconButton(onClick = onRetry) {
                        Icon(
                            Icons.Rounded.Refresh,
                            contentDescription = stringResource(R.string.retry_message),
                            tint = MaterialTheme.colorScheme.error,
                        )
                    }
                }
            }
            when (deliveryState) {
                UserMessageDeliveryState.QUEUED ->
                    Text(
                        text = stringResource(R.string.message_queued),
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        style = MaterialTheme.typography.labelSmall,
                    )
                UserMessageDeliveryState.FAILED ->
                    Text(
                        text = stringResource(R.string.message_failed),
                        color = MaterialTheme.colorScheme.error,
                        style = MaterialTheme.typography.labelSmall,
                    )
                UserMessageDeliveryState.SENT -> Unit
            }
        }
    }

    if (detailOpen) {
        MessageDetailDialog(
            title = stringResource(R.string.user_message_detail),
            content = message.content,
            onDismiss = { detailOpen = false },
        )
    }
}

/** Assistant 最终回复直接采用文档式排版，不套大气泡或重复展示头像与名称。 */
@OptIn(ExperimentalFoundationApi::class)
@Composable
internal fun AssistantTimelineMessage(
    message: UiMessage,
    forkIndex: Int?,
    resolveUrl: (String) -> String,
    playbackCoordinator: TimelinePlaybackCoordinator,
    onQuote: () -> Unit,
    onFork: () -> Unit,
    menuDismissSignal: Int,
    modifier: Modifier = Modifier,
) {
    val copyText = rememberClipboardCopy()
    val haptics = LocalHapticFeedback.current
    val density = LocalDensity.current
    var actionsExpanded by rememberSaveable(message.id) { mutableStateOf(false) }
    var detailOpen by rememberSaveable(message.id) { mutableStateOf(false) }
    var messageTopPx by remember(message.id) { mutableFloatStateOf(Float.MAX_VALUE) }
    val hasContent = message.content.isNotBlank()
    val structuredError = remember(message.content) { parseStructuredTimelineError(message.content) }
    val hasMedia = !message.images.isNullOrEmpty() || !message.media.isNullOrEmpty()
    val actions =
        remember(message.isStreaming, forkIndex, hasContent) {
            availableMessageActions(
                role = "assistant",
                streaming = message.isStreaming == true,
                canFork = forkIndex != null,
                hasContent = hasContent,
            )
        }

    LaunchedEffect(menuDismissSignal) { actionsExpanded = false }

    Box(
        modifier =
            modifier.fillMaxWidth().onGloballyPositioned { coordinates ->
                messageTopPx = coordinates.boundsInWindow().top
            }
    ) {
        Column(
            modifier =
                Modifier.fillMaxWidth().combinedClickable(
                    onClick = {},
                    onLongClick = {
                        if (actions.isNotEmpty()) {
                            haptics.performHapticFeedback(HapticFeedbackType.LongPress)
                            actionsExpanded = true
                        }
                    },
                ),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            message.source?.takeIf { it.kind.equals("automation", ignoreCase = true) }?.let { source ->
                Text(
                    text = stringResource(R.string.automation_source, source.label ?: source.kind),
                    color = MaterialTheme.colorScheme.primary,
                    style = MaterialTheme.typography.labelMedium,
                    fontWeight = FontWeight.Medium,
                )
            }
            if (hasContent) {
                if (structuredError != null) {
                    TimelineErrorBlock(error = structuredError)
                } else {
                    MarkdownDocument(markdown = message.content, resolveUrl = resolveUrl)
                }
            }
            if (hasMedia) {
                MessageMediaBlock(
                    message = message,
                    resolveUrl = resolveUrl,
                    playbackCoordinator = playbackCoordinator,
                )
            }
            if (message.isStreaming == true) {
                CircularProgressIndicator(
                    modifier = Modifier.size(15.dp),
                    strokeWidth = 1.8.dp,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }

        MessageFloatingActionMenu(
            expanded = actionsExpanded,
            actions = actions,
            placeBelow = with(density) { messageTopPx < 112.dp.toPx() },
            onDismiss = { actionsExpanded = false },
            onAction = { action ->
                actionsExpanded = false
                when (action) {
                    MessageAction.COPY -> copyText(message.content)
                    MessageAction.QUOTE -> onQuote()
                    MessageAction.FORK -> if (forkIndex != null) onFork()
                    MessageAction.VIEW -> detailOpen = true
                }
            },
        )
    }

    if (detailOpen) {
        MessageDetailDialog(
            title = stringResource(R.string.assistant_message_detail),
            content = message.content,
            onDismiss = { detailOpen = false },
        )
    }
}

/**
 * 协议错误在时间轴中使用低饱和状态块，而不是把 <error> 标签当作 Assistant 正文直接输出。
 * 卡片保持内容自适应并限制最大宽度，避免错误信息再次成为占满屏幕的大气泡。
 */
@Composable
private fun TimelineErrorBlock(error: StructuredTimelineError) {
    Surface(
        modifier = Modifier.widthIn(min = 220.dp, max = 320.dp),
        color = MaterialTheme.colorScheme.errorContainer.copy(alpha = 0.42f),
        shape = MaterialTheme.shapes.medium,
        border =
            androidx.compose.foundation.BorderStroke(
                1.dp,
                MaterialTheme.colorScheme.error.copy(alpha = 0.28f),
            ),
        tonalElevation = 0.dp,
        shadowElevation = 0.dp,
    ) {
        Row(
            modifier = Modifier.padding(horizontal = 12.dp, vertical = 10.dp),
            horizontalArrangement = Arrangement.spacedBy(10.dp),
            verticalAlignment = Alignment.Top,
        ) {
            Icon(
                imageVector = Icons.Rounded.ErrorOutline,
                contentDescription = null,
                modifier = Modifier.size(20.dp),
                tint = MaterialTheme.colorScheme.error,
            )
            Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
                Text(
                    text = stringResource(R.string.timeline_error_title),
                    color = MaterialTheme.colorScheme.onErrorContainer,
                    style = MaterialTheme.typography.labelLarge,
                    fontWeight = FontWeight.SemiBold,
                )
                Text(
                    text = error.detail,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    style = MaterialTheme.typography.bodySmall,
                )
            }
        }
    }
}

@Composable
private fun MessageDetailDialog(
    title: String,
    content: String,
    onDismiss: () -> Unit,
) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(title) },
        text = {
            Text(
                text = content,
                modifier = Modifier.fillMaxWidth().heightIn(max = 480.dp).verticalScroll(rememberScrollState()),
                style = MaterialTheme.typography.bodyMedium,
            )
        },
        confirmButton = {
            TextButton(onClick = onDismiss) { Text(stringResource(R.string.close)) }
        },
    )
}

/** Clipboard 获取集中在 UI 边界；异常只影响复制结果，不能中断消息渲染。 */
@Composable
private fun rememberClipboardCopy(): (String) -> Boolean {
    val context = LocalContext.current
    val clipboard = remember(context) { context.getSystemService(ClipboardManager::class.java) }
    return remember(clipboard) {
        { text: String ->
            clipboard?.let {
                runCatching { it.setPrimaryClip(ClipData.newPlainText("message", text)) }.isSuccess
            } ?: false
        }
    }
}
