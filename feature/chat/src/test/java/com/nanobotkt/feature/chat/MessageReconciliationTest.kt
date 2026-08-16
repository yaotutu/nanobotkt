package com.nanobotkt.feature.chat

import com.nanobotkt.core.model.UiMessage
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test

class MessageReconciliationTest {
    @Test
    fun `matches stable id or semantic identity`() {
        assertTrue(sameSemanticMessage(message("same", "old"), message("same", "new")))
        assertTrue(
            sameSemanticMessage(
                message("", "Answer", turnId = "turn-1"),
                message("", "Answer", turnId = "turn-1"),
            ),
        )
        assertFalse(
            sameSemanticMessage(
                message("", "Answer", turnId = "turn-1"),
                message("", "Answer", turnId = "turn-2"),
            ),
        )
    }

    @Test
    fun `latest page replaces overlapping suffix without duplication`() {
        val first = message("first", "First", role = "user")
        val overlap = message("overlap", "Existing answer")
        val refreshed = message("overlap", "Refreshed answer")
        val latest = message("latest", "Latest answer")

        assertEquals(
            listOf(first, refreshed, latest),
            mergeLatestMessages(listOf(first, overlap), listOf(refreshed, latest)),
        )
    }

    @Test
    fun `latest without overlap replaces the entire local window`() {
        val current = listOf(
            message("old-user", "Research xxx", role = "user"),
            message("old-answer", "Old answer"),
        )
        val latest = listOf(
            message("replay-user-1", "Research xxx", role = "user"),
            message("replay-answer-1", "Canonical answer"),
        )

        // latest 请求代表服务端当前权威窗口。即使 replay ID 全部变化，也不能把完整窗口
        // 追加到旧时间线前面，否则每次锁屏恢复都会再复制一份相同对话。
        assertEquals(latest, mergeLatestMessages(current, latest))
    }

    @Test
    fun `repeated replay refreshes never grow the message count`() {
        val firstReplay = listOf(
            message("replay-1-user", "Research xxx", role = "user"),
            message("replay-1-answer", "Canonical answer"),
        )
        val secondReplay = listOf(
            message("replay-2-user", "Research xxx", role = "user"),
            message("replay-2-answer", "Canonical answer"),
        )
        val thirdReplay = listOf(
            message("replay-3-user", "Research xxx", role = "user"),
            message("replay-3-answer", "Canonical answer"),
        )

        val afterFirstResume = mergeLatestMessages(emptyList(), firstReplay)
        val afterSecondResume = mergeLatestMessages(afterFirstResume, secondReplay)
        val afterThirdResume = mergeLatestMessages(afterSecondResume, thirdReplay)

        assertEquals(secondReplay.size, afterSecondResume.size)
        assertEquals(thirdReplay, afterThirdResume)
    }

    @Test
    fun `older page stops at current semantic boundary`() {
        val boundary = message("boundary", "Boundary")
        val current = listOf(boundary, message("latest", "Latest"))
        val result = prependOlderMessages(
            current,
            listOf(message("oldest", "Oldest"), message("boundary", "Boundary")),
        )

        assertEquals(listOf("oldest", "boundary", "latest"), result.map(UiMessage::id))
        assertSame(current, prependOlderMessages(current, emptyList()))
    }

    private fun message(
        id: String,
        content: String,
        role: String = "assistant",
        kind: String? = null,
        turnId: String? = null,
    ) = UiMessage(
        id = id,
        role = role,
        content = content,
        kind = kind,
        createdAt = 1,
        turnId = turnId,
    )
}
