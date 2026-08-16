package com.nanobotkt.feature.chat

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import com.nanobotkt.core.designsystem.NanobotThemeDefaults
import com.nanobotkt.core.model.FilePreviewPayload

/** 消息列表只负责编排时间轴单元；具体消息、Activity 和媒体分别由独立组件渲染。 */
@Composable
internal fun MessageList(
    listState: LazyListState,
    state: ChatUiState,
    timelineItems: List<ChatTimelineItem>,
    loadOlder: () -> Unit,
    onQuote: (String) -> Unit,
    onPreview: (String) -> Unit,
    onFork: (String, Int) -> Unit,
    onRetry: (String) -> Unit,
    resolveMediaUrl: (String) -> String,
    highlightedMessageId: String?,
    menuDismissSignal: Int,
    modifier: Modifier = Modifier,
    autoFollow: Boolean = true,
) {
    val forkIndexes =
        remember(state.messages, state.userMessageOffset) {
            assistantForkIndexes(state.messages, state.userMessageOffset)
        }
    val tailSignature = remember(state.messages) { timelineTailSignature(state.messages) }
    val timelineStartIndex = if (state.hasMoreBefore) 1 else 0
    // 播放互斥只属于当前消息列表实例。会话切换导致 MessageList 重建后不会继续持有旧附件状态。
    val playbackCoordinator = remember(state.sessionKey) { TimelinePlaybackCoordinator() }

    LaunchedEffect(tailSignature, autoFollow, timelineItems.lastOrNull()?.key) {
        // 只观察尾消息签名，不观察列表总长度：加载更早历史只会在头部插入，不应把用户从顶部
        // 强制拉回底部。新消息、流式正文和工具状态都会改变尾签名，因此在 autoFollow=true 时跟随。
        if (autoFollow && timelineItems.isNotEmpty()) {
            listState.scrollToTimelineBottom(timelineStartIndex + timelineItems.lastIndex)
        }
    }

    LazyColumn(
        state = listState,
        modifier = modifier,
        contentPadding =
            PaddingValues(
                // 文档式 Assistant 正文需要稳定页边距。20dp 比旧的 12dp 更接近阅读页面，
                // 同时仍为用户气泡和长代码保留足够宽度。
                start = 20.dp,
                top = NanobotThemeDefaults.spacing.sm,
                end = 20.dp,
                // Composer 是独立底部区域，这里只保留消息与输入框之间的呼吸空间。
                bottom = NanobotThemeDefaults.spacing.md,
            ),
        // 不再用统一 spacedBy：用户回合、Activity 与 Assistant 正文属于不同层级，
        // 必须根据相邻类型决定间距，才能避免短对话被机械地拉成多张卡片。
        verticalArrangement = Arrangement.Top,
    ) {
        if (state.hasMoreBefore) {
            item(key = "load-older") {
                TextButton(
                    onClick = loadOlder,
                    enabled = !state.loadingOlder,
                    modifier = Modifier.fillMaxWidth(),
                ) {
                    if (state.loadingOlder) {
                        CircularProgressIndicator(Modifier.size(18.dp), strokeWidth = 2.dp)
                    } else {
                        Text(stringResource(R.string.load_older))
                    }
                }
            }
        }

        itemsIndexed(timelineItems, key = { _, item -> item.key }) { index, item ->
            val previous = timelineItems.getOrNull(index - 1)
            Box(
                modifier =
                    Modifier.fillMaxWidth()
                        .padding(top = timelineItemTopSpacing(previous = previous, current = item))
            ) {
                when (item) {
                    is ChatTimelineItem.UserMessage ->
                        UserTimelineMessage(
                            message = item.message,
                            deliveryState = item.deliveryState,
                            resolveUrl = resolveMediaUrl,
                            playbackCoordinator = playbackCoordinator,
                            onQuote = { onQuote(item.message.content) },
                            // Queue 是本地待发送状态，历史用户消息也没有稳定的 beforeUserIndex；
                            // 在后端语义未确认前不伪造用户消息 Fork 入口。
                            onFork = null,
                            // 重新发送只对真实 FAILED 用户消息开放；成功历史和 Queue 不暴露该入口。
                            onRetry =
                                if (item.deliveryState == UserMessageDeliveryState.FAILED) {
                                    { onRetry(item.message.id) }
                                } else {
                                    null
                                },
                            menuDismissSignal = menuDismissSignal,
                            highlighted = highlightedMessageId == item.message.id,
                        )

                    is ChatTimelineItem.AssistantMessage -> {
                        val forkIndex = forkIndexes.getOrNull(item.originalIndex)
                        AssistantTimelineMessage(
                            message = item.message,
                            forkIndex = forkIndex,
                            resolveUrl = resolveMediaUrl,
                            playbackCoordinator = playbackCoordinator,
                            onQuote = { onQuote(item.message.content) },
                            onFork = {
                                forkIndex?.let { onFork(item.message.id, it) }
                            },
                            menuDismissSignal = menuDismissSignal,
                        )
                    }

                    is ChatTimelineItem.AgentActivity ->
                        AgentActivityCluster(item = item, onPreview = onPreview)

                    is ChatTimelineItem.Marker -> TimelineMarker(item)
                }
            }
        }
    }
}

