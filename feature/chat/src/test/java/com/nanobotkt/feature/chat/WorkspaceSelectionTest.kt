package com.nanobotkt.feature.chat

import com.nanobotkt.core.model.WorkspaceAccessMode
import com.nanobotkt.core.model.WorkspaceScope
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Test

class WorkspaceSelectionTest {
    @Test
    fun `unique directory names display only the final path segment`() {
        val options = buildWorkspaceOptions(
            listOf(
                scope("/Users/test/client-a/nanobot"),
                scope("/Users/test/client-b/mobile"),
            ),
        )

        assertEquals(
            listOf("mobile", "nanobot"),
            options.map(WorkspaceOption::displayName),
        )
    }

    @Test
    fun `duplicate directory names display the complete normalized paths`() {
        val options = buildWorkspaceOptions(
            listOf(
                scope("/Users/test/client-a/nanobot/"),
                scope("/Users/test/client-b/nanobot"),
            ),
        )

        assertEquals(
            listOf(
                "/Users/test/client-a/nanobot",
                "/Users/test/client-b/nanobot",
            ),
            options.map(WorkspaceOption::displayName),
        )
    }

    @Test
    fun `same normalized path is one option even when sessions repeat it`() {
        val options = buildWorkspaceOptions(
            listOf(
                scope("C:\\work\\nanobot"),
                scope("C:/work/nanobot/"),
            ),
        )

        assertEquals(1, options.size)
        assertEquals("C:/work/nanobot", options.single().identity)
        // 选择项携带的 WorkspaceScope 也必须使用同一个规范化完整路径，不能只规范化展示文案。
        assertEquals("C:/work/nanobot", options.single().scope.projectPath)
        assertNotNull(options.single().scope)
    }

    private fun scope(path: String) = WorkspaceScope(
        projectPath = path,
        accessMode = WorkspaceAccessMode.RESTRICTED,
    )
}
