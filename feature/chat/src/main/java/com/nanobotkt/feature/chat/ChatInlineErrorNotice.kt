package com.nanobotkt.feature.chat

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.sizeIn
import androidx.compose.foundation.layout.width
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Close
import androidx.compose.material.icons.rounded.WarningAmber
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp

internal const val CHAT_INLINE_ERROR_TEST_TAG = "chat_inline_error"
internal const val CHAT_INLINE_ERROR_DISMISS_TEST_TAG = "chat_inline_error_dismiss"

/**
 * 显示在 Composer 上方的可关闭错误条。
 *
 * 该组件只负责视觉和关闭事件，不直接清理 ViewModel/Repository 错误。关闭状态由 ChatScreen
 * 以错误 key 维护，因此不会破坏发送、模型切换或时间轴状态机。Assertive live region 让 TalkBack
 * 在错误首次出现时主动播报；关闭按钮仍保留独立节点和至少 48dp 触控区。
 */
@Composable
internal fun ChatInlineErrorNotice(
    presentation: ChatErrorPresentation,
    onDismiss: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val colors = MaterialTheme.colorScheme

    Surface(
        modifier =
            modifier
                .fillMaxWidth()
                .testTag(CHAT_INLINE_ERROR_TEST_TAG)
                .semantics { liveRegion = LiveRegionMode.Assertive },
        shape = MaterialTheme.shapes.large,
        color = colors.errorContainer.copy(alpha = 0.72f),
        contentColor = colors.onErrorContainer,
        border = BorderStroke(1.dp, colors.error.copy(alpha = 0.28f)),
    ) {
        Row(
            modifier = Modifier.padding(start = 12.dp, top = 8.dp, end = 4.dp, bottom = 8.dp),
            verticalAlignment = Alignment.Top,
        ) {
            Icon(
                imageVector = Icons.Rounded.WarningAmber,
                contentDescription = null,
                modifier = Modifier.padding(top = 2.dp).size(20.dp),
            )
            Spacer(Modifier.width(8.dp))
            Column(
                modifier = Modifier.weight(1f).padding(vertical = 2.dp),
            ) {
                Text(
                    text = stringResource(presentation.titleRes),
                    style = MaterialTheme.typography.labelLarge,
                )
                Text(
                    text = stringResource(presentation.bodyRes),
                    modifier = Modifier.padding(top = 2.dp),
                    style = MaterialTheme.typography.bodySmall,
                    color = colors.onErrorContainer.copy(alpha = 0.82f),
                )
            }
            IconButton(
                onClick = onDismiss,
                modifier =
                    Modifier.sizeIn(minWidth = 48.dp, minHeight = 48.dp)
                        .testTag(CHAT_INLINE_ERROR_DISMISS_TEST_TAG),
            ) {
                Icon(
                    imageVector = Icons.Rounded.Close,
                    contentDescription = stringResource(R.string.dismiss_chat_error),
                    modifier = Modifier.size(18.dp),
                )
            }
        }
    }
}
