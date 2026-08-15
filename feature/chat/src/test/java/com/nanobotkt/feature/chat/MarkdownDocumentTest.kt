package com.nanobotkt.feature.chat

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class MarkdownDocumentTest {
    @Test
    fun `parses document blocks without executing unsupported html`() {
        val blocks =
            parseMarkdownBlocks(
                """
                # Title

                - [x] Done
                - Todo

                | Name | Value |
                | --- | ---: |
                | A | 1 |

                > quote

                <script>alert(1)</script>
                """.trimIndent()
            )

        assertTrue(blocks.first() is MarkdownBlock.Heading)
        assertTrue(blocks.any { it is MarkdownBlock.Table })
        assertTrue(blocks.any { it is MarkdownBlock.Quote })
        assertTrue(blocks.any { it is MarkdownBlock.Paragraph && it.text.contains("<script>") })
    }

    @Test
    fun `unfinished streaming code fence remains visible and marked open`() {
        val block = parseMarkdownBlocks("```kotlin\nval answer = 42").single() as MarkdownBlock.Code

        assertEquals("kotlin", block.language)
        assertEquals("val answer = 42", block.content)
        assertFalse(block.closed)
    }

    @Test
    fun `standalone markdown image becomes image block`() {
        assertEquals(
            MarkdownBlock.Image("preview", "/media/a.png"),
            parseMarkdownBlocks("![preview](/media/a.png)").single(),
        )
    }
}
