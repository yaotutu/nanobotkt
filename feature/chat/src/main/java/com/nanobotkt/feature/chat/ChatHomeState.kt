package com.nanobotkt.feature.chat

import com.nanobotkt.core.transport.TransportStatus

/**
 * 聊天首页顶部只展示能够从现有真实状态可靠推导出的粗粒度执行状态。
 *
 * 当前协议虽然包含 goal/tool 事件，但 Repository 尚未把“思考”“工具执行”等阶段归一化为
 * 会话级状态。这里因此不会根据历史消息猜测工具是否仍在运行，避免旧事件让顶部状态滞留。
 */
internal enum class ChatHeaderStatus {
    IDLE,
    RUNNING,
    RECONNECTING,
    FAILED,
}

/**
 * 以纯函数集中定义顶部状态优先级，防止多个 Composable 各自解释连接、错误和活动回合。
 *
 * 连接恢复与连接失败优先于聊天回合，因为此时发送链路本身不可用；其次显示当前错误，
 * 再显示活动回合。只有所有实时状态都正常时才回到 Idle。
 */
internal fun resolveChatHeaderStatus(
    transportStatus: TransportStatus,
    hasError: Boolean,
    active: Boolean,
): ChatHeaderStatus =
    when (transportStatus) {
        TransportStatus.CONNECTING,
        TransportStatus.RECONNECTING,
        -> ChatHeaderStatus.RECONNECTING
        TransportStatus.CLOSED,
        TransportStatus.ERROR,
        -> ChatHeaderStatus.FAILED
        TransportStatus.IDLE,
        TransportStatus.OPEN,
        ->
            when {
                hasError -> ChatHeaderStatus.FAILED
                active -> ChatHeaderStatus.RUNNING
                else -> ChatHeaderStatus.IDLE
            }
    }

/**
 * 附件入口的允许集合是产品边界的一部分。使用不可变常量并由测试锁定，避免模型、权限或
 * Workspace 在后续改动中重新混入输入框“+”菜单。
 */
internal enum class AttachmentMenuAction {
    IMAGES,
    FILES,
}

internal val CHAT_ATTACHMENT_ACTIONS: List<AttachmentMenuAction> =
    listOf(AttachmentMenuAction.IMAGES, AttachmentMenuAction.FILES)
