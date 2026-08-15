package com.nanobotkt.feature.chat

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Description
import androidx.compose.material.icons.rounded.ExpandLess
import androidx.compose.material.icons.rounded.ExpandMore
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.nanobotkt.core.model.UiFileEdit

private const val COLLAPSED_DIFF_LINE_COUNT = 18

/** 单个文件修改活动；摘要始终可见，Diff 与完整文件预览分别使用独立入口。 */
@Composable
internal fun FileEditItem(
    edit: UiFileEdit,
    onPreview: (String) -> Unit,
    modifier: Modifier = Modifier,
) {
    val diffText = edit.diff?.text.orEmpty()
    val diffLines = rememberDiffLines(diffText)
    var diffOpen by rememberSaveable(edit.callId, edit.path) { mutableStateOf(false) }
    var showAll by rememberSaveable(edit.callId, edit.path, "show-all") { mutableStateOf(false) }
    val canExpandDiff = diffText.isNotBlank() && edit.binary != true
    val statusColor =
        if (edit.status.isFailureStatus() || !edit.error.isNullOrBlank()) {
            MaterialTheme.colorScheme.error
        } else {
            MaterialTheme.colorScheme.onSurfaceVariant
        }

    Surface(
        modifier = modifier.fillMaxWidth(),
        color = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.72f),
        shape = MaterialTheme.shapes.medium,
    ) {
        Column(modifier = Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 8.dp)) {
            Row(
                modifier =
                    Modifier.fillMaxWidth().let { base ->
                        if (canExpandDiff) base.clickable { diffOpen = !diffOpen } else base
                    },
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                Icon(
                    Icons.Rounded.Description,
                    contentDescription = null,
                    tint = MaterialTheme.colorScheme.primary,
                )
                Column(modifier = Modifier.weight(1f)) {
                    Text(
                        text = edit.path,
                        maxLines = 2,
                        overflow = TextOverflow.Ellipsis,
                        style = MaterialTheme.typography.bodyMedium,
                        fontWeight = FontWeight.Medium,
                    )
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        Text(
                            text = "+${edit.added}",
                            color = MaterialTheme.colorScheme.primary,
                            style = MaterialTheme.typography.labelSmall,
                        )
                        Text(
                            text = "-${edit.deleted}",
                            color = MaterialTheme.colorScheme.error,
                            style = MaterialTheme.typography.labelSmall,
                        )
                        Text(
                            text = edit.status,
                            color = statusColor,
                            style = MaterialTheme.typography.labelSmall,
                        )
                    }
                }
                if (canExpandDiff) {
                    Icon(
                        imageVector = if (diffOpen) Icons.Rounded.ExpandLess else Icons.Rounded.ExpandMore,
                        contentDescription = null,
                        tint = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
                TextButton(
                    onClick = { onPreview(edit.absolutePath ?: edit.path) },
                    contentPadding = PaddingValues(horizontal = 6.dp, vertical = 0.dp),
                ) {
                    Text(stringResourceCompat(R.string.file_preview))
                }
            }

            edit.error?.takeIf(String::isNotBlank)?.let { error ->
                Text(
                    text = error,
                    modifier = Modifier.padding(top = 6.dp),
                    color = MaterialTheme.colorScheme.error,
                    style = MaterialTheme.typography.bodySmall,
                )
            }

            AnimatedVisibility(diffOpen && canExpandDiff) {
                Column(modifier = Modifier.padding(top = 8.dp)) {
                    Surface(
                        modifier = Modifier.fillMaxWidth(),
                        color = MaterialTheme.colorScheme.surface,
                        shape = MaterialTheme.shapes.small,
                    ) {
                        Text(
                            text =
                                if (showAll || diffLines.size <= COLLAPSED_DIFF_LINE_COUNT) {
                                    diffText
                                } else {
                                    diffLines.take(COLLAPSED_DIFF_LINE_COUNT).joinToString("\n")
                                },
                            modifier = Modifier.padding(10.dp),
                            style = MaterialTheme.typography.bodySmall,
                            fontFamily = FontFamily.Monospace,
                        )
                    }
                    if (diffLines.size > COLLAPSED_DIFF_LINE_COUNT) {
                        TextButton(onClick = { showAll = !showAll }) {
                            Text(
                                stringResourceCompat(
                                    if (showAll) R.string.file_diff_show_less
                                    else R.string.file_diff_show_more
                                )
                            )
                        }
                    }
                    if (edit.diff?.truncated == true) {
                        Text(
                            text = stringResourceCompat(R.string.file_diff_truncated),
                            color = MaterialTheme.colorScheme.error,
                            style = MaterialTheme.typography.labelSmall,
                        )
                    }
                }
            }
        }
    }
}

/** 避免每次重组都重新切割可能较大的 Diff 文本。 */
@Composable
private fun rememberDiffLines(diffText: String): List<String> =
    androidx.compose.runtime.remember(diffText) { diffText.lines() }

/** 状态值来自多版本服务端，使用集合式兼容而不是依赖单一枚举拼写。 */
private fun String.isFailureStatus(): Boolean =
    lowercase() in setOf("error", "failed", "failure", "cancelled", "canceled")

/** 简化同文件内的资源调用，保持下方布局代码可读。 */
@Composable
private fun stringResourceCompat(id: Int): String = androidx.compose.ui.res.stringResource(id)
