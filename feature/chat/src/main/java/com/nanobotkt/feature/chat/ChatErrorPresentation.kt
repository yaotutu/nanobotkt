package com.nanobotkt.feature.chat

import androidx.annotation.StringRes

/**
 * 标记错误来自哪个 UI 状态域。
 *
 * 同一段底层异常文本在不同状态域中的用户含义并不完全相同，例如未知的 Model 错误应说明
 * “上一个模型仍然有效”，而未知的时间轴错误应说明当前回复被中断。这里不把来源泄漏到 UI，
 * 只用于稳定地选择兜底文案。
 */
internal enum class ChatErrorSource {
    COMPOSER,
    MODEL,
    TIMELINE,
}

/** Chat 页面当前需要展示的一条原始错误信号；[key] 只用于本地关闭状态去重。 */
internal data class ChatErrorSignal(
    val raw: String,
    val source: ChatErrorSource,
) {
    val key: String = "${source.name}:$raw"
}

/**
 * 只保存本地化资源 ID，不保存已经解析的字符串。
 *
 * 这样错误分类仍是无 Android Context 的纯函数，单元测试可以直接锁定映射规则；Composable
 * 仅在渲染阶段调用 stringResource，语言切换后也能自然重组为新的文案。
 */
internal data class ChatErrorPresentation(
    @param:StringRes val titleRes: Int,
    @param:StringRes val bodyRes: Int,
)

/**
 * 按“输入操作 > 模型操作 > 时间轴”选择当前最相关的错误。
 *
 * Composer 错误通常由用户刚刚执行的发送、附件或新会话操作触发，应优先反馈。会话主体完全
 * 加载失败时，时间轴中央已经展示带刷新按钮的完整错误状态，因此必须抑制同一 state.error，
 * 避免页面同时出现两份重复反馈。
 */
internal fun selectChatInlineError(
    composerError: String?,
    modelError: String?,
    timelineError: String?,
    fullLoadFailed: Boolean,
): ChatErrorSignal? =
    composerError.toErrorSignal(ChatErrorSource.COMPOSER)
        ?: modelError.toErrorSignal(ChatErrorSource.MODEL)
        ?: timelineError
            .takeUnless { fullLoadFailed }
            .toErrorSignal(ChatErrorSource.TIMELINE)

/**
 * 把内部错误码、异常 message 或“code: reason”组合映射为稳定的产品文案。
 *
 * 匹配只用于识别已知类别，任何原始文本都不会进入返回结果。这样即使上游返回异常堆栈、路径
 * 或新的内部错误码，界面也只展示安全的通用说明，而不会把实现细节直接暴露给用户。
 */
internal fun resolveChatErrorPresentation(error: ChatErrorSignal): ChatErrorPresentation {
    val normalized = error.raw.trim().lowercase()

    return when {
        normalized.contains("workspace_scope_rejected") ->
            presentation(
                R.string.chat_error_workspace_scope_title,
                R.string.chat_error_workspace_scope_body,
            )

        normalized.containsAny("message_too_big", "message_text_too_large") ->
            presentation(
                R.string.chat_error_message_too_big_title,
                R.string.chat_error_message_too_big_body,
            )

        normalized.contains("too_many_attachments") ->
            presentation(
                R.string.chat_error_attachment_count_title,
                R.string.chat_error_attachment_count_body,
            )

        normalized.containsAny("total_too_large", "transport_too_large", "too_large") ->
            presentation(
                R.string.chat_error_attachment_size_title,
                R.string.chat_error_attachment_size_body,
            )

        normalized.contains("unsupported_type") ->
            presentation(
                R.string.chat_error_attachment_type_title,
                R.string.chat_error_attachment_type_body,
            )

        normalized.contains("empty_file") ->
            presentation(
                R.string.chat_error_attachment_empty_title,
                R.string.chat_error_attachment_empty_body,
            )

        error.source == ChatErrorSource.MODEL || normalized.contains("model_preset_change_failed") ->
            presentation(
                R.string.chat_error_model_title,
                R.string.chat_error_model_body,
            )

        normalized.containsAny(
            "network",
            "timeout",
            "connection",
            "not_connected",
            "connection_closed",
            "socket",
            "reauthentication_failed",
        ) || normalized == "io" ->
            presentation(
                R.string.chat_error_connection_title,
                R.string.chat_error_connection_body,
            )

        normalized.contains("new_chat") || normalized.contains("new chat") ->
            presentation(
                R.string.chat_error_new_conversation_title,
                R.string.chat_error_new_conversation_body,
            )

        normalized.contains("fork") ->
            presentation(
                R.string.chat_error_fork_title,
                R.string.chat_error_fork_body,
            )

        normalized.containsAny(
            "turn_rejected",
            "invalid_payload",
            "message_send_failed",
            "send_failed",
            "send_rejected",
        ) ->
            presentation(
                R.string.chat_error_turn_rejected_title,
                R.string.chat_error_turn_rejected_body,
            )

        error.source == ChatErrorSource.TIMELINE ->
            presentation(
                R.string.chat_error_timeline_title,
                R.string.chat_error_timeline_body,
            )

        else ->
            presentation(
                R.string.chat_error_generic_title,
                R.string.chat_error_generic_body,
            )
    }
}

private fun String?.toErrorSignal(source: ChatErrorSource): ChatErrorSignal? =
    this?.trim()?.takeIf(String::isNotEmpty)?.let { ChatErrorSignal(raw = it, source = source) }

private fun String.containsAny(vararg candidates: String): Boolean =
    candidates.any { candidate -> contains(candidate) }

private fun presentation(
    @StringRes titleRes: Int,
    @StringRes bodyRes: Int,
): ChatErrorPresentation = ChatErrorPresentation(titleRes = titleRes, bodyRes = bodyRes)
