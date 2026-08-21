package com.nanobotkt.feature.chat

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class ChatErrorPresentationTest {
    @Test
    fun `输入错误优先于模型和时间轴错误`() {
        assertEquals(
            ChatErrorSignal("too_many_attachments", ChatErrorSource.COMPOSER),
            selectChatInlineError(
                composerError = " too_many_attachments ",
                modelError = "model_preset_change_failed",
                timelineError = "turn_rejected",
                fullLoadFailed = false,
            ),
        )
    }

    @Test
    fun `完整加载失败不重复展示时间轴错误条`() {
        assertNull(
            selectChatInlineError(
                composerError = null,
                modelError = null,
                timelineError = "thread_load_failed",
                fullLoadFailed = true,
            )
        )
    }

    @Test
    fun `完整加载失败仍保留独立的模型操作错误`() {
        assertEquals(
            ChatErrorSource.MODEL,
            selectChatInlineError(
                    composerError = null,
                    modelError = "model_preset_change_failed",
                    timelineError = "thread_load_failed",
                    fullLoadFailed = true,
                )
                ?.source,
        )
    }

    @Test
    fun `已知错误码映射为稳定资源而不是原始文本`() {
        val messageTooBig =
            resolveChatErrorPresentation(
                ChatErrorSignal("message_too_big: max_frame_bytes", ChatErrorSource.TIMELINE)
            )
        val workspaceRejected =
            resolveChatErrorPresentation(
                ChatErrorSignal(
                    "workspace_scope_rejected: path_not_allowed",
                    ChatErrorSource.TIMELINE,
                )
            )
        val attachmentLimit =
            resolveChatErrorPresentation(
                ChatErrorSignal("too_many_attachments", ChatErrorSource.COMPOSER)
            )

        assertEquals(R.string.chat_error_message_too_big_title, messageTooBig.titleRes)
        assertEquals(R.string.chat_error_workspace_scope_title, workspaceRejected.titleRes)
        assertEquals(R.string.chat_error_attachment_count_title, attachmentLimit.titleRes)
    }

    @Test
    fun `连接类异常统一映射为可恢复连接提示`() {
        listOf(
                "message_accept_timeout",
                "chat_not_connected",
                "connection_closed",
                "socket_acceptance_failed",
                "network_unavailable",
                "io",
            )
            .forEach { raw ->
                val presentation =
                    resolveChatErrorPresentation(
                        ChatErrorSignal(raw = raw, source = ChatErrorSource.COMPOSER)
                    )
                assertEquals(R.string.chat_error_connection_title, presentation.titleRes)
                assertEquals(R.string.chat_error_connection_body, presentation.bodyRes)
            }
    }

    @Test
    fun `未知错误按状态来源选择安全兜底文案`() {
        val composer =
            resolveChatErrorPresentation(
                ChatErrorSignal("internal/path/should-not-be-visible", ChatErrorSource.COMPOSER)
            )
        val timeline =
            resolveChatErrorPresentation(
                ChatErrorSignal("unexpected_gateway_detail", ChatErrorSource.TIMELINE)
            )

        assertEquals(R.string.chat_error_generic_title, composer.titleRes)
        assertEquals(R.string.chat_error_generic_body, composer.bodyRes)
        assertEquals(R.string.chat_error_timeline_title, timeline.titleRes)
        assertEquals(R.string.chat_error_timeline_body, timeline.bodyRes)
    }
}
