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
            resetRepositories = (1..9).map { index ->
                {
                    // 用编号记录 9 个 Singleton Repository 的清理调用，保证测试不会
                    // 因为把某个具体 Repository 的实现细节复制进来而失去焦点。
                    events += "repository-$index"
                }
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
                "repository-1",
                "repository-2",
                "repository-3",
                "repository-4",
                "repository-5",
                "repository-6",
                "repository-7",
                "repository-8",
                "repository-9",
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
