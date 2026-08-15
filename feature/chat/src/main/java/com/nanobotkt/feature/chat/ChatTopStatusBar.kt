package com.nanobotkt.feature.chat

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.Toc
import androidx.compose.material.icons.rounded.Checklist
import androidx.compose.material.icons.rounded.ChatBubbleOutline
import androidx.compose.material.icons.rounded.Close
import androidx.compose.material.icons.rounded.DarkMode
import androidx.compose.material.icons.rounded.Folder
import androidx.compose.material.icons.rounded.Add
import androidx.compose.material.icons.rounded.Settings
import androidx.compose.material.icons.rounded.MoreVert
import androidx.compose.material.icons.rounded.SmartToy
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp

/**
 * 聊天首页唯一的顶部常驻区域。
 *
 * 左侧设置按钮直接进入统一控制中心；中间只展示会话身份、真实运行状态和本地 Queue；右侧
 * “新话题”和更多菜单分别承载会话创建与当前会话配置，避免再次引入隐藏的全局抽屉。
 */
@Composable
internal fun ChatTopStatusBar(
    title: String,
    status: ChatHeaderStatus,
    queuedPrompts: List<QueuedPrompt>,
    queueOpen: Boolean,
    configMenuOpen: Boolean,
    hasPromptNavigator: Boolean,
    hasSessionInfo: Boolean,
    hasAccessSettings: Boolean,
    onOpenSettings: () -> Unit,
    onNewConversation: () -> Unit,
    onQueueOpenChange: (Boolean) -> Unit,
    onConfigMenuOpenChange: (Boolean) -> Unit,
    onRemoveQueuedPrompt: (String) -> Unit,
    onOpenPromptNavigator: () -> Unit,
    onOpenSessionInfo: () -> Unit,
    onOpenConversationList: () -> Unit,
    onToggleTheme: () -> Unit,
    onOpenModel: () -> Unit,
    onOpenAccess: () -> Unit,
) {
    val muted = MaterialTheme.colorScheme.onSurfaceVariant

    Row(
        modifier =
            Modifier.fillMaxWidth()
                .background(MaterialTheme.colorScheme.surface)
                .statusBarsPadding()
                .height(64.dp)
                .padding(horizontal = 4.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        IconButton(onClick = onOpenSettings, modifier = Modifier.size(48.dp)) {
            Icon(
                Icons.Rounded.Settings,
                contentDescription = stringResource(R.string.open_settings),
                modifier = Modifier.size(20.dp),
                tint = muted,
            )
        }
        Column(modifier = Modifier.weight(1f).padding(horizontal = 8.dp)) {
            Text(
                text = title.ifBlank { stringResource(R.string.conversation_list_title) },
                style = MaterialTheme.typography.titleMedium,
                color = MaterialTheme.colorScheme.onSurface,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            Row(
                horizontalArrangement = Arrangement.spacedBy(6.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                HeaderStatusLabel(status)
                if (queuedPrompts.isNotEmpty()) {
                    QueueStatusMenu(
                        prompts = queuedPrompts,
                        expanded = queueOpen,
                        onExpandedChange = onQueueOpenChange,
                        onRemove = onRemoveQueuedPrompt,
                    )
                }
            }
        }
        // 新建会话提升为顶栏一级操作；它与会话列表 Sheet 中的入口复用同一回调，
        // 因而不会绕过 Root 的 drafting-new-topic 竞态保护。
        IconButton(onClick = onNewConversation, modifier = Modifier.size(48.dp)) {
            Icon(
                Icons.Rounded.Add,
                contentDescription = stringResource(R.string.new_topic),
                modifier = Modifier.size(22.dp),
                tint = muted,
            )
        }
        Box {
            IconButton(onClick = { onConfigMenuOpenChange(true) }, modifier = Modifier.size(48.dp)) {
                Icon(
                    Icons.Rounded.MoreVert,
                    contentDescription = stringResource(R.string.current_session_settings),
                    modifier = Modifier.size(22.dp),
                    tint = muted,
                )
            }
            DropdownMenu(
                expanded = configMenuOpen,
                onDismissRequest = { onConfigMenuOpenChange(false) },
            ) {
                if (hasSessionInfo) {
                    SessionConfigMenuItem(
                        label = stringResource(R.string.session_info_title),
                        icon = Icons.AutoMirrored.Rounded.Toc,
                        onClick = {
                            onConfigMenuOpenChange(false)
                            onOpenSessionInfo()
                        },
                    )
                }
                if (hasPromptNavigator) {
                    SessionConfigMenuItem(
                        label = stringResource(R.string.prompt_navigator_open),
                        icon = Icons.Rounded.Checklist,
                        onClick = {
                            onConfigMenuOpenChange(false)
                            onOpenPromptNavigator()
                        },
                    )
                }
                // 会话列表和主题切换属于首页级快捷操作，不能因为“当前会话配置”重构
                // 就被遗漏；会话列表仍由 ChatScreen 打开同一个 Bottom Sheet，主题切换
                // 则回到 AppViewModel 持久化用户偏好，不在 feature 层复制状态。
                SessionConfigMenuItem(
                    label = stringResource(R.string.open_conversation_list),
                    icon = Icons.Rounded.ChatBubbleOutline,
                    onClick = {
                        onConfigMenuOpenChange(false)
                        onOpenConversationList()
                    },
                )
                SessionConfigMenuItem(
                    label = stringResource(R.string.model_select_title),
                    icon = Icons.Rounded.SmartToy,
                    onClick = {
                        onConfigMenuOpenChange(false)
                        onOpenModel()
                    },
                )
                if (hasAccessSettings) {
                    SessionConfigMenuItem(
                        label = stringResource(R.string.workspace_access_title),
                        icon = Icons.Rounded.Folder,
                        onClick = {
                            onConfigMenuOpenChange(false)
                            onOpenAccess()
                        },
                    )
                }
                SessionConfigMenuItem(
                    label = stringResource(R.string.toggle_theme),
                    icon = Icons.Rounded.DarkMode,
                    onClick = {
                        onConfigMenuOpenChange(false)
                        onToggleTheme()
                    },
                )
            }
        }
    }
}

@Composable
private fun HeaderStatusLabel(status: ChatHeaderStatus) {
    val text =
        when (status) {
            ChatHeaderStatus.IDLE -> stringResource(R.string.chat_status_idle)
            ChatHeaderStatus.RUNNING -> stringResource(R.string.chat_status_running)
            ChatHeaderStatus.RECONNECTING -> stringResource(R.string.chat_status_reconnecting)
            ChatHeaderStatus.FAILED -> stringResource(R.string.chat_status_failed)
        }
    val color =
        when (status) {
            ChatHeaderStatus.IDLE -> MaterialTheme.colorScheme.onSurfaceVariant
            ChatHeaderStatus.RUNNING -> MaterialTheme.colorScheme.primary
            ChatHeaderStatus.RECONNECTING -> MaterialTheme.colorScheme.tertiary
            ChatHeaderStatus.FAILED -> MaterialTheme.colorScheme.error
        }
    val containerColor =
        when (status) {
            ChatHeaderStatus.IDLE -> MaterialTheme.colorScheme.surfaceContainerHighest
            ChatHeaderStatus.RUNNING -> MaterialTheme.colorScheme.primaryContainer
            ChatHeaderStatus.RECONNECTING -> MaterialTheme.colorScheme.tertiaryContainer
            ChatHeaderStatus.FAILED -> MaterialTheme.colorScheme.errorContainer
        }
    StatusLabel(text = text, color = color, containerColor = containerColor)
}

/** Queue 是本地 Composer 状态，只提供查看与删除，不伪造后端队列操作。 */
@Composable
private fun QueueStatusMenu(
    prompts: List<QueuedPrompt>,
    expanded: Boolean,
    onExpandedChange: (Boolean) -> Unit,
    onRemove: (String) -> Unit,
) {
    Box {
        Surface(
            onClick = { onExpandedChange(true) },
            shape = MaterialTheme.shapes.small,
            color = MaterialTheme.colorScheme.surfaceContainerHighest,
            contentColor = MaterialTheme.colorScheme.onSurfaceVariant,
        ) {
            Text(
                text = stringResource(R.string.queued_count, prompts.size),
                modifier = Modifier.padding(horizontal = 8.dp, vertical = 2.dp),
                style = MaterialTheme.typography.labelSmall,
                maxLines = 1,
            )
        }
        DropdownMenu(
            expanded = expanded,
            onDismissRequest = { onExpandedChange(false) },
        ) {
            Text(
                text = stringResource(R.string.queued_prompts_title),
                modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp),
                style = MaterialTheme.typography.labelMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            prompts.forEach { prompt ->
                DropdownMenuItem(
                    text = {
                        Text(
                            queuedPromptPreview(prompt),
                            maxLines = 2,
                            overflow = TextOverflow.Ellipsis,
                        )
                    },
                    trailingIcon = {
                        Icon(
                            Icons.Rounded.Close,
                            contentDescription = stringResource(R.string.remove_queued_prompt),
                        )
                    },
                    onClick = {
                        // 删除后保持弹层打开，方便用户连续处理多条；删除最后一条时必须同步
                        // 清除展开状态，否则后续同一会话再次产生 Queue 时会意外自动弹出。
                        if (prompts.size == 1) onExpandedChange(false)
                        onRemove(prompt.id)
                    },
                )
            }
        }
    }
}

@Composable
private fun SessionConfigMenuItem(
    label: String,
    icon: androidx.compose.ui.graphics.vector.ImageVector,
    onClick: () -> Unit,
) {
    DropdownMenuItem(
        text = { Text(label) },
        leadingIcon = { Icon(icon, contentDescription = null) },
        onClick = onClick,
    )
}

@Composable
internal fun StatusLabel(text: String, color: Color, containerColor: Color) {
    Surface(shape = MaterialTheme.shapes.small, color = containerColor, contentColor = color) {
        Text(
            text = text,
            modifier = Modifier.padding(horizontal = 8.dp, vertical = 2.dp),
            style = MaterialTheme.typography.labelSmall,
            maxLines = 1,
        )
    }
}
