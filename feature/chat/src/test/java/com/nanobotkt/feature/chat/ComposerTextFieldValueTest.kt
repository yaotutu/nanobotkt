package com.nanobotkt.feature.chat

import androidx.compose.ui.text.TextRange
import androidx.compose.ui.text.input.TextFieldValue
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Test

/**
 * 锁定 Composer 与输入法之间的编辑状态契约。
 *
 * JVM 测试无法启动搜狗输入法，但可以精确验证导致真机丢字的关键条件：ViewModel 回显相同文本
 * 时不得新建 composition=null 的 TextFieldValue；只有业务层真正替换文本时才结束旧组合状态。
 */
class ComposerTextFieldValueTest {
    @Test
    fun `matching external echo preserves ime composition and selection`() {
        val localValue =
            TextFieldValue(
                text = "Windows",
                selection = TextRange(2, 7),
                composition = TextRange(0, 7),
            )

        val reconciled =
            reconcileComposerFieldValue(
                localValue = localValue,
                externalText = "Windows",
                // ViewModel 只保存 selection.end；只要末端一致，就不能折叠本地完整选择范围。
                externalCursorPosition = 7,
            )

        assertSame(localValue, reconciled)
        assertEquals(TextRange(2, 7), reconciled.selection)
        assertEquals(TextRange(0, 7), reconciled.composition)
    }

    @Test
    fun `external text replacement resets stale ime composition`() {
        val localValue =
            TextFieldValue(
                text = "wi",
                selection = TextRange(2),
                composition = TextRange(0, 2),
            )

        val reconciled =
            reconcileComposerFieldValue(
                localValue = localValue,
                externalText = "/help ",
                externalCursorPosition = 6,
            )

        assertEquals("/help ", reconciled.text)
        assertEquals(TextRange(6), reconciled.selection)
        assertNull(reconciled.composition)
    }

    @Test
    fun `external cursor replacement is clamped and resets stale composition`() {
        val localValue =
            TextFieldValue(
                text = "draft",
                selection = TextRange.Zero,
                composition = TextRange(0, 5),
            )

        val reconciled =
            reconcileComposerFieldValue(
                localValue = localValue,
                externalText = "draft",
                externalCursorPosition = 99,
            )

        assertEquals(TextRange(5), reconciled.selection)
        assertNull(reconciled.composition)
    }
}
