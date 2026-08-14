package com.nanobotkt.feature.chat

import android.content.ClipData
import android.content.ClipboardManager
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.widthIn
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.CallSplit
import androidx.compose.material.icons.rounded.Check
import androidx.compose.material.icons.rounded.ContentCopy
import androidx.compose.material.icons.rounded.FormatQuote
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.nanobotkt.core.model.UiMessage
import kotlinx.coroutines.delay

/** 用户消息：右侧 tonal 气泡，Quote、正文和附件作为同一消息内的内容块。 */
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

    Row(modifier = modifier.fillMaxWidth(), horizontalArrangement = Arrangement.End) {
        Column(
            modifier = Modifier.widthIn(min = 54.dp, max = 340.dp),
            horizontalAlignment = Alignment.End,
        ) {
            Surface(
                modifier =
                    Modifier.fillMaxWidth().combinedClickable(
                        onClick = {},
                        onLongClick = {
                            message.content.takeIf(String::isNotBlank)?.let(copyText)
                        },
                    ),
                shape = MaterialTheme.shapes.extraLarge,
                color = MaterialTheme.colorScheme.primaryContainer,
                tonalElevation = 0.dp,
                shadowElevation = 0.dp,
            ) {
                Column(
                    modifier = Modifier.padding(horizontal = 16.dp, vertical = 11.dp),
                    verticalArrangement = Arrangement.spacedBy(9.dp),
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
                            color = MaterialTheme.colorScheme.onPrimaryContainer,
                            style = MaterialTheme.typography.bodyLarge,
                        )
                    }
                    if (hasMedia) {
                        MessageMediaBlock(message = message, resolveUrl = resolveUrl)
                    }
                }
            }
            if (hasText) {
                Spacer(Modifier.height(6.dp))
                IconButton(
                    onClick = { copyText(message.content) },
                    modifier = Modifier.size(38.dp),
                ) {
                    Icon(
                        Icons.Rounded.ContentCopy,
                        stringResource(R.string.copy),
                        modifier = Modifier.size(17.dp),
                        tint = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
        }
    }
}

/** 助手正文保持文档式布局，不套大气泡；活动详情已经由 Mapper 分离。 */
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
    var copied by remember(message.id) { mutableStateOf(false) }
    val hasContent = message.content.isNotBlank()
    val hasMedia = !message.images.isNullOrEmpty() || !message.media.isNullOrEmpty()
    val elapsedMs =
        message.latencyMs ?: message.completedAt?.minus(message.createdAt)?.coerceAtLeast(0L)

    LaunchedEffect(copied) {
        if (copied) {
            delay(1_500L)
            copied = false
        }
    }

    Column(
        modifier =
            modifier.fillMaxWidth().combinedClickable(
                onClick = {},
                onLongClick = { if (hasContent) onQuote() },
            ),
        verticalArrangement = Arrangement.spacedBy(9.dp),
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
        if (message.isStreaming != true && hasContent) {
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(6.dp),
            ) {
                val copyLabel = stringResource(if (copied) R.string.copied else R.string.copy)
                IconButton(
                    onClick = { if (copyText(message.content)) copied = true },
                    modifier = Modifier.size(38.dp),
                ) {
                    Icon(
                        imageVector = if (copied) Icons.Rounded.Check else Icons.Rounded.ContentCopy,
                        contentDescription = copyLabel,
                        modifier = Modifier.size(17.dp),
                        tint = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
                // 长按正文仍保留快速引用，但显式按钮能让高频动作可发现，并与复制、分支形成
                // 一组只作用于当前 assistant 消息的局部操作，不占用页面级导航空间。
                IconButton(onClick = onQuote, modifier = Modifier.size(38.dp)) {
                    Icon(
                        Icons.Rounded.FormatQuote,
                        stringResource(R.string.quote_selection_title),
                        modifier = Modifier.size(18.dp),
                        tint = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
                if (forkIndex != null) {
                    IconButton(onClick = onFork, modifier = Modifier.size(38.dp)) {
                        Icon(
                            Icons.AutoMirrored.Rounded.CallSplit,
                            stringResource(R.string.fork),
                            modifier = Modifier.size(18.dp),
                            tint = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                }
                elapsedMs?.let {
                    Text(
                        text = formatMessageLatency(it),
                        modifier = Modifier.padding(start = 6.dp),
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        style = MaterialTheme.typography.labelSmall,
                    )
                }
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
