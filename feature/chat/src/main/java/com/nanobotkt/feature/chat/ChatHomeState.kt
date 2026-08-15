package com.nanobotkt.feature.chat

import com.nanobotkt.core.model.UiMessage
import com.nanobotkt.core.transport.TransportStatus

/**
 * 聊天页顶部状态的产品级枚举。
 *
 * [IDLE] 只表示“没有需要用户注意的临时状态”，界面不会把“空闲”三个字渲染出来；其余状态
 * 都对应可操作或需要关注的会话级事实。消息发送失败等单条消息问题不进入这里，避免顶部状态与
 * 具体消息的错误提示重复。
 */
internal enum class ChatHeaderStatus {
    IDLE,
    WAITING_FOR_USER,
    RUNNING,
    RECONNECTING,
    DISCONNECTED,
}

/**
 * 集中定义顶部状态优先级：等待用户确认高于连接问题，其次才是普通运行。
 *
 * `hasError` 不再参与顶部状态推导。Repository 的通用错误可能来自加载、模型配置或某次发送，
 * 这些错误应由对应内容区或 Snackbar 解释；把它们统一显示成“失败”会让用户无法判断哪里出了问题。
 */
internal fun resolveChatHeaderStatus(
    transportStatus: TransportStatus,
    waitingForUser: Boolean,
    active: Boolean,
): ChatHeaderStatus =
    when {
        // 等待确认意味着页面上已经存在一个需要用户处理的 Activity。即使连接随后波动，
        // 用户仍应先看到这个可操作状态，而不是被较低优先级的重连文案覆盖。
        waitingForUser -> ChatHeaderStatus.WAITING_FOR_USER
        transportStatus == TransportStatus.CONNECTING ||
            transportStatus == TransportStatus.RECONNECTING -> ChatHeaderStatus.RECONNECTING
        transportStatus == TransportStatus.CLOSED ||
            transportStatus == TransportStatus.ERROR -> ChatHeaderStatus.DISCONNECTED
        active -> ChatHeaderStatus.RUNNING
        else -> ChatHeaderStatus.IDLE
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
