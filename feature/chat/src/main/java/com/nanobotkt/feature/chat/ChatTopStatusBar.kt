package com.nanobotkt.feature.chat

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.Toc
import androidx.compose.material.icons.outlined.Settings
import androidx.compose.material.icons.rounded.Checklist
import androidx.compose.material.icons.rounded.Folder
import androidx.compose.material.icons.rounded.Info
import androidx.compose.material.icons.rounded.MoreVert
import androidx.compose.material.icons.rounded.SmartToy
import androidx.compose.material3.CenterAlignedTopAppBar
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.HorizontalDivider
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
 * 这里直接使用 Material 3 的 CenterAlignedTopAppBar：当前会话标题和辅助状态位于视觉中轴，
 * 顶部只保留右侧低频会话菜单，不再为了视觉对称把系统设置放到难以触达的左上角。
 * 高频会话切换继续留在 Composer 旁的拇指热区；系统设置则进入右侧溢出菜单，避免把
 * “视觉平衡”置于移动端单手可达性之上。
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun ChatTopStatusBar(
    title: String,
    /** 当前会话所属工作区的短名称；不传绝对路径，避免顶部栏被长文本撑乱。 */
    workspaceName: String? = null,
    status: ChatHeaderStatus,
    configMenuOpen: Boolean,
    hasPromptNavigator: Boolean,
    hasSessionInfo: Boolean,
    hasAccessSettings: Boolean,
    onOpenSettings: () -> Unit,
    onStatusClick: () -> Unit,
    onConfigMenuOpenChange: (Boolean) -> Unit,
    onOpenPromptNavigator: () -> Unit,
    onOpenSessionInfo: () -> Unit,
    onOpenModel: () -> Unit,
    onOpenAccess: () -> Unit,
) {
    val muted = MaterialTheme.colorScheme.onSurfaceVariant
    val hasSecondaryRow = workspaceName != null || status != ChatHeaderStatus.IDLE

    CenterAlignedTopAppBar(
        title = {
            Column(
                horizontalAlignment = Alignment.CenterHorizontally,
            ) {
                Text(
                    text = title.ifBlank { stringResource(R.string.conversation_list_title) },
                    style = MaterialTheme.typography.titleLarge,
                    color = MaterialTheme.colorScheme.onSurface,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
                if (hasSecondaryRow) {
                    Row(
                        // 辅助信息仍保留在标题下方，但随标题整体居中；固定最小行高避免运行状态
                        // 出现或消失时造成顶部栏细微跳动，同时不改变两侧标准触控区。
                        modifier = Modifier.heightIn(min = 18.dp),
                        horizontalArrangement = Arrangement.spacedBy(10.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        workspaceName?.let { name ->
                            WorkspaceNameLabel(workspaceName = name)
                        }
                        if (status != ChatHeaderStatus.IDLE) {
                            HeaderStatusLabel(status = status, onClick = onStatusClick)
                        }
                    }
                }
            }
        },
        actions = {
            Box {
                // 当前会话操作放在右槽；展开菜单后仍沿用原有回调和状态，不改变业务行为。
                IconButton(onClick = { onConfigMenuOpenChange(true) }) {
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
                    // 系统设置是应用级低频入口，与上方当前会话操作用分隔线区分；
                    // 用户无需伸手到左上角，仍可从唯一的右侧菜单稳定进入设置。
                    HorizontalDivider()
                    SessionConfigMenuItem(
                        label = stringResource(R.string.system_settings),
                        icon = Icons.Outlined.Settings,
                        onClick = {
                            onConfigMenuOpenChange(false)
                            onOpenSettings()
                        },
                    )
                }
            }
        },
    )
}

/**
 * 顶部栏只显示工作区名称，不显示完整路径；名称过长时由标题区域的单行省略保护右侧操作。
 */
@Composable
private fun WorkspaceNameLabel(workspaceName: String) {
    Row(
        modifier = Modifier,
        horizontalArrangement = Arrangement.spacedBy(4.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Icon(
            imageVector = Icons.Rounded.Folder,
            contentDescription = null,
            modifier = Modifier.size(14.dp),
            tint = MaterialTheme.colorScheme.primary,
        )
        Text(
            text = workspaceName,
            color = MaterialTheme.colorScheme.primary,
            style = MaterialTheme.typography.labelSmall,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
        )
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
