package com.nanobotkt.feature.chat

import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.nanobotkt.core.designsystem.NanobotTheme
import com.nanobotkt.core.model.WorkspaceAccessMode
import com.nanobotkt.core.model.WorkspaceScope
import java.util.concurrent.atomic.AtomicReference
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/**
 * 验证新建会话 Workspace 选择器的用户可见规则和完整路径传递边界。
 *
 * 测试只渲染独立 Dialog，不触发 Root、Repository 或 Gateway；因此可以确认重名展示和
 * 选择回调，而不会创建真实会话或把任何设备数据写入服务端。
 */
@RunWith(AndroidJUnit4::class)
class WorkspaceSelectionUiTest {
    @get:Rule
    val composeRule = createComposeRule()

    @Test
    fun duplicateLeafNamesShowFullPathsAndReturnSelectedScope() {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val selectedScope = AtomicReference<WorkspaceScope?>(null)
        val options = buildWorkspaceOptions(
            listOf(
                workspaceScope("/Users/test/client-a/nanobot"),
                workspaceScope("/Users/test/client-b/nanobot"),
            ),
        )

        composeRule.setContent {
            NanobotTheme(darkTheme = false, dynamicColor = false) {
                WorkspaceSelectionDialog(
                    options = options,
                    onSelect = selectedScope::set,
                    onDismiss = {},
                )
            }
        }

        composeRule.onNodeWithText("/Users/test/client-b/nanobot")
            .assertIsDisplayed()
            .performClick()
        composeRule.onNodeWithText(context.getString(R.string.confirm))
            .assertIsDisplayed()
            .performClick()

        composeRule.runOnIdle {
            // UI 可以展示路径文本，但回调必须返回完整 WorkspaceScope，供新主题请求使用。
            assertEquals("/Users/test/client-b/nanobot", selectedScope.get()?.projectPath)
        }
    }

    @Test
    fun uniqueLeafNamesStayCompact() {
        val options = buildWorkspaceOptions(
            listOf(
                workspaceScope("/Users/test/client-a/nanobot"),
                workspaceScope("/Users/test/client-b/mobile"),
            ),
        )

        composeRule.setContent {
            NanobotTheme(darkTheme = false, dynamicColor = false) {
                WorkspaceSelectionDialog(
                    options = options,
                    onSelect = {},
                    onDismiss = {},
                )
            }
        }

        composeRule.onNodeWithText("nanobot").assertIsDisplayed()
        composeRule.onNodeWithText("mobile").assertIsDisplayed()
    }

    private fun workspaceScope(path: String) = WorkspaceScope(
        projectPath = path,
        accessMode = WorkspaceAccessMode.RESTRICTED,
    )
}
