package com.nanobotkt.feature.chat

import android.content.ClipData
import android.content.ClipboardManager
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.widthIn
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.CallSplit
import androidx.compose.material.icons.rounded.ContentCopy
import androidx.compose.material.icons.rounded.FormatQuote
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.nanobotkt.core.model.UiMessage

private const val USER_BUBBLE_MAX_WIDTH_FRACTION = 0.82f

/**
 * 用户消息使用右侧、内容自适应的 tonal 气泡。
 *
 * 这里不能再给气泡调用 fillMaxWidth：短文本会因此被强制拉到屏幕最大宽度，复制按钮也会被挤到
 * 独立一行。操作入口统一改为长按菜单，时间轴默认只保留真正的消息内容。
 */
@OptIn(ExperimentalFoundationApi::class)
@Composable
internal fun UserTimelineMessage(
    message: UiMessage,
    resolveUrl: (String) -> String,
    modifier: Modifier = Modifier,
) {
    val copyText = rememberClipboardCopy()
    val parsed = remember(message.content) { parseQuotedUserMessage(message.content) }
    val hasText = parsed.content.isNotBlank() || !parsed.quotedContext.isNullOrBlank()
    val hasMedia = !message.images.isNullOrEmpty() || !message.media.isNullOrEmpty()
    var actionsExpanded by rememberSaveable(message.id) { mutableStateOf(false) }

    BoxWithConstraints(modifier = modifier.fillMaxWidth()) {
        // 使用当前可用宽度而不是固定手机像素值，既让短消息保持紧凑，也能适配横屏和折叠屏。
        val maxBubbleWidth = maxWidth * USER_BUBBLE_MAX_WIDTH_FRACTION
        Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.End) {
            Box {
                Surface(
                    modifier =
                        Modifier.widthIn(min = 48.dp, max = maxBubbleWidth).combinedClickable(
                            onClick = {},
                            onLongClick = {
                                if (hasText) actionsExpanded = true
                            },
                        ),
                    shape = MaterialTheme.shapes.extraLarge,
                    // 用户消息只需要和 Assistant 正文形成方向区分，不应再与高优先级状态共用
                    // 过强的 primaryContainer，因此使用更克制的 secondaryContainer。
                    color = MaterialTheme.colorScheme.secondaryContainer,
                    tonalElevation = 0.dp,
                    shadowElevation = 0.dp,
                ) {
                    Column(
                        modifier = Modifier.padding(horizontal = 16.dp, vertical = 10.dp),
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
                                        text = quote,
                                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                                        style = MaterialTheme.typography.bodySmall,
                                    )
                                }
                            }
                        }
                        parsed.content.takeIf(String::isNotBlank)?.let { content ->
                            Text(
                                text = content,
                                color = MaterialTheme.colorScheme.onSecondaryContainer,
                                style = MaterialTheme.typography.bodyLarge,
                            )
                        }
                        if (hasMedia) {
                            MessageMediaBlock(message = message, resolveUrl = resolveUrl)
                        }
                    }
                }

                DropdownMenu(
                    expanded = actionsExpanded,
                    onDismissRequest = { actionsExpanded = false },
                ) {
                    DropdownMenuItem(
                        text = { Text(stringResource(R.string.copy)) },
                        leadingIcon = {
                            Icon(Icons.Rounded.ContentCopy, contentDescription = null)
                        },
                        onClick = {
                            copyText(message.content)
                            actionsExpanded = false
                        },
                    )
                }
            }
        }
    }
}

/**
 * Assistant 正文保持文档式布局，不套大气泡。
 *
 * Copy、引用和 Fork 都属于当前消息的低频局部操作，默认隐藏在长按菜单中。这样不会让每条回复
 * 永久携带一排图标，也不会重复展示已经由 Activity 摘要承载的耗时信息。
 */
@OptIn(ExperimentalFoundationApi::class)
@Composable
internal fun AssistantTimelineMessage(
    message: UiMessage,
    forkIndex: Int?,
    resolveUrl: (String) -> String,
    onQuote: () -> Unit,
    onFork: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val copyText = rememberClipboardCopy()
    var actionsExpanded by rememberSaveable(message.id) { mutableStateOf(false) }
    val hasContent = message.content.isNotBlank()
    val hasMedia = !message.images.isNullOrEmpty() || !message.media.isNullOrEmpty()
    val canOpenActions = message.isStreaming != true && hasContent

    Box(modifier = modifier.fillMaxWidth()) {
        Column(
            modifier =
                Modifier.fillMaxWidth().combinedClickable(
                    onClick = {},
                    onLongClick = {
                        if (canOpenActions) actionsExpanded = true
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
                Text(
                    text = message.content,
                    color = MaterialTheme.colorScheme.onSurface,
                    style = MaterialTheme.typography.bodyLarge,
                )
            }
            if (hasMedia) {
                MessageMediaBlock(message = message, resolveUrl = resolveUrl)
            }
            if (message.isStreaming == true) {
                CircularProgressIndicator(
                    modifier = Modifier.size(15.dp),
                    strokeWidth = 1.8.dp,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }

        DropdownMenu(
            expanded = actionsExpanded,
            onDismissRequest = { actionsExpanded = false },
        ) {
            DropdownMenuItem(
                text = { Text(stringResource(R.string.copy)) },
                leadingIcon = {
                    Icon(Icons.Rounded.ContentCopy, contentDescription = null)
                },
                onClick = {
                    // Android 系统会为剪贴板写入提供统一反馈；菜单立即关闭，避免在时间轴内部
                    // 再维护一套不会自动复位的“已复制”状态，也防止下次长按仍显示旧反馈。
                    copyText(message.content)
                    actionsExpanded = false
                },
            )
            DropdownMenuItem(
                text = { Text(stringResource(R.string.quote_selection_title)) },
                leadingIcon = {
                    Icon(Icons.Rounded.FormatQuote, contentDescription = null)
                },
                onClick = {
                    actionsExpanded = false
                    onQuote()
                },
            )
            if (forkIndex != null) {
                DropdownMenuItem(
                    text = { Text(stringResource(R.string.fork)) },
                    leadingIcon = {
                        Icon(Icons.AutoMirrored.Rounded.CallSplit, contentDescription = null)
                    },
                    onClick = {
                        actionsExpanded = false
                        onFork()
                    },
                )
            }
        }
    }
}

/** Clipboard 获取集中在 UI 边界；异常只影响复制反馈，不能中断消息渲染。 */
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