/**
 * 计算相邻时间轴单元之间的层级间距。
 *
 * 用户消息代表新回合，和上一轮保持 24dp；同一回合内的过程摘要与回答只保留 8–12dp。
 * 这种规则比全列表统一 16dp 更能表达“一个回合是一个整体”。
 */
private fun timelineItemTopSpacing(
    previous: ChatTimelineItem?,
    current: ChatTimelineItem,
): Dp =
    when {
        previous == null -> 0.dp
        current is ChatTimelineItem.UserMessage -> 24.dp
        current is ChatTimelineItem.Marker || previous is ChatTimelineItem.Marker -> 16.dp
        previous is ChatTimelineItem.UserMessage -> 12.dp
        previous is ChatTimelineItem.AgentActivity && current is ChatTimelineItem.AssistantMessage -> 8.dp
        previous is ChatTimelineItem.AssistantMessage && current is ChatTimelineItem.AgentActivity -> 8.dp
        else -> 12.dp
    }

/**
 * 将消息列表定位到真实内容尾部，而不是只把最后一个时间轴单元的顶部对齐到视口顶部。
 *
 * Assistant 正文、Reasoning 或文件 Diff 可能高于一整屏。若只调用 `scrollToItem(lastIndex)`，
 * Compose 会停在最后一个单元的开头，界面仍然可以继续向下滚动，“新消息”按钮也不会消失。
 * 使用最大正偏移让 LazyColumn 在测量时把请求裁剪到可滚动上限，因而无论最后一个单元多高，
 * 都会稳定落在时间轴真正的底部。这里使用立即跳转而不是长距离动画，避免超长会话产生持续动画，
 * 也避免流式 delta 高频到达时积压多个滚动动画。
 */
internal suspend fun LazyListState.scrollToTimelineBottom(lastIndex: Int) {
    if (lastIndex < 0) return
    scrollToItem(index = lastIndex, scrollOffset = Int.MAX_VALUE)
}

/** 未知角色的真实记录使用居中小标记，避免被误认成 assistant 回答。 */
@Composable
private fun TimelineMarker(item: ChatTimelineItem.Marker) {
    Surface(
        modifier = Modifier.fillMaxWidth(),
        color = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.55f),
        shape = MaterialTheme.shapes.medium,
    ) {
        Text(
            text = item.message.content.ifBlank { item.message.role },
            modifier = Modifier.padding(horizontal = 12.dp, vertical = 8.dp),
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            style = MaterialTheme.typography.bodySmall,
        )
    }
}

/**
 * 生成尾部内容签名。不能直接依赖 messages.size，因为 reasoning delta、tool phase 和 file edit
 * 更新通常复用同一条消息；也不能使用整列表 hashCode，否则加载更早历史会误触发回到底部。
 */
private fun timelineTailSignature(messages: List<com.nanobotkt.core.model.UiMessage>): Int? {
    val tail = messages.lastOrNull() ?: return null
    return listOf(
        tail.id,
        tail.content.length,
        tail.content.hashCode(),
        tail.reasoning?.length,
        tail.reasoning?.hashCode(),
        tail.reasoningStreaming,
        tail.isStreaming,
        tail.toolEvents?.map { listOf(it.callId, it.phase, it.error?.hashCode(), it.result?.hashCode()) },
        tail.fileEdits?.map { listOf(it.callId, it.path, it.status, it.added, it.deleted, it.error) },
        tail.media?.size,
        tail.images?.size,
    ).hashCode()
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
                            stringResource(
                                R.string.file_preview_language,
                                preview.language,
                            ),
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                        Text(
                            stringResource(
                                R.string.file_preview_size,
                                preview.size,
                            ),
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                        if (preview.truncated) {
                            Text(
                                stringResource(
                                    R.string.file_preview_truncated
                                ),
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
        confirmButton = {
            TextButton(onClick = onDismiss) {
                Text(stringResource(R.string.close))
            }
        },
    )
}

internal fun formatMessageLatency(durationMs: Long): String {
    if (durationMs < 1_000L) return "${durationMs.coerceAtLeast(0L)}ms"
    val tenths = (durationMs.coerceAtLeast(0L) + 50L) / 100L
    return if (tenths % 10L == 0L) {
        "${tenths / 10L}s"
    } else {
        "${tenths / 10L}.${tenths % 10L}s"
    }
}
