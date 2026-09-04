package com.nanobotkt.feature.chat

import androidx.compose.ui.test.assertHasClickAction
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
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
 * 验证新主题输入框上方 Workspace 选择器的展示与回调边界。
 *
 * 测试只渲染独立选择器，不触发 Root、Repository 或 Gateway；这样可以确认完整路径传递，
 * 同时不会创建真实会话或把设备上的用户数据带入测试产物。
 */
@RunWith(AndroidJUnit4::class)
class WorkspaceSelectionUiTest {
    @get:Rule val composeRule = createComposeRule()

    @Test
    fun duplicateLeafNamesShowFullPathsAndReturnSelectedScope() {
        val selectedScope = AtomicReference<WorkspaceScope?>(null)
        val options =
            buildWorkspaceOptions(
                listOf(
                    workspaceScope("/Users/test/client-a/nanobot"),
                    workspaceScope("/Users/test/client-b/nanobot"),
                )
            )

        composeRule.setContent {
            NanobotTheme(darkTheme = false, dynamicColor = false) {
                NewTopicWorkspaceSelector(
                    currentScope = options.first().scope,
                    options = options,
                    enabled = true,
                    onSelect = selectedScope::set,
                )
            }
        }

        composeRule.onNodeWithTag(NEW_TOPIC_WORKSPACE_SELECTOR_TEST_TAG)
            .assertIsDisplayed()
            .assertHasClickAction()
            .performClick()
        composeRule.onNodeWithText("/Users/test/client-b/nanobot")
            .assertIsDisplayed()
            .performClick()

        composeRule.runOnIdle {
            // 展示可以使用目录名或完整路径，但业务回调必须保留规范化后的绝对路径。
            assertEquals("/Users/test/client-b/nanobot", selectedScope.get()?.projectPath)
        }
    }

    @Test
    fun multipleWorkspacesCannotOpenMenuWhenGatewayDisablesProjectChanges() {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val options =
            buildWorkspaceOptions(
                listOf(
                    workspaceScope("/Users/test/client-a/nanobot"),
                    workspaceScope("/Users/test/client-b/mobile"),
                )
            )

        composeRule.setContent {
            NanobotTheme(darkTheme = false, dynamicColor = false) {
                NewTopicWorkspaceSelector(
                    currentScope = options.first().scope,
                    options = options,
                    enabled = false,
                    onSelect = {},
                )
            }
        }

        composeRule.onNodeWithText(
            context.getString(R.string.current_workspace, options.first().displayName)
        ).assertIsDisplayed()
        // 候选数量不能绕过 Gateway 能力边界：服务端禁止改项目时仍展示当前 Workspace，
        // 但整行必须不可点击，避免先接受选择、再在首次发送时由服务端拒绝。
        composeRule.onNodeWithTag(NEW_TOPIC_WORKSPACE_SELECTOR_TEST_TAG)
            .assertIsNotEnabled()
    }

    @Test
    fun singleWorkspaceIsVisibleButCannotOpenASelectionMenu() {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val option = buildWorkspaceOptions(listOf(workspaceScope("/Users/test/mobile"))).single()

        composeRule.setContent {
            NanobotTheme(darkTheme = false, dynamicColor = false) {
                NewTopicWorkspaceSelector(
                    currentScope = option.scope,
                    options = listOf(option),
                    enabled = true,
                    onSelect = {},
                )
            }
        }

        composeRule.onNodeWithText(context.getString(R.string.current_workspace, "mobile"))
            .assertIsDisplayed()
        // 只有一个 Workspace 时仍展示当前目录，但不制造一个没有实际选项的可点击入口。
        composeRule.onNodeWithTag(NEW_TOPIC_WORKSPACE_SELECTOR_TEST_TAG)
            .assertIsNotEnabled()
    }

    private fun workspaceScope(path: String) =
        WorkspaceScope(
            projectPath = path,
            accessMode = WorkspaceAccessMode.RESTRICTED,
        )
}
