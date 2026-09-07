package com.nanobotkt.feature.chat

import androidx.compose.ui.test.assertCountEquals
import androidx.compose.ui.test.assertHasClickAction
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onAllNodesWithContentDescription
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.onRoot
import androidx.compose.ui.test.performClick
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.nanobotkt.core.designsystem.NanobotTheme
import com.nanobotkt.core.transport.TransportStatus
import java.util.concurrent.atomic.AtomicInteger
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/**
 * 锁定新的顶部信息层级：标题进入会话详情，正常连接不显示状态，异常状态只作为标题 Badge；
 * 左侧 Menu 打开 Drawer，右侧 Settings 保持为应用级全局设置入口。测试只渲染本地 Compose 组件，
 * 不产生外部副作用。
 */
@RunWith(AndroidJUnit4::class)
class ChatTopStatusBarUiTest {
    @get:Rule val composeRule = createComposeRule()

    @Test
    fun titleOpensSessionInfoAndDrawerMenuIsDirectlyVisible() {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val titleClickCount = AtomicInteger(0)
        val drawerOpenCount = AtomicInteger(0)
        val settingsOpenCount = AtomicInteger(0)

        composeRule.setContent {
            NanobotTheme(darkTheme = false, dynamicColor = false) {
                ChatTopStatusBar(
                    title = "Test conversation",
                    transportStatus = TransportStatus.OPEN,
                    onOpenDrawer = { drawerOpenCount.incrementAndGet() },
                    onOpenSettings = { settingsOpenCount.incrementAndGet() },
                    onOpenSessionInfo = { titleClickCount.incrementAndGet() },
                )
            }
        }

        val titleNode = composeRule.onNodeWithText("Test conversation")
        val menuNode = composeRule.onNodeWithContentDescription(context.getString(R.string.open_navigation))
        val settingsNode = composeRule.onNodeWithContentDescription(context.getString(R.string.system_settings))
        titleNode.assertIsDisplayed().assertHasClickAction().performClick()
        menuNode.assertIsDisplayed().assertHasClickAction().performClick()
        settingsNode.assertIsDisplayed().assertHasClickAction().performClick()
        composeRule.runOnIdle {
            assertEquals(1, titleClickCount.get())
            assertEquals(1, drawerOpenCount.get())
            assertEquals(1, settingsOpenCount.get())
        }

        // 正常连接属于无需用户处理的成功状态，顶部既不显示绿色标记，也不占据 actions 宽度。
        composeRule
            .onAllNodesWithContentDescription(context.getString(R.string.connection_status_connected))
            .assertCountEquals(0)
        // 顶部不应再把会话标题下面撑出 Workspace 或状态文字第二行。
        composeRule.onAllNodesWithText(context.getString(R.string.session_info_workspace))
            .assertCountEquals(0)
    }

    @Test
    fun connectingUsesAccessibleAnimatedStatusDotAndLongTitleKeepsActionsReachable() {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val longTitle = "这是一个用于验证顶部栏在窄屏中仍然稳定显示操作入口的超长会话标题"

        composeRule.setContent {
            NanobotTheme(darkTheme = false, dynamicColor = false) {
                ChatTopStatusBar(
                    title = longTitle,
                    transportStatus = TransportStatus.RECONNECTING,
                    onOpenDrawer = {},
                    onOpenSettings = {},
                    onOpenSessionInfo = {},
                )
            }
        }

        val titleNode = composeRule.onNodeWithText(longTitle)
        val menuNode = composeRule.onNodeWithContentDescription(context.getString(R.string.open_navigation))
        val connectionNode =
            composeRule.onNodeWithContentDescription(context.getString(R.string.connection_status_connecting))

        titleNode.assertIsDisplayed()
        connectionNode.assertIsDisplayed()
        menuNode.assertIsDisplayed().assertHasClickAction()

        val titleBounds = titleNode.fetchSemanticsNode().boundsInRoot
        val connectionBounds = connectionNode.fetchSemanticsNode().boundsInRoot
        val menuBounds = menuNode.fetchSemanticsNode().boundsInRoot
        val rootBounds = composeRule.onRoot().fetchSemanticsNode().boundsInRoot
        // Badge 的语义边界应明显小于标准操作按钮，并附着在标题右侧，而不是成为独立 action。
        // Menu 已迁移到左侧 navigationIcon，因此不能再沿用旧设置按钮位于右侧时的左右关系断言。
        check(connectionBounds.width < menuBounds.width / 2f)
        check(connectionBounds.center.x > titleBounds.center.x)
        check(menuBounds.right < rootBounds.center.x)
        check(titleBounds.left >= rootBounds.left && titleBounds.right <= rootBounds.right)
    }
}
