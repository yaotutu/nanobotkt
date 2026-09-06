package com.nanobotkt.feature.chat

import com.nanobotkt.core.model.ToolProgressEvent
import com.nanobotkt.core.model.UiMessage
import com.nanobotkt.core.transport.TransportStatus
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ChatHomeStateTest {
    @Test
    fun `连接中状态覆盖底层重连枚举`() {
        assertEquals(
            ChatConnectionStatus.CONNECTING,
            resolveConnectionStatus(TransportStatus.RECONNECTING),
        )
    }

    @Test
    fun `连接关闭时显示断开而不是笼统失败`() {
        assertEquals(
            ChatConnectionStatus.DISCONNECTED,
            resolveConnectionStatus(TransportStatus.ERROR),
        )
    }

    @Test
    fun `连接正常时不受 Agent 活动影响`() {
        assertEquals(
            ChatConnectionStatus.CONNECTED,
            resolveConnectionStatus(TransportStatus.OPEN),
        )
    }

    @Test
    fun `空闲连接返回中性状态`() {
        assertEquals(
            ChatConnectionStatus.IDLE,
            resolveConnectionStatus(TransportStatus.IDLE),
        )
    }

    @Test
    fun `只从当前活动轮次识别等待确认`() {
        val historical =
            UiMessage(
                id = "old",
                role = "assistant",
                content = "",
                createdAt = 1,
                turnId = "old-turn",
                toolEvents = listOf(ToolProgressEvent(phase = "awaiting_confirmation")),
            )
        val active =
            UiMessage(
                id = "active",
                role = "assistant",
                content = "",
                createdAt = 2,
                turnId = "active-turn",
                toolEvents = listOf(ToolProgressEvent(phase = "awaiting_user")),
            )

        assertTrue(hasWaitingForUserActivity(listOf(historical, active), "active-turn"))
        assertFalse(hasWaitingForUserActivity(listOf(historical), "active-turn"))
        assertFalse(hasWaitingForUserActivity(listOf(active), null))
    }

    @Test
    fun `附件菜单只暴露图片和文件`() {
        assertEquals(
            listOf(AttachmentMenuAction.IMAGES, AttachmentMenuAction.FILES),
            CHAT_ATTACHMENT_ACTIONS,
        )
    }
}
