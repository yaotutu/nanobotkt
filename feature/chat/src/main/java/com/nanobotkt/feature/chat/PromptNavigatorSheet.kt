package com.nanobotkt.feature.chat

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.Text
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.nanobotkt.core.model.UiMessage
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * 当前会话的 Prompt 导航使用 Bottom Sheet，而不是占据几乎整屏的右侧 Drawer。
 *
 * 该列表是低频的“回看并定位”工具，只展示已进入历史的用户 Prompt 与 Automation 指令；第一阶段
 * 不提供搜索、筛选和收藏。点击条目后先关闭 Sheet，再由上层滚动并短暂高亮目标消息。
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun PromptNavigatorSheet(
    messages: List<UiMessage>,
    visible: Boolean,
    onClose: () -> Unit,
    onJumpToPrompt: (String) -> Unit,
) {
    if (!visible) return

    val prompts = remember(messages) { extractPromptAnchors(messages) }
    val sourceByMessageId =
        remember(messages) {
            messages.associate { message -> message.id to message.source }
        }
    val sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = false)

    ModalBottomSheet(
        onDismissRequest = onClose,
        sheetState = sheetState,
    ) {
        Column(
            modifier = Modifier.fillMaxWidth().padding(bottom = 12.dp),
            verticalArrangement = Arrangement.spacedBy(4.dp),
        ) {
            Text(
                text = stringResource(R.string.prompt_navigator_title),
                modifier = Modifier.padding(horizontal = 20.dp, vertical = 8.dp),
                style = MaterialTheme.typography.titleLarge,
                fontWeight = FontWeight.SemiBold,
            )

            if (prompts.isEmpty()) {
                Text(
                    text = stringResource(R.string.prompt_navigator_empty),
                    modifier = Modifier.padding(horizontal = 20.dp, vertical = 28.dp),
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    style = MaterialTheme.typography.bodyMedium,
                )
            } else {
                LazyColumn(modifier = Modifier.fillMaxWidth().heightIn(max = 560.dp)) {
                    items(prompts, key = PromptNavigatorItem::stableId) { prompt ->
                        val source = sourceByMessageId[prompt.messageId]
                        PromptNavigatorRow(
                            item = prompt,
                            automationLabel =
                                source?.takeIf { it.kind.equals("automation", ignoreCase = true) }
                                    ?.label,
                            onClick = {
                                // 关闭动作必须先发生，避免 Sheet 退场动画继续拦截目标消息上的手势。
                                onClose()
                                onJumpToPrompt(prompt.messageId)
                            },
                        )
                        HorizontalDivider(
                            modifier = Modifier.padding(start = 56.dp),
                            color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.45f),
                        )
                    }
                }
            }
        }
    }
}

@Composable
private fun PromptNavigatorRow(
    item: PromptNavigatorItem,
    automationLabel: String?,
    onClick: () -> Unit,
) {
    Row(
        modifier =
            Modifier.fillMaxWidth()
                .clickable(onClick = onClick)
                .padding(horizontal = 20.dp, vertical = 14.dp),
        horizontalArrangement = Arrangement.spacedBy(12.dp),
        verticalAlignment = Alignment.Top,
    ) {
        Text(
            text = (item.ordinal + 1).toString(),
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            style = MaterialTheme.typography.labelLarge,
        )
        Column(modifier = Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(4.dp)) {
            automationLabel?.let { label ->
                Text(
                    text = stringResource(R.string.automation_source, label),
                    color = MaterialTheme.colorScheme.primary,
                    style = MaterialTheme.typography.labelSmall,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            }
            Text(
                text = item.label,
                style = MaterialTheme.typography.bodyMedium,
                fontWeight = FontWeight.Medium,
                maxLines = 2,
                overflow = TextOverflow.Ellipsis,
            )
            if (item.createdAt > 0L) {
                Text(
                    text = promptNavigatorTime(item.createdAt),
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    style = MaterialTheme.typography.labelSmall,
                )
            }
        }
    }
}

/** 时间只辅助区分 Prompt，不显示秒级精度，避免列表产生无意义的视觉噪声。 */
private fun promptNavigatorTime(createdAt: Long): String =
    SimpleDateFormat("MMM d · HH:mm", Locale.getDefault()).format(Date(createdAt))
