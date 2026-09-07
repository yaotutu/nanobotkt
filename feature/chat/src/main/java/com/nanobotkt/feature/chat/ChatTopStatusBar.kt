package com.nanobotkt.feature.chat

import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Settings
import androidx.compose.material.icons.rounded.Menu
import androidx.compose.material3.CenterAlignedTopAppBar
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.role
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.nanobotkt.core.designsystem.NanobotThemeDefaults
import com.nanobotkt.core.transport.TransportStatus

/**
 * 聊天页顶部保留一个显式导航入口和当前会话信息。
 * Workspace、模型和自动化都属于低频会话信息，统一收纳到标题点击后的详情面板，避免顶部为
 * 一个纯展示字段额外占行，也避免三个点菜单同时承载两套不同层级的操作。连接正常时顶部完全
 * 不渲染状态；只有连接中或断开时，才在标题右上角叠加不参与布局的轻量 Badge。
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun ChatTopStatusBar(
    title: String,
    transportStatus: TransportStatus,
    onOpenDrawer: () -> Unit,
    onOpenSettings: () -> Unit,
    onOpenSessionInfo: () -> Unit,
) {
    CenterAlignedTopAppBar(
        title = {
            Box {
                Text(
                    text = title.ifBlank { stringResource(R.string.conversation_list_title) },
                    modifier = Modifier
                        .clickable(onClick = onOpenSessionInfo)
                        .semantics { role = Role.Button },
                    style = MaterialTheme.typography.titleLarge,
                    color = MaterialTheme.colorScheme.onSurface,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
                ConnectionStatusBadge(
                    status = transportStatus,
                    modifier = Modifier
                        // offset 只改变绘制位置，不参与标题测量，因此 Badge 不会挤压标题或
                        // 为 TopAppBar 新增操作宽度；轻微向右上偏移，保持“附着于标题”的视觉关系。
                        .align(androidx.compose.ui.Alignment.TopEnd)
                        .offset(x = 5.dp, y = 1.dp),
                )
            }
        },
        navigationIcon = {
            // 手势入口不易被新用户发现，也缺少无障碍语义；保留左上角 Menu 作为稳定兜底。
            // navigationIcon 会由 TopAppBar 统一处理 inset 与触控目标，不额外手写宽度。
            IconButton(onClick = onOpenDrawer) {
                Icon(
                    imageVector = Icons.Rounded.Menu,
                    contentDescription = stringResource(R.string.open_navigation),
                    modifier = Modifier.size(22.dp),
                    tint = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        },
        actions = {
            // 这里承载的是应用级全局设置，而不是当前会话或模型的局部设置；因此固定放在
            // Chat 顶部右侧，并沿用 Settings 页面已有的统一入口。
            IconButton(onClick = onOpenSettings) {
                Icon(
                    imageVector = Icons.Outlined.Settings,
                    contentDescription = stringResource(R.string.system_settings),
                    modifier = Modifier.size(22.dp),
                    tint = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        },
    )
}

/**
 * 标题 Badge 只提示需要关注的连接状态：连接中为闪烁橙点，断开或错误为红点。
 * 正常连接和初始空闲都直接不渲染，避免成功状态持续抢占注意力。Badge 不提供独立点击行为，
 * 用户仍通过标题打开详情，因此这里不需要 48dp 触控区域，也不会占据 TopAppBar actions 宽度。
 */
@Composable
internal fun ConnectionStatusBadge(
    status: TransportStatus,
    modifier: Modifier = Modifier,
) {
    val connectionStatus = resolveConnectionStatus(status)
    if (
        connectionStatus == ChatConnectionStatus.IDLE ||
        connectionStatus == ChatConnectionStatus.CONNECTED
    ) {
        return
    }
    val color =
        when (connectionStatus) {
            ChatConnectionStatus.CONNECTING -> NanobotThemeDefaults.statusColors.warning
            ChatConnectionStatus.DISCONNECTED -> MaterialTheme.colorScheme.error
            ChatConnectionStatus.IDLE,
            ChatConnectionStatus.CONNECTED,
            -> Color.Transparent
        }
    val alpha =
        if (connectionStatus == ChatConnectionStatus.CONNECTING) {
            val transition = rememberInfiniteTransition()
            val animatedAlpha by transition.animateFloat(
                initialValue = 0.42f,
                targetValue = 1f,
                animationSpec = infiniteRepeatable(tween(700), RepeatMode.Reverse),
            )
            animatedAlpha
        } else {
            1f
        }
    val statusDescription =
        when (connectionStatus) {
            ChatConnectionStatus.CONNECTING -> stringResource(R.string.connection_status_connecting)
            ChatConnectionStatus.DISCONNECTED -> stringResource(R.string.connection_status_disconnected)
            ChatConnectionStatus.IDLE -> stringResource(R.string.connection_status_idle)
            ChatConnectionStatus.CONNECTED -> stringResource(R.string.connection_status_connected)
        }
    Surface(
        modifier = modifier
            .size(6.dp)
            .semantics { contentDescription = statusDescription },
        shape = androidx.compose.foundation.shape.CircleShape,
        color = color.copy(alpha = alpha),
    ) {}
}

/** 供详情面板复用的连接状态文字，顶部栏本身不显示这段文字。 */
@Composable
internal fun connectionStatusLabel(status: TransportStatus): String =
    when (resolveConnectionStatus(status)) {
        ChatConnectionStatus.IDLE -> stringResource(R.string.connection_status_idle)
        ChatConnectionStatus.CONNECTING -> stringResource(R.string.connection_status_connecting)
        ChatConnectionStatus.CONNECTED -> stringResource(R.string.connection_status_connected)
        ChatConnectionStatus.DISCONNECTED -> stringResource(R.string.connection_status_disconnected)
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
