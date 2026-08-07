package com.nanobotkt.core.model

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class WorkspaceScopeUtilsTest {
    @Test
    fun `access mode keeps restriction flag consistent`() {
        val scope = scope("/srv/project", WorkspaceAccessMode.RESTRICTED)

        assertEquals(false, scope.withAccessMode(WorkspaceAccessMode.FULL).restrictToWorkspace)
        assertEquals(true, scope.withAccessMode(WorkspaceAccessMode.RESTRICTED).restrictToWorkspace)
    }

    @Test
    fun `normalization derives project name and repairs restriction flag`() {
        assertEquals(
            WorkspaceScope(
                projectPath = "C:\\dev\\nanobot",
                projectName = "nanobot",
                accessMode = WorkspaceAccessMode.RESTRICTED,
                restrictToWorkspace = true,
            ),
            WorkspaceScope(
                projectPath = "C:\\dev\\nanobot",
                accessMode = WorkspaceAccessMode.RESTRICTED,
                restrictToWorkspace = false,
            ).normalized(),
        )
    }

    @Test
    fun `absolute path validation matches RN rules`() {
        listOf("~", "~/project", "~\\project", "/srv/project", "C:\\dev\\project", "d:/code")
            .forEach { assertTrue(it, isAbsoluteWorkspacePath(it)) }
        listOf("", "project", ".\\project", "../project", "C:project")
            .forEach { assertFalse(it, isAbsoluteWorkspacePath(it)) }
    }

    @Test
    fun `project selection treats normalized default path as no override`() {
        val default = scope("C:\\dev\\nanobot", WorkspaceAccessMode.RESTRICTED)

        assertNull(selectedProjectScope(scope("C:/dev/nanobot/", WorkspaceAccessMode.FULL), default))
        assertEquals("other", selectedProjectScope(scope("/srv/other", WorkspaceAccessMode.FULL), default)?.projectName)
    }

    @Test
    fun `short path retains at most final three segments`() {
        assertEquals(".../b/c/d", shortWorkspacePath("/a/b/c/d"))
        assertEquals("C:\\a\\b", shortWorkspacePath("C:\\a\\b"))
    }

    private fun scope(path: String, mode: WorkspaceAccessMode) = WorkspaceScope(
        projectPath = path,
        projectName = projectNameFromPath(path),
        accessMode = mode,
        restrictToWorkspace = mode == WorkspaceAccessMode.RESTRICTED,
    )
}
