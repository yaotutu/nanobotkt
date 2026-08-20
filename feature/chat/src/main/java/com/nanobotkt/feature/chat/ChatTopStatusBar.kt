package com.nanobotkt.feature.chat

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.Toc
import androidx.compose.material.icons.outlined.Settings
import androidx.compose.material.icons.rounded.Checklist
import androidx.compose.material.icons.rounded.Folder
import androidx.compose.material.icons.rounded.Info
import androidx.compose.material.icons.rounded.Menu
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
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.nanobotkt.core.designsystem.NanobotThemeDefaults

/**
 * 聊天页唯一的顶部常驻区域。
 *
 * 顶部承载“会话导航、标题、运行/连接状态、系统设置、当前会话菜单”。会话列表属于页面导航，
 * 固定放在标题左侧；系统设置与当前会话菜单仍保持右侧独立入口，避免把应用级和会话级操作混在
 * 同一个菜单中。空闲时不渲染状态文案；出现运行、等待、连接或队列状态时才增加第二行。
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
    onOpenConversationList: () -> Unit,
    onOpenSettings: () -> Unit,
    onStatusClick: () -> Unit,
    onQueueOpenChange: (Boolean) -> Unit,
    onConfigMenuOpenChange: (Boolean) -> Unit,
    onQueuedPromptClick: (QueuedPrompt) -> Unit,
    onOpenPromptNavigator: () -> Unit,
    onOpenSessionInfo: () -> Unit,
    onOpenModel: () -> Unit,
    onOpenAccess: () -> Unit,
) {
    val muted = MaterialTheme.colorScheme.onSurfaceVariant
    val hasSecondaryRow = status != ChatHeaderStatus.IDLE || queuedPrompts.isNotEmpty()

    Row(
        modifier =
            Modifier.fillMaxWidth()
                .background(MaterialTheme.colorScheme.surface)
                .statusBarsPadding()
                .heightIn(min = 56.dp)
                .padding(horizontal = 4.dp, vertical = 4.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        // 会话导航从 Composer 迁移到顶部，既符合 Android 的页面层级习惯，也把底部横向空间
        // 完整留给输入内容；按钮仍保留 48dp 触控目标，视觉图标保持轻量。
        IconButton(onClick = onOpenConversationList) {
            Icon(
                imageVector = Icons.Rounded.Menu,
                contentDescription = stringResource(R.string.open_conversation_list),
                modifier = Modifier.size(22.dp),
                tint = muted,
            )
        }

        Column(
            modifier = Modifier.weight(1f).padding(start = 4.dp),
            verticalArrangement = Arrangement.Center,
        ) {
            Text(
                text = title.ifBlank { stringResource(R.string.conversation_list_title) },
                style = MaterialTheme.typography.titleMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            if (hasSecondaryRow) {
                Row(
                    // 所有非空状态共用固定的最小行高，避免 Running、Queue 或连接状态在文字
                    // lineHeight 不同时造成顶部栏细微跳动；只压缩标题间距，不缩小两侧 48dp 触控区。
                    modifier = Modifier.padding(top = 1.dp).heightIn(min = 18.dp),
                    horizontalArrangement = Arrangement.spacedBy(10.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    // Queue 是用户主动提交、并且仍等待处理的独立状态，不能被 Running/Waiting
                    // 覆盖。两者并列后，用户在长回复期间仍能立即确认排队数量，并点击查看摘要；
                    // 每个状态仍保持独立点击区域，避免把“定位运行记录”和“打开队列”混成一个入口。
                    if (status != ChatHeaderStatus.IDLE) {
                        HeaderStatusLabel(status = status, onClick = onStatusClick)
                    }
                    if (queuedPrompts.isNotEmpty()) {
                        QueueStatusMenu(
                            prompts = queuedPrompts,
                            expanded = queueOpen,
                            onExpandedChange = onQueueOpenChange,
                            onPromptClick = onQueuedPromptClick,
                        )
                    }
                }
            }
        }

        // 系统设置是应用级入口，使用独立的描边齿轮并放在会话菜单左侧。两个按钮共享右侧操作区，
        // 但不合并到同一个菜单中，从视觉和语义上同时保留“全局设置 / 当前会话设置”的边界。
        IconButton(onClick = onOpenSettings, modifier = Modifier.size(48.dp)) {
            Icon(
                Icons.Outlined.Settings,
                contentDescription = stringResource(R.string.system_settings),
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
                        icon = Icons.Rounded.Info,
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
                // Automation 仍由“会话信息”页面展示，避免把同一会话元数据拆成两个边界模糊的入口。
                if (hasSessionInfo) {
                    SessionConfigMenuItem(
                        label = stringResource(R.string.session_info_automations),
                        icon = Icons.AutoMirrored.Rounded.Toc,
                        onClick = {
                            onConfigMenuOpenChange(false)
                            onOpenSessionInfo()
                        },
                    )
                }
            }
        }
    }
}

/** 顶部状态使用轻量文字而不是胶囊 Badge；点击后由页面定位到对应 Activity。 */
@Composable
private fun HeaderStatusLabel(
    status: ChatHeaderStatus,
    onClick: () -> Unit,
) {
    val text =
        when (status) {
            ChatHeaderStatus.IDLE -> ""
            ChatHeaderStatus.WAITING_FOR_USER -> stringResource(R.string.chat_status_waiting)
            ChatHeaderStatus.RUNNING -> stringResource(R.string.chat_status_running)
            ChatHeaderStatus.RECONNECTING -> stringResource(R.string.chat_status_reconnecting)
            ChatHeaderStatus.DISCONNECTED -> stringResource(R.string.chat_status_disconnected)
        }
    val color =
        when (status) {
            ChatHeaderStatus.IDLE -> MaterialTheme.colorScheme.onSurfaceVariant
            // 等待用户和重连都属于需要注意、但尚未失败的业务 Warning。这里使用产品扩展
            // 状态色，而不是借用 tertiary 品牌角色，避免主题调整后状态语义随装饰色漂移。
            ChatHeaderStatus.WAITING_FOR_USER,
            ChatHeaderStatus.RECONNECTING,
            -> NanobotThemeDefaults.statusColors.warning
            ChatHeaderStatus.RUNNING -> MaterialTheme.colorScheme.primary
            ChatHeaderStatus.DISCONNECTED -> MaterialTheme.colorScheme.error
        }
    Row(
        modifier = Modifier.clickable(enabled = status != ChatHeaderStatus.IDLE, onClick = onClick),
        horizontalArrangement = Arrangement.spacedBy(5.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(text = "●", color = color, style = MaterialTheme.typography.labelSmall)
        Text(
            text = text,
            color = color,
            style = MaterialTheme.typography.labelSmall,
            maxLines = 1,
        )
    }
}

/**
 * Queue 弹层是只读的轻量摘要。这里明确不提供删除、编辑或移除按钮，避免顶部状态承担会话管理职责。
 * 点击某一项后交由上层定位时间轴中的排队用户消息，并立即关闭弹层。
 */
@Composable
private fun QueueStatusMenu(
    prompts: List<QueuedPrompt>,
    expanded: Boolean,
    onExpandedChange: (Boolean) -> Unit,
    onPromptClick: (QueuedPrompt) -> Unit,
) {
    Box {
        Text(
            text = stringResource(R.string.queued_count, prompts.size),
            modifier = Modifier.clickable { onExpandedChange(true) },
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            style = MaterialTheme.typography.labelSmall,
            maxLines = 1,
        )
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
            prompts.forEachIndexed { index, prompt ->
                DropdownMenuItem(
                    text = {
                        Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                            Text(
                                text = "${index + 1}",
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                                style = MaterialTheme.typography.labelMedium,
                            )
                            Text(
                                text = queuedPromptPreview(prompt),
                                maxLines = 2,
                                overflow = TextOverflow.Ellipsis,
                            )
                        }
                    },
                    onClick = {
                        onExpandedChange(false)
                        onPromptClick(prompt)
                    },
                )
            }
        }
    }
}

@Composable
private fun SessionConfigMenuItem(
    label: String,
    icon: ImageVector,
    onClick: () -> Unit,
) {
    DropdownMenuItem(
        text = { Text(label) },
        leadingIcon = { Icon(icon, contentDescription = null) },
        onClick = onClick,
    )
}

/** 保留给输入区等现有组件使用的通用轻量标签。 */
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
