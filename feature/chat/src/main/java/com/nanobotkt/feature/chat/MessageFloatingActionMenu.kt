package com.nanobotkt.feature.chat

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.CallSplit
import androidx.compose.material.icons.rounded.ContentCopy
import androidx.compose.material.icons.rounded.FormatQuote
import androidx.compose.material.icons.rounded.Visibility
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Popup
import androidx.compose.ui.window.PopupProperties

/**
 * 消息附近的轻量悬浮菜单。Popup 使用 focusable=true，因此点击外部会由系统关闭，而不是再叠加一层
 * 全屏手势拦截。菜单保持单行“图标 + 文字”，更接近常见聊天软件的长按反馈。
 */
@Composable
internal fun MessageFloatingActionMenu(
    expanded: Boolean,
    actions: List<MessageAction>,
    placeBelow: Boolean,
    onDismiss: () -> Unit,
    onAction: (MessageAction) -> Unit,
) {
    if (!expanded || actions.isEmpty()) return

    Popup(
        alignment = if (placeBelow) Alignment.TopCenter else Alignment.BottomCenter,
        offset = IntOffset(x = 0, y = if (placeBelow) 12 else -12),
        onDismissRequest = onDismiss,
        properties = PopupProperties(focusable = true, clippingEnabled = true),
    ) {
        Surface(
            color = MaterialTheme.colorScheme.inverseSurface,
            contentColor = MaterialTheme.colorScheme.inverseOnSurface,
            shape = MaterialTheme.shapes.large,
            tonalElevation = 3.dp,
            shadowElevation = 4.dp,
        ) {
            Row(
                modifier = Modifier.padding(horizontal = 4.dp, vertical = 4.dp),
                horizontalArrangement = Arrangement.spacedBy(2.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                actions.forEach { action ->
                    MessageActionItem(action = action, onClick = { onAction(action) })
                }
            }
        }
    }
}

@Composable
private fun MessageActionItem(
    action: MessageAction,
    onClick: () -> Unit,
) {
    val icon: ImageVector
    val label: String
    when (action) {
        MessageAction.COPY -> {
            icon = Icons.Rounded.ContentCopy
            label = stringResource(R.string.copy)
        }
        MessageAction.QUOTE -> {
            icon = Icons.Rounded.FormatQuote
            label = stringResource(R.string.quote_action)
        }
        MessageAction.FORK -> {
            icon = Icons.AutoMirrored.Rounded.CallSplit
            label = stringResource(R.string.fork)
        }
        MessageAction.VIEW -> {
            icon = Icons.Rounded.Visibility
            label = stringResource(R.string.view_message)
        }
    }

    Surface(onClick = onClick, color = androidx.compose.ui.graphics.Color.Transparent) {
        Row(
            modifier = Modifier.padding(horizontal = 8.dp, vertical = 8.dp),
            horizontalArrangement = Arrangement.spacedBy(5.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Icon(icon, contentDescription = null)
            Text(text = label, style = MaterialTheme.typography.labelMedium)
        }
    }
}
