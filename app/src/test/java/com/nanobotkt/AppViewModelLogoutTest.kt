package com.nanobotkt

import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.runCurrent
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class AppViewModelLogoutTest {
    @Test
    fun `logout cleanup invalidates every session holder before async auth logout`() = runTest {
        val events = mutableListOf<String>()
        var authLogoutStarted = false

        scheduleLogoutCleanup(
            scope = this,
            resetRootUiState = { events += "root" },
            resetSessionState = {
                // 具体 Repository 的 reset 行为由各 feature 测试覆盖；这里仅验证组合根
                // 必须在关闭传输和异步认证注销前完成整组会话状态失效。
                events += "session-state"
            },
            clearAttachments = { events += "transport-clear-attachments" },
            closeTransport = { events += "transport-close" },
            logout = {
                authLogoutStarted = true
                events += "auth-logout"
            },
        )

        // logout 的认证协程必须排在同步清理之后；调用函数返回时它尚未运行。
        assertFalse(authLogoutStarted)
        assertEquals(
            listOf(
                "root",
                "session-state",
                "transport-clear-attachments",
                "transport-close",
            ),
            events,
        )

        runCurrent()

        // 当前会话已全部失效后，才允许认证仓库开始清理 secret/bootstrap。
        assertTrue(authLogoutStarted)
        assertEquals("auth-logout", events.last())
    }
}
