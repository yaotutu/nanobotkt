package com.nanobotkt.feature.chat

import android.content.ClipData
import android.content.ClipboardManager
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.CallSplit
import androidx.compose.material.icons.rounded.Check
import androidx.compose.material.icons.rounded.ContentCopy
import androidx.compose.material.icons.rounded.ExpandMore
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
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.nanobotkt.core.designsystem.NanobotThemeDefaults
import com.nanobotkt.core.model.FilePreviewPayload
import com.nanobotkt.core.model.UiMessage

/** 消息列表、消息气泡与文件预览；只负责渲染消息相关状态和转发事件。 */
@Composable
internal fun MessageList(
    listState: androidx.compose.foundation.lazy.LazyListState,
    state: ChatUiState,
    loadOlder: () -> Unit,
    onQuote: (String) -> Unit,
    onPreview: (String) -> Unit,
    onFork: (String, Int) -> Unit,
    modifier: Modifier = Modifier,
    autoFollow: Boolean = true,
) {
    val forkIndexes =
        remember(state.messages, state.userMessageOffset) {
            assistantForkIndexes(state.messages, state.userMessageOffset)
        }
    LaunchedEffect(state.messages.size) {
        if (autoFollow && state.messages.isNotEmpty())
            listState.animateScrollToItem(state.messages.lastIndex)
    }
    LazyColumn(
        state = listState,
        modifier = modifier,
        contentPadding =
            PaddingValues(
                start = NanobotThemeDefaults.spacing.sm,
                top = NanobotThemeDefaults.spacing.lg,
                end = NanobotThemeDefaults.spacing.sm,
                // Composer 已经是 Column 的独立底部区域，这里只保留消息与边界的呼吸空间。
                bottom = NanobotThemeDefaults.spacing.md,
            ),
        verticalArrangement = Arrangement.spacedBy(NanobotThemeDefaults.spacing.md),
    ) {
        if (state.hasMoreBefore) {
            item {
                TextButton(
                    onClick = loadOlder,
                    enabled = !state.loadingOlder,
                    modifier = Modifier.fillMaxWidth(),
                ) {
                    if (state.loadingOlder)
                        CircularProgressIndicator(Modifier.size(18.dp), strokeWidth = 2.dp)
                    else Text(stringResource(R.string.load_older))
                }
            }
        }
        itemsIndexed(state.messages, key = { _, message -> message.id }) { index, message ->
            MessageBubble(
                message = message,
                forkIndex = forkIndexes.getOrNull(index),
                onQuote = { onQuote(message.content) },
                onPreview = onPreview,
                onFork = { forkIndexes.getOrNull(index)?.let { onFork(message.id, it) } },
            )
        }
    }
}

