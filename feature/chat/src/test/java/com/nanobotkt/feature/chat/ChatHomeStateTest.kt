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
    fun `等待确认优先于重连和活动回合`() {
        assertEquals(
            ChatHeaderStatus.WAITING_FOR_USER,
            resolveChatHeaderStatus(
                transportStatus = TransportStatus.RECONNECTING,
                waitingForUser = true,
                active = true,
            ),
        )
    }

    @Test
    fun `连接关闭时显示断开而不是笼统失败`() {
        assertEquals(
            ChatHeaderStatus.DISCONNECTED,
            resolveChatHeaderStatus(
                transportStatus = TransportStatus.ERROR,
                waitingForUser = false,
                active = true,
            ),
        )
    }

    @Test
    fun `连接正常时等待确认优先于普通运行`() {
        assertEquals(
            ChatHeaderStatus.WAITING_FOR_USER,
            resolveChatHeaderStatus(
                transportStatus = TransportStatus.OPEN,
                waitingForUser = true,
                active = true,
            ),
        )
    }

    @Test
    fun `连接正常且存在活动回合时显示运行中`() {
        assertEquals(
            ChatHeaderStatus.RUNNING,
            resolveChatHeaderStatus(
                transportStatus = TransportStatus.OPEN,
                waitingForUser = false,
                active = true,
            ),
        )
    }

    @Test
    fun `无临时状态时返回空闲但界面不渲染标签`() {
        assertEquals(
            ChatHeaderStatus.IDLE,
            resolveChatHeaderStatus(
                transportStatus = TransportStatus.OPEN,
                waitingForUser = false,
                active = false,
            ),
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
