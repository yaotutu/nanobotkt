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
            clearComposerDrafts = { events += "composer-drafts" },
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

        // 当前会话先清除持久化消息载荷，再允许认证仓库清理 secret/bootstrap。
        assertTrue(authLogoutStarted)
        assertEquals(
            listOf(
                "root",
                "session-state",
                "transport-clear-attachments",
                "transport-close",
                "composer-drafts",
                "auth-logout",
            ),
            events,
        )
    }

    @Test
    fun `composer draft cleanup failure cannot prevent auth logout`() = runTest {
        val events = mutableListOf<String>()

        scheduleLogoutCleanup(
            scope = this,
            resetRootUiState = { events += "root" },
            resetSessionState = { events += "session-state" },
            clearAttachments = { events += "transport-clear-attachments" },
            closeTransport = { events += "transport-close" },
            clearComposerDrafts = {
                events += "composer-drafts-failed"
                error("database unavailable")
            },
            logout = { events += "auth-logout" },
        )

        // 数据库异常由编排层隔离，认证注销仍必须执行且测试作用域不能被异常击穿。
        runCurrent()

        assertEquals("auth-logout", events.last())
    }
}