@OptIn(ExperimentalFoundationApi::class)
@Composable
internal fun MessageBubble(
    message: UiMessage,
    forkIndex: Int?,
    onQuote: () -> Unit,
    onPreview: (String) -> Unit,
    onFork: () -> Unit,
) {
    val user = message.role == "user"
    val textColor = MaterialTheme.colorScheme.onSurface
    val mutedColor = MaterialTheme.colorScheme.onSurfaceVariant
    // 用户消息使用 primaryContainer，助手消息保持页面背景，阅读层级更接近 MD3 的 tonal surface。
    val userBubbleColor = MaterialTheme.colorScheme.primaryContainer
    val userTextColor = MaterialTheme.colorScheme.onPrimaryContainer
    val context = LocalContext.current
    val clipboard = remember(context) { context.getSystemService(ClipboardManager::class.java) }
    val copyText: (String) -> Boolean = { text ->
        clipboard?.let {
            runCatching { it.setPrimaryClip(ClipData.newPlainText("message", text)) }.isSuccess
        } ?: false
    }
    var copied by remember(message.id) { mutableStateOf(false) }
    var reasoningOpen by remember(message.id) { mutableStateOf(false) }
    LaunchedEffect(copied) {
        if (copied) {
            kotlinx.coroutines.delay(1_500L)
            copied = false
        }
    }
    val elapsedMs =
        message.latencyMs ?: message.completedAt?.minus(message.createdAt)?.coerceAtLeast(0L)

    if (user) {
        Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.End) {
            Column(horizontalAlignment = Alignment.End) {
                Surface(
                    modifier =
                        Modifier.widthIn(min = 54.dp, max = 320.dp)
                            .combinedClickable(
                                onClick = {},
                                onLongClick = { copyText(message.content) },
                            ),
                    shape = MaterialTheme.shapes.extraLarge,
                    color = userBubbleColor,
                    tonalElevation = 0.dp,
                    shadowElevation = 0.dp,
                ) {
                    Box(
                        modifier = Modifier.padding(horizontal = 18.dp, vertical = 10.dp),
                        contentAlignment = Alignment.Center,
                    ) {
                        Text(
                            text = message.content,
                            color = userTextColor,
                            style = MaterialTheme.typography.bodyLarge,
                        )
                    }
                }
                Spacer(Modifier.height(10.dp))
                IconButton(
                    onClick = { copyText(message.content) },
                    modifier = Modifier.size(40.dp),
                ) {
                    Icon(
                        Icons.Rounded.ContentCopy,
                        stringResource(R.string.copy),
                        modifier = Modifier.size(17.dp),
                        tint = mutedColor,
                    )
                }
            }
        }
        return
    }

    Column(
        modifier = Modifier.fillMaxWidth().combinedClickable(onClick = {}, onLongClick = onQuote)
    ) {
        val reasoning = message.reasoning
        if (!reasoning.isNullOrBlank()) {
            Row(
                modifier =
                    Modifier.height(28.dp)
                        .combinedClickable(
                            onClick = { reasoningOpen = !reasoningOpen },
                            onLongClick = {},
                        ),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text(
                    text =
                        when {
                            message.reasoningStreaming == true -> "Thinking…"
                            elapsedMs != null ->
                                "Thought for ${(elapsedMs / 1_000L).coerceAtLeast(1L)}s"
                            else -> "Thought"
                        },
                    color = mutedColor,
                    style = MaterialTheme.typography.bodyMedium,
                )
                Icon(
                    Icons.Rounded.ExpandMore,
                    contentDescription = null,
                    modifier = Modifier.padding(start = 3.dp).size(13.dp),
                    tint = mutedColor,
                )
            }
            AnimatedVisibility(reasoningOpen) {
                Text(
                    text = reasoning,
                    modifier = Modifier.padding(top = 4.dp, bottom = 10.dp),
                    color = mutedColor,
                    style = MaterialTheme.typography.bodySmall.copy(lineHeight = 20.sp),
                )
            }
            Spacer(Modifier.height(22.dp))
        }

        if (message.content.isNotBlank()) {
            Text(
                text = message.content,
                color = textColor,
                style = MaterialTheme.typography.bodyLarge,
            )
        }
        message.toolEvents
            ?.takeIf { it.isNotEmpty() }
            ?.let { tools ->
                Text(
                    pluralStringResource(R.plurals.tool_count, tools.size, tools.size),
                    modifier = Modifier.padding(top = 8.dp),
                    style = MaterialTheme.typography.labelMedium,
                    color = mutedColor,
                )
            }
        message.fileEdits?.forEach { edit ->
            Row(
                modifier = Modifier.fillMaxWidth().padding(top = 5.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(4.dp),
            ) {
                Text(
                    "${edit.path}  +${edit.added} -${edit.deleted}",
                    modifier = Modifier.weight(1f),
                    style = MaterialTheme.typography.labelMedium,
                    color = MaterialTheme.colorScheme.primary,
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis,
                )
                TextButton(
                    onClick = { onPreview(edit.absolutePath ?: edit.path) },
                    contentPadding = PaddingValues(horizontal = 8.dp, vertical = 0.dp),
                ) {
                    Text(stringResource(R.string.file_preview))
                }
            }
        }
        if (message.isStreaming == true) {
            CircularProgressIndicator(
                modifier = Modifier.padding(top = 8.dp).size(14.dp),
                strokeWidth = 1.5.dp,
                color = mutedColor,
            )
        }
        if (message.isStreaming != true && message.content.isNotBlank()) {
            Row(
                modifier = Modifier.padding(top = 9.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                val copyLabel = stringResource(if (copied) R.string.copied else R.string.copy)
                IconButton(
                    onClick = { if (copyText(message.content)) copied = true },
                    modifier = Modifier.size(40.dp),
                ) {
                    Icon(
                        imageVector =
                            if (copied) Icons.Rounded.Check else Icons.Rounded.ContentCopy,
                        contentDescription = copyLabel,
                        modifier = Modifier.size(17.dp),
                        tint = mutedColor,
                    )
                }
                if (forkIndex != null) {
                    IconButton(onClick = onFork, modifier = Modifier.size(40.dp)) {
                        Icon(
                            Icons.AutoMirrored.Rounded.CallSplit,
                            stringResource(R.string.fork),
                            modifier = Modifier.size(18.dp),
                            tint = mutedColor,
                        )
                    }
                }
                elapsedMs?.let {
                    Text(
                        text = formatMessageLatency(it),
                        modifier = Modifier.padding(start = 8.dp),
                        color = mutedColor,
                        style = MaterialTheme.typography.labelSmall,
                    )
                }
            }
        }
    }
}

@Composable
internal fun FilePreviewDialog(
    preview: FilePreviewPayload?,
    loading: Boolean,
    failed: Boolean,
    onDismiss: () -> Unit,
) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(R.string.file_preview_title)) },
        text = {
            when {
                loading -> CircularProgressIndicator(modifier = Modifier.size(24.dp))
                failed ->
                    Text(
                        stringResource(R.string.file_preview_load_failed),
                        color = MaterialTheme.colorScheme.error,
                    )
                preview != null ->
                    Column(
                        modifier =
                            Modifier.fillMaxWidth()
                                .heightIn(max = 460.dp)
                                .verticalScroll(rememberScrollState()),
                        verticalArrangement = Arrangement.spacedBy(6.dp),
                    ) {
                        Text(
                            preview.displayPath,
                            style = MaterialTheme.typography.titleSmall,
                            fontWeight = FontWeight.Medium,
                        )
                        Text(
                            stringResource(R.string.file_preview_language, preview.language),
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                        Text(
                            stringResource(R.string.file_preview_size, preview.size),
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                        if (preview.truncated) {
                            Text(
                                stringResource(R.string.file_preview_truncated),
                                style = MaterialTheme.typography.labelSmall,
                                color = MaterialTheme.colorScheme.error,
                            )
                        }
                        Surface(
                            modifier = Modifier.fillMaxWidth(),
                            color = MaterialTheme.colorScheme.surfaceVariant,
                            shape = MaterialTheme.shapes.small,
                        ) {
                            Text(
                                preview.content,
                                modifier = Modifier.padding(10.dp),
                                style = MaterialTheme.typography.bodySmall,
                            )
                        }
                    }
            }
        },
        confirmButton = { TextButton(onClick = onDismiss) { Text(stringResource(R.string.close)) } },
    )
}

internal fun formatMessageLatency(durationMs: Long): String {
    if (durationMs < 1_000L) return "${durationMs.coerceAtLeast(0L)}ms"
    val tenths = (durationMs.coerceAtLeast(0L) + 50L) / 100L
    return if (tenths % 10L == 0L) "${tenths / 10L}s" else "${tenths / 10L}.${tenths % 10L}s"
}
