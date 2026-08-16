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
 * 锁定聊天页顶部“系统设置 / 当前会话设置”的独立入口和排列关系，防止后续调整标题时
 * 再次把系统设置放回左侧导航位，或把应用级操作混入会话级菜单。
 *
 * 测试只渲染本地 Compose 组件，不读取登录凭据、会话内容或 Gateway 状态，因此不会产生外部副作用。
 */
@RunWith(AndroidJUnit4::class)
class ChatTopStatusBarUiTest {
    @get:Rule val composeRule = createComposeRule()

    @Test
    fun globalSettingsAndSessionMenuRemainSeparateRightSideActions() {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val settingsOpenCount = AtomicInteger(0)
        val sessionMenuOpenCount = AtomicInteger(0)

        composeRule.setContent {
            NanobotTheme(darkTheme = false, dynamicColor = false) {
                ChatTopStatusBar(
                    title = "Test conversation",
                    status = ChatHeaderStatus.IDLE,
                    queuedPrompts = emptyList(),
                    queueOpen = false,
                    configMenuOpen = false,
                    hasPromptNavigator = false,
                    hasSessionInfo = false,
                    hasAccessSettings = false,
                    onOpenSettings = { settingsOpenCount.incrementAndGet() },
                    onStatusClick = {},
                    onQueueOpenChange = {},
                    onConfigMenuOpenChange = { open ->
                        if (open) sessionMenuOpenCount.incrementAndGet()
                    },
                    onQueuedPromptClick = {},
                    onOpenPromptNavigator = {},
                    onOpenSessionInfo = {},
                    onOpenModel = {},
                    onOpenAccess = {},
                )
            }
        }

        val titleNode = composeRule.onNodeWithText("Test conversation")
        val systemSettingsNode =
            composeRule.onNodeWithContentDescription(context.getString(R.string.system_settings))
        val sessionSettingsNode =
            composeRule.onNodeWithContentDescription(context.getString(R.string.current_session_settings))

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
        val titleBounds = titleNode.fetchSemanticsNode().boundsInRoot
        val systemSettingsBounds = systemSettingsNode.fetchSemanticsNode().boundsInRoot
        val sessionSettingsBounds = sessionSettingsNode.fetchSemanticsNode().boundsInRoot
        check(titleBounds.left < systemSettingsBounds.left)
        check(systemSettingsBounds.left < sessionSettingsBounds.left)
    }
}
