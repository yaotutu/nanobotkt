package com.nanobotkt.feature.chat

import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Settings
import androidx.compose.material3.CenterAlignedTopAppBar
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
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
 * 聊天页顶部只保留高频识别信息和一个应用级入口：标题、连接点、系统设置。
 * Workspace、模型和自动化都属于低频会话信息，统一收纳到标题点击后的详情面板，避免顶部为
 * 一个纯展示字段额外占行，也避免三个点菜单同时承载两套不同层级的操作。
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun ChatTopStatusBar(
    title: String,
    transportStatus: TransportStatus,
    onOpenSettings: () -> Unit,
    onOpenSessionInfo: () -> Unit,
) {
    CenterAlignedTopAppBar(
        title = {
            Column(
                modifier = Modifier
                    .clickable(onClick = onOpenSessionInfo)
                    .semantics { role = Role.Button },
                horizontalAlignment = Alignment.CenterHorizontally,
            ) {
                Text(
                    text = title.ifBlank { stringResource(R.string.conversation_list_title) },
                    style = MaterialTheme.typography.titleLarge,
                    color = MaterialTheme.colorScheme.onSurface,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            }
        },
        actions = {
            ConnectionStatusDot(
                status = transportStatus,
                onClick = onOpenSessionInfo,
            )
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
 * 顶部连接点是唯一的连接状态视觉提示：正常为绿点，连接中为闪烁橙点，异常为红点。
 * 点本身仍保留 48dp 左右的触控区域，并通过完整 contentDescription 暴露状态，不能只依赖颜色。
 */
@Composable
internal fun ConnectionStatusDot(
    status: TransportStatus,
    onClick: (() -> Unit)? = null,
) {
    val connectionStatus = resolveConnectionStatus(status)
    val color =
        when (connectionStatus) {
            ChatConnectionStatus.IDLE -> MaterialTheme.colorScheme.onSurfaceVariant
            ChatConnectionStatus.CONNECTING -> NanobotThemeDefaults.statusColors.warning
            ChatConnectionStatus.CONNECTED -> NanobotThemeDefaults.statusColors.success
            ChatConnectionStatus.DISCONNECTED -> MaterialTheme.colorScheme.error
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
            ChatConnectionStatus.IDLE -> stringResource(R.string.connection_status_idle)
            ChatConnectionStatus.CONNECTING -> stringResource(R.string.connection_status_connecting)
            ChatConnectionStatus.CONNECTED -> stringResource(R.string.connection_status_connected)
            ChatConnectionStatus.DISCONNECTED -> stringResource(R.string.connection_status_disconnected)
        }
    val modifier =
        Modifier
            .size(48.dp)
            .then(
                if (onClick == null) {
                    Modifier
                } else {
                    Modifier
                        .clickable(onClick = onClick)
                        .semantics { role = Role.Button }
                },
            )
            .padding(19.dp)
            .semantics { contentDescription = statusDescription }
    Box(
        modifier = modifier,
        contentAlignment = Alignment.Center,
    ) {
        Surface(
            modifier = Modifier
                .size(10.dp),
            shape = androidx.compose.foundation.shape.CircleShape,
            color = color.copy(alpha = alpha),
        ) {}
    }
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
