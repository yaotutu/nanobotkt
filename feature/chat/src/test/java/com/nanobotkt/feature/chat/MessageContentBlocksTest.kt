package com.nanobotkt.feature.chat

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class MessageContentBlocksTest {
    @Test
    fun `whole error envelope becomes structured timeline error`() {
        assertEquals(
            StructuredTimelineError("Tool result missing due to internal error"),
            parseStructuredTimelineError(
                "  <error>Tool result missing due to internal error</error>\n",
            ),
        )
    }

    @Test
    fun `inline error example remains normal document content`() {
        // 只识别完整协议 envelope，避免用户讨论标签或 Markdown 代码时被误渲染成错误状态卡片。
        assertNull(parseStructuredTimelineError("Example: <error>network</error>"))
        assertNull(parseStructuredTimelineError("<error>network</error> followed by explanation"))
        assertNull(parseStructuredTimelineError("<error>   </error>"))
    }
}
