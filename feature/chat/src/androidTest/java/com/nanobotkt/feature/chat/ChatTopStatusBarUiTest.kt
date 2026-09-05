package com.nanobotkt.feature.chat

import androidx.compose.ui.test.assertHasClickAction
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.onRoot
import androidx.compose.ui.test.performClick
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import com.nanobotkt.core.designsystem.NanobotTheme
import java.util.concurrent.atomic.AtomicInteger
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/**
 * 锁定聊天页顶部“居中标题 / 右侧低频菜单”的排列关系，防止为了视觉对称再次把
 * 高频操作或系统设置放回难以单手触达的左上角。会话导航入口由 ChatComposerUiTest
 * 负责验证其底部位置，系统设置则作为应用级操作放在右侧菜单末尾。
 *
 * 测试只渲染本地 Compose 组件，不读取登录凭据、会话内容或 Gateway 状态，因此不会产生外部副作用。
 */
@RunWith(AndroidJUnit4::class)
class ChatTopStatusBarUiTest {
    @get:Rule val composeRule = createComposeRule()

    @Test
    fun titleRemainsCenteredAndSettingsLivesInOverflowMenu() {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val settingsOpenCount = AtomicInteger(0)
        val sessionMenuOpenCount = AtomicInteger(0)

        composeRule.setContent {
            NanobotTheme(darkTheme = false, dynamicColor = false) {
                var configMenuOpen by remember { mutableStateOf(false) }
                ChatTopStatusBar(
                    title = "Test conversation",
                    status = ChatHeaderStatus.IDLE,
                    configMenuOpen = configMenuOpen,
                    hasPromptNavigator = false,
                    hasSessionInfo = false,
                    hasAccessSettings = false,
                    onOpenSettings = { settingsOpenCount.incrementAndGet() },
                    onStatusClick = {},
                    onConfigMenuOpenChange = { open ->
                        configMenuOpen = open
                        if (open) sessionMenuOpenCount.incrementAndGet()
                    },
                    onOpenPromptNavigator = {},
                    onOpenSessionInfo = {},
                    onOpenModel = {},
                    onOpenAccess = {},
                )
            }
        }

        val titleNode = composeRule.onNodeWithText("Test conversation")
        val sessionSettingsNode =
            composeRule.onNodeWithContentDescription(context.getString(R.string.current_session_settings))

        titleNode.assertIsDisplayed()
        sessionSettingsNode.assertIsDisplayed().assertHasClickAction().performClick()
        composeRule.runOnIdle { assertEquals(1, sessionMenuOpenCount.get()) }

        // 系统设置不再占据左上角；它位于已经展开的右侧菜单中，并保持可点击的独立语义。
        composeRule
            .onNodeWithText(context.getString(R.string.system_settings))
            .assertIsDisplayed()
            .assertHasClickAction()
            .performClick()
        composeRule.runOnIdle { assertEquals(1, settingsOpenCount.get()) }

        // 标题中心应与 TopAppBar 根节点中心一致。使用很小的浮点容差吸收不同测试密度下的
        // 像素取整，不依赖某个固定设备宽度。
        val titleBounds = titleNode.fetchSemanticsNode().boundsInRoot
        val rootBounds = composeRule.onRoot().fetchSemanticsNode().boundsInRoot
        check(kotlin.math.abs(titleBounds.center.x - rootBounds.center.x) <= 1f)
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
        val sessionSettingsNode =
            composeRule.onNodeWithContentDescription(context.getString(R.string.current_session_settings))

        titleNode.assertIsDisplayed()
        statusNode.assertIsDisplayed().assertHasClickAction().performClick()
        sessionSettingsNode.assertIsDisplayed().assertHasClickAction()
        composeRule.runOnIdle { assertEquals(1, statusClickCount.get()) }

        // Text 的语义仍保留完整标题，因此通过真实布局边界锁定 ellipsis：长标题可以压缩，
        // 但不能覆盖右侧菜单；与此同时，标题整体仍应围绕屏幕中轴布局。
        val titleBounds = titleNode.fetchSemanticsNode().boundsInRoot
        val sessionSettingsBounds = sessionSettingsNode.fetchSemanticsNode().boundsInRoot
        val rootBounds = composeRule.onRoot().fetchSemanticsNode().boundsInRoot
        check(titleBounds.right <= sessionSettingsBounds.left)
        check(kotlin.math.abs(titleBounds.center.x - rootBounds.center.x) <= 1f)
    }
}
