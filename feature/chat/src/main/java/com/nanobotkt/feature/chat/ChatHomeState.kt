package com.nanobotkt.feature.chat

import com.nanobotkt.core.model.UiMessage
import com.nanobotkt.core.transport.TransportStatus

/** 顶部连接点只表达传输层状态，不再混入 Agent 运行或等待用户确认等会话活动。 */
internal enum class ChatConnectionStatus {
    IDLE,
    CONNECTING,
    CONNECTED,
    DISCONNECTED,
}

/**
 * 将底层 WebSocket 状态收敛成顶部栏需要的四种视觉语义。
 *
 * `CONNECTING` 和 `RECONNECTING` 共用动画橙点，`CLOSED` 和 `ERROR` 共用错误红点；这样顶部不会
 * 因底层状态枚举继续扩展而出现一串难以理解的标签，也保证 Agent 的 RUNNING/WAITING 不会误导
 * 用户把“模型正在工作”理解为“网络正在连接”。
 */
internal fun resolveConnectionStatus(transportStatus: TransportStatus): ChatConnectionStatus =
    when (transportStatus) {
        TransportStatus.IDLE -> ChatConnectionStatus.IDLE
        TransportStatus.CONNECTING,
        TransportStatus.RECONNECTING,
        -> ChatConnectionStatus.CONNECTING
        TransportStatus.OPEN -> ChatConnectionStatus.CONNECTED
        TransportStatus.CLOSED,
        TransportStatus.ERROR,
        -> ChatConnectionStatus.DISCONNECTED
    }

/**
 * 从当前会话消息中识别“等待用户确认”的真实 Activity。
 *
 * 服务端不同版本可能把等待态写在 tool phase、file phase 或 file pending 上，因此这里统一归一化。
 * 只检查仍处于流式/活动回合的记录，防止历史中的旧确认步骤让顶部状态永久停留。
 */
internal fun hasWaitingForUserActivity(
    messages: List<UiMessage>,
    activeTurnId: String?,
): Boolean {
    if (activeTurnId == null) return false
    return messages.asSequence()
        .filter { message -> message.turnId == null || message.turnId == activeTurnId }
        .any { message ->
            message.toolEvents.orEmpty().any { event -> event.phase.isWaitingForUserPhase() } ||
                message.fileEdits.orEmpty().any { edit ->
                    edit.pending == true || edit.phase.isWaitingForUserPhase()
                }
        }
}

/** 等待态字符串在顶部状态与 Activity 中必须保持同一判断口径。 */
private fun String?.isWaitingForUserPhase(): Boolean =
    this?.lowercase() in setOf("waiting", "awaiting_user", "awaiting_confirmation", "needs_confirmation")

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
