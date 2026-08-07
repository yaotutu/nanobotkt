package com.nanobotkt.feature.chat

import com.nanobotkt.core.model.UiMessage
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class PromptNavigatorTest {

    // -----------------------------------------------------------------------
    // extractPromptAnchors
    // -----------------------------------------------------------------------

    @Test
    fun `empty message list returns empty anchors`() {
        val anchors = extractPromptAnchors(emptyList())
        assertTrue(anchors.isEmpty())
    }

    @Test
    fun `only assistant messages returns empty anchors`() {
        val messages = listOf(
            assistantMessage(id = "a1", content = "Hello"),
            assistantMessage(id = "a2", content = "World"),
        )
        val anchors = extractPromptAnchors(messages)
        assertTrue(anchors.isEmpty())
    }

    @Test
    fun `single user prompt`() {
        val messages = listOf(
            userMessage(id = "u1", content = "What is Kotlin?"),
        )
        val anchors = extractPromptAnchors(messages)
        assertEquals(1, anchors.size)
        assertEquals("u1", anchors[0].stableId)
        assertEquals("What is Kotlin?", anchors[0].label)
        assertEquals("What is Kotlin?", anchors[0].preview)
        assertEquals(0, anchors[0].ordinal)
    }

    @Test
    fun `multiple user prompts interleaved with assistant`() {
        val messages = listOf(
            userMessage(id = "u1", content = "Q1"),
            assistantMessage(id = "a1", content = "A1"),
            userMessage(id = "u2", content = "Q2"),
            assistantMessage(id = "a2", content = "A2"),
            userMessage(id = "u3", content = "Q3"),
        )
        val anchors = extractPromptAnchors(messages)
        assertEquals(3, anchors.size)
        assertEquals("u1", anchors[0].stableId)
        assertEquals("u2", anchors[1].stableId)
        assertEquals("u3", anchors[2].stableId)
        assertEquals(listOf(0, 1, 2), anchors.map { it.ordinal })
    }

    @Test
    fun `empty user message content falls back to Prompt N label`() {
        val messages = listOf(
            userMessage(id = "u1", content = "   "),
            userMessage(id = "u2", content = ""),
        )
        val anchors = extractPromptAnchors(messages)
        assertEquals(2, anchors.size)
        assertEquals("Prompt 1", anchors[0].label)
        assertEquals("Prompt 1", anchors[0].preview)
        assertEquals("Prompt 2", anchors[1].label)
    }

    @Test
    fun `long content is truncated for label and preview`() {
        val longText = "a".repeat(500)
        val messages = listOf(userMessage(id = "u1", content = longText))
        val anchors = extractPromptAnchors(messages)
        assertEquals(1, anchors.size)
        // label max 80 chars -> 77 + "..."
        assertTrue(anchors[0].label.length <= 80)
        assertTrue(anchors[0].label.endsWith("..."))
        // preview max 320 chars -> 317 + "..."
        assertTrue(anchors[0].preview.length <= 320)
        assertTrue(anchors[0].preview.endsWith("..."))
    }

    @Test
    fun `answerPreview captures next assistant content`() {
        val messages = listOf(
            userMessage(id = "u1", content = "Q"),
            assistantMessage(id = "a1", content = "This is the answer"),
        )
        val anchors = extractPromptAnchors(messages)
        assertEquals(1, anchors.size)
        assertEquals("This is the answer", anchors[0].answerPreview)
    }

    @Test
    fun `answerPreview empty when next message is user`() {
        val messages = listOf(
            userMessage(id = "u1", content = "Q1"),
            userMessage(id = "u2", content = "Q2"),
        )
        val anchors = extractPromptAnchors(messages)
        assertEquals(2, anchors.size)
        assertEquals("", anchors[0].answerPreview)
    }

    @Test
    fun `answerPreview empty when no following assistant`() {
        val messages = listOf(
            userMessage(id = "u1", content = "Q"),
        )
        val anchors = extractPromptAnchors(messages)
        assertEquals(1, anchors.size)
        assertEquals("", anchors[0].answerPreview)
    }

    @Test
    fun `compacts multiple newlines in preview`() {
        val messages = listOf(
            userMessage(id = "u1", content = "Line1\n\n\n\nLine2\n\n\n\n\nLine3"),
        )
        val anchors = extractPromptAnchors(messages)
        assertEquals(1, anchors.size)
        // 3+ newlines should collapse to \n\n
        assertEquals("Line1\n\nLine2\n\nLine3", anchors[0].preview)
    }

    @Test
    fun `preserves message createdAt`() {
        val messages = listOf(
            userMessage(id = "u1", content = "Q", createdAt = 1720000000000L),
        )
        val anchors = extractPromptAnchors(messages)
        assertEquals(1720000000000L, anchors[0].createdAt)
    }

    // -----------------------------------------------------------------------
    // filterPrompts
    // -----------------------------------------------------------------------

    @Test
    fun `filter with empty query returns all items`() {
        val items = listOf(
            promptItem("a", "hello"),
            promptItem("b", "world"),
        )
        assertEquals(2, filterPrompts(items, "").size)
        assertEquals(2, filterPrompts(items, "  ").size)
    }

    @Test
    fun `filter by label match`() {
        val items = listOf(
            promptItem("a", "hello"),
            promptItem("b", "world"),
            promptItem("c", "HELLO again"),
        )
        val result = filterPrompts(items, "hello")
        assertEquals(2, result.size)
        assertEquals(listOf("a", "c"), result.map { it.stableId })
    }

    @Test
    fun `filter by preview match`() {
        val items = listOf(
            promptItem("a", label = "X", preview = "find me"),
            promptItem("b", label = "Y", preview = "other"),
        )
        val result = filterPrompts(items, "find")
        assertEquals(1, result.size)
        assertEquals("a", result[0].stableId)
    }

    @Test
    fun `filter case insensitive`() {
        val items = listOf(promptItem("a", "HeLLo"))
        assertEquals(1, filterPrompts(items, "hello").size)
        assertEquals(1, filterPrompts(items, "HELLO").size)
        assertEquals(1, filterPrompts(items, "Hello").size)
    }

    @Test
    fun `filter no match returns empty`() {
        val items = listOf(promptItem("a", "hello"))
        assertTrue(filterPrompts(items, "zzz").isEmpty())
    }

    // -----------------------------------------------------------------------
    // helpers
    // -----------------------------------------------------------------------

    private fun userMessage(
        id: String,
        content: String,
        createdAt: Long = 1000000L,
    ) = UiMessage(
        id = id,
        role = "user",
        content = content,
        createdAt = createdAt,
    )

    private fun assistantMessage(
        id: String,
        content: String,
        createdAt: Long = 1000000L,
    ) = UiMessage(
        id = id,
        role = "assistant",
        content = content,
        createdAt = createdAt,
    )

    private fun promptItem(
        stableId: String,
        label: String,
        preview: String = label,
    ) = PromptNavigatorItem(
        stableId = stableId,
        messageId = stableId,
        label = label,
        preview = preview,
        answerPreview = "",
        createdAt = 0,
        ordinal = 0,
    )
}
