package com.nanobotkt.feature.chat

import com.nanobotkt.core.transport.TransportStatus
import org.junit.Assert.assertEquals
import org.junit.Test

class ChatHomeStateTest {
    @Test
    fun `重连状态优先于活动回合和聊天错误`() {
        assertEquals(
            ChatHeaderStatus.RECONNECTING,
            resolveChatHeaderStatus(
                transportStatus = TransportStatus.RECONNECTING,
                hasError = true,
                active = true,
            ),
        )
    }

    @Test
    fun `连接错误优先显示失败`() {
        assertEquals(
            ChatHeaderStatus.FAILED,
            resolveChatHeaderStatus(
                transportStatus = TransportStatus.ERROR,
                hasError = false,
                active = true,
            ),
        )
    }

    @Test
    fun `连接正常时聊天错误优先于运行状态`() {
        assertEquals(
            ChatHeaderStatus.FAILED,
            resolveChatHeaderStatus(
                transportStatus = TransportStatus.OPEN,
                hasError = true,
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
                hasError = false,
                active = true,
            ),
        )
    }

    @Test
    fun `无错误无活动回合时显示空闲`() {
        assertEquals(
            ChatHeaderStatus.IDLE,
            resolveChatHeaderStatus(
                transportStatus = TransportStatus.OPEN,
                hasError = false,
                active = false,
            ),
        )
    }

    @Test
    fun `附件菜单只暴露图片和文件`() {
        assertEquals(
            listOf(AttachmentMenuAction.IMAGES, AttachmentMenuAction.FILES),
            CHAT_ATTACHMENT_ACTIONS,
        )
    }
}
