package com.nanobotkt.feature.chat

import com.nanobotkt.core.model.InboundEvent
import com.nanobotkt.core.model.ToolProgressEvent
import com.nanobotkt.core.model.UiFileEdit
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class ChatStreamFoldTest {
    private var time = 1_000L
    private var id = 0
    private fun fold() = ChatStreamFold(now = { time++ }, newId = { "id-${++id}" })

    @Test
    fun `appends deltas and isolates turns`() {
        val fold = fold()
        fold.fold(InboundEvent.Delta("c1", "Hello", turnId = "t1", turnPhase = "answer"))
        fold.fold(InboundEvent.Delta("c1", " world", turnId = "t1"))
        fold.fold(InboundEvent.Delta("c1", "Second", turnId = "t2"))

        assertEquals(listOf("Hello world", "Second"), fold.snapshot().map { it.content })
        assertEquals(listOf("t1", "t2"), fold.snapshot().map { it.turnId })
    }

    @Test
    fun `canonical message replaces partial content`() {
        val fold = fold()
        fold.fold(InboundEvent.Delta("c1", "Part", turnId = "t1"))
        val originalId = fold.snapshot().single().id
        fold.fold(InboundEvent.Message("c1", "Canonical answer", latencyMs = 125, turnId = "t1"))

        val message = fold.snapshot().single()
        assertEquals(originalId, message.id)
        assertEquals("Canonical answer", message.content)
        assertEquals(125L, message.latencyMs)
        assertFalse(message.isStreaming == true)
    }

    @Test
    fun `resumable stream remains open while closed stream starts a new buffer`() {
        val resumable = fold()
        resumable.fold(InboundEvent.Delta("c1", "draft", turnId = "t1"))
        val firstId = resumable.snapshot().single().id
        resumable.fold(InboundEvent.StreamEnd("c1", text = "canonical", resuming = true, mergeNext = true, turnId = "t1"))
        resumable.fold(InboundEvent.Delta("c1", " continuation", turnId = "t1"))
        assertEquals(firstId, resumable.snapshot().single().id)
        assertEquals("canonical continuation", resumable.snapshot().single().content)

        val closed = fold()
        closed.fold(InboundEvent.Delta("c1", "first", turnId = "t1"))
        closed.fold(InboundEvent.StreamEnd("c1", turnId = "t1"))
        closed.fold(InboundEvent.Delta("c1", "late", turnId = "t1"))
        assertEquals(listOf("first", "late"), closed.snapshot().map { it.content })
    }

    @Test
    fun `tool lifecycle folds by call id`() {
        val fold = fold()
        fold.fold(
            InboundEvent.Message(
                chatId = "c1",
                text = "",
                kind = "progress",
                toolEvents = listOf(ToolProgressEvent(phase = "start", callId = "tool-1", name = "read_file")),
                turnId = "t1",
            ),
        )
        fold.fold(
            InboundEvent.Message(
                chatId = "c1",
                text = "",
                kind = "progress",
                toolEvents = listOf(ToolProgressEvent(phase = "end", callId = "tool-1", name = "read_file")),
                turnId = "t1",
            ),
        )

        val trace = fold.snapshot().single()
        assertEquals("trace", trace.kind)
        assertEquals("activity", trace.turnPhase)
        assertEquals(1, trace.toolEvents?.size)
        assertEquals("end", trace.toolEvents?.single()?.phase)
    }

    @Test
    fun `file edit completion replaces pending edit`() {
        val fold = fold()
        fold.fold(InboundEvent.FileEdit("c1", listOf(edit(path = "", pending = true, phase = "start")), turnId = "t1"))
        fold.fold(InboundEvent.FileEdit("c1", listOf(edit(path = "src/Main.kt", pending = null, phase = "end", status = "done", added = 3, deleted = 2)), turnId = "t1"))

        val result = fold.snapshot().single().fileEdits?.single()
        assertEquals("src/Main.kt", result?.path)
        assertEquals("done", result?.status)
        assertEquals(3, result?.added)
        assertNull(result?.pending)
    }

    @Test
    fun `media completion suppresses duplicate deltas until turn end`() {
        val fold = fold()
        val afterMedia = fold.fold(
            InboundEvent.Message(
                chatId = "c1",
                text = "image",
                media = listOf("https://example.invalid/image.png"),
                turnId = "t1",
            ),
        )
        fold.fold(InboundEvent.Delta("c1", "duplicate", turnId = "t1"))
        assertEquals(afterMedia, fold.snapshot())

        fold.fold(InboundEvent.TurnEnd("c1", turnId = "t1"))
        fold.fold(InboundEvent.Delta("c1", "next", turnId = "t2"))
        assertEquals("next", fold.snapshot().last().content)
    }

    @Test
    fun `turn end prunes reasoning only placeholder and rejects late events`() {
        val fold = fold()
        fold.fold(InboundEvent.ReasoningDelta("c1", "thinking", turnId = "t1"))
        fold.fold(InboundEvent.ReasoningEnd("c1", turnId = "t1"))
        fold.fold(InboundEvent.TurnEnd("c1", turnId = "t1"))
        fold.fold(InboundEvent.Delta("c1", "late", turnId = "t1"))

        assertTrue(fold.snapshot().isEmpty())
    }

    @Test
    fun `canonical reconciliation discards projection and suppresses completed late events`() {
        val fold = fold()
        fold.fold(InboundEvent.Delta("c1", "partial one", turnId = "t1"))
        fold.fold(InboundEvent.Delta("c1", "partial two", turnId = "t2"))

        fold.discardTurns(setOf("t1"))
        assertEquals(listOf("t2"), fold.snapshot().map { it.turnId })

        fold.markCompletedTurns(setOf("t2"))
        fold.fold(InboundEvent.Delta("c1", "late", turnId = "t2"))
        assertTrue(fold.snapshot().isEmpty())
    }
    private fun edit(
        path: String,
        pending: Boolean?,
        phase: String,
        status: String = "editing",
        added: Int = 1,
        deleted: Int = 0,
    ) = UiFileEdit(
        callId = "call-1",
        tool = "edit_file",
        path = path,
        phase = phase,
        added = added,
        deleted = deleted,
        status = status,
        pending = pending,
    )
}

