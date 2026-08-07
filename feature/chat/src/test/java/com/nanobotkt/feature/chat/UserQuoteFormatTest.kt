package com.nanobotkt.feature.chat

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class UserQuoteFormatTest {
    @Test
    fun `normalizes edge whitespace newlines and maximum length`() {
        assertEquals("hello\n\n  world", normalizeQuotedContext("  hello\r\n\r\n  world  "))
        assertEquals(MAX_QUOTED_CONTEXT_CHARS, normalizeQuotedContext("x".repeat(5_000)).length)
        assertEquals("", normalizeQuotedContext(null))
    }

    @Test
    fun `formats and parses quoted user message`() {
        val formatted = formatQuotedUserMessage("reply", "previous\n\nanswer")
        assertTrue(formatted.startsWith("> [!QUOTE]\n> previous\n>\n> answer"))
        assertEquals(
            ParsedUserMessageQuote("previous\n\nanswer", "reply"),
            parseQuotedUserMessage(formatted),
        )
    }

    @Test
    fun `does not prepend quotes to slash commands or malformed content`() {
        assertEquals("/stop", formatQuotedUserMessage(" /stop ", "previous"))
        val plain = parseQuotedUserMessage("just plain text")
        assertNull(plain.quotedContext)
        assertEquals("just plain text", plain.content)
    }
}