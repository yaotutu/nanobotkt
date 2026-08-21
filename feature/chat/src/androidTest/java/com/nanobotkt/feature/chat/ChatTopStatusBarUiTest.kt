package com.nanobotkt.feature.chat

import androidx.compose.ui.test.assertHasClickAction
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.nanobotkt.core.designsystem.NanobotTheme
import java.util.concurrent.atomic.AtomicInteger
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/**
 * 锁定聊天页顶部“会话导航 / 系统设置 / 当前会话设置”的独立入口和排列关系，防止
 * 会话入口再次挤占 Composer，或把应用级操作混入会话级菜单。
 *
 * 测试只渲染本地 Compose 组件，不读取登录凭据、会话内容或 Gateway 状态，因此不会产生外部副作用。
 */
@RunWith(AndroidJUnit4::class)
class ChatTopStatusBarUiTest {
    @get:Rule val composeRule = createComposeRule()

    @Test
    fun conversationNavigationAndSettingsActionsRemainOrdered() {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val conversationOpenCount = AtomicInteger(0)
        val settingsOpenCount = AtomicInteger(0)
        val sessionMenuOpenCount = AtomicInteger(0)

        composeRule.setContent {
            NanobotTheme(darkTheme = false, dynamicColor = false) {
                ChatTopStatusBar(
                    title = "Test conversation",
                    status = ChatHeaderStatus.IDLE,
                    configMenuOpen = false,
                    hasPromptNavigator = false,
                    hasSessionInfo = false,
                    hasAccessSettings = false,
                    onOpenConversationList = { conversationOpenCount.incrementAndGet() },
                    onOpenSettings = { settingsOpenCount.incrementAndGet() },
                    onStatusClick = {},
                    onConfigMenuOpenChange = { open ->
                        if (open) sessionMenuOpenCount.incrementAndGet()
                    },
                    onOpenPromptNavigator = {},
                    onOpenSessionInfo = {},
                    onOpenModel = {},
                    onOpenAccess = {},
                )
            }
        }

        val conversationNode =
            composeRule.onNodeWithContentDescription(
                context.getString(R.string.open_conversation_list)
            )
        val titleNode = composeRule.onNodeWithText("Test conversation")
        val systemSettingsNode =
            composeRule.onNodeWithContentDescription(context.getString(R.string.system_settings))
        val sessionSettingsNode =
            composeRule.onNodeWithContentDescription(context.getString(R.string.current_session_settings))

        conversationNode
            .assertIsDisplayed()
            .assertHasClickAction()
            .performClick()
        composeRule.runOnIdle { assertEquals(1, conversationOpenCount.get()) }

        systemSettingsNode
            .assertIsDisplayed()
            .assertHasClickAction()
            .performClick()
        composeRule.runOnIdle { assertEquals(1, settingsOpenCount.get()) }

        sessionSettingsNode
            .assertIsDisplayed()
            .assertHasClickAction()
            .performClick()
        composeRule.runOnIdle { assertEquals(1, sessionMenuOpenCount.get()) }

        // 标题、系统设置、当前会话设置必须从左到右排列。fetchSemanticsNode 本身会等待 Compose 空闲，
        // 因此不能嵌套在 runOnIdle 的主线程回调中，否则测试框架会拒绝重复同步。这里在测试线程依次
        // 读取边界，既能锁定视觉顺序，也能避免仅验证“按钮存在”却漏掉设置入口回到标题左侧的回归。
        val conversationBounds = conversationNode.fetchSemanticsNode().boundsInRoot
        val titleBounds = titleNode.fetchSemanticsNode().boundsInRoot
        val systemSettingsBounds = systemSettingsNode.fetchSemanticsNode().boundsInRoot
        val sessionSettingsBounds = sessionSettingsNode.fetchSemanticsNode().boundsInRoot
        check(conversationBounds.left < titleBounds.left)
        check(titleBounds.left < systemSettingsBounds.left)
        check(systemSettingsBounds.left < sessionSettingsBounds.left)
    }

    @Test
    fun longTitleAndRunningStatusKeepRightActionsReachable() {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val longTitle = "这是一个用于验证顶部栏在窄屏中仍然稳定显示操作入口的超长会话标题"
        val statusClickCount = AtomicInteger(0)

        composeRule.setContent {
            NanobotTheme(darkTheme = false, dynamicColor = false) {
                ChatTopStatusBar(
                    title = longTitle,
                    status = ChatHeaderStatus.RUNNING,
                    configMenuOpen = false,
                    hasPromptNavigator = false,
                    hasSessionInfo = false,
                    hasAccessSettings = false,
                    onOpenConversationList = {},
                    onOpenSettings = {},
                    onStatusClick = { statusClickCount.incrementAndGet() },
                    onConfigMenuOpenChange = {},
                    onOpenPromptNavigator = {},
                    onOpenSessionInfo = {},
                    onOpenModel = {},
                    onOpenAccess = {},
                )
            }
        }

        val titleNode = composeRule.onNodeWithText(longTitle)
        val statusNode = composeRule.onNodeWithText(context.getString(R.string.chat_status_running))
        val systemSettingsNode =
            composeRule.onNodeWithContentDescription(context.getString(R.string.system_settings))
        val sessionSettingsNode =
            composeRule.onNodeWithContentDescription(context.getString(R.string.current_session_settings))

        titleNode.assertIsDisplayed()
        statusNode.assertIsDisplayed().assertHasClickAction().performClick()
        systemSettingsNode.assertIsDisplayed().assertHasClickAction()
        sessionSettingsNode.assertIsDisplayed().assertHasClickAction()
        composeRule.runOnIdle { assertEquals(1, statusClickCount.get()) }

        // Text 的语义仍保留完整标题，因此这里通过真实布局边界锁定 ellipsis 的结果：标题可被压缩，
        // 但不得覆盖或挤出右侧应用级、会话级操作。避免使用固定像素宽度，以兼容不同测试密度。
        val titleBounds = titleNode.fetchSemanticsNode().boundsInRoot
        val systemSettingsBounds = systemSettingsNode.fetchSemanticsNode().boundsInRoot
        val sessionSettingsBounds = sessionSettingsNode.fetchSemanticsNode().boundsInRoot
        check(titleBounds.right <= systemSettingsBounds.left)
        check(systemSettingsBounds.left < sessionSettingsBounds.left)
    }
}
