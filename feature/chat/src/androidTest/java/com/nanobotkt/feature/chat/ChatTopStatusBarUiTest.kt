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
import com.nanobotkt.core.designsystem.NanobotTheme
import com.nanobotkt.core.transport.TransportStatus
import java.util.concurrent.atomic.AtomicInteger
import kotlin.math.abs
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/**
 * 锁定新的顶部信息层级：标题进入会话详情，正常连接不显示状态，异常状态只作为标题 Badge，
 * 系统设置直接可见。测试只渲染本地 Compose 组件，不产生外部副作用。
 */
@RunWith(AndroidJUnit4::class)
class ChatTopStatusBarUiTest {
    @get:Rule val composeRule = createComposeRule()

    @Test
    fun titleOpensSessionInfoAndSettingsIsDirectlyVisible() {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val titleClickCount = AtomicInteger(0)
        val settingsClickCount = AtomicInteger(0)

        composeRule.setContent {
            NanobotTheme(darkTheme = false, dynamicColor = false) {
                ChatTopStatusBar(
                    title = "Test conversation",
                    transportStatus = TransportStatus.OPEN,
                    onOpenSettings = { settingsClickCount.incrementAndGet() },
                    onOpenSessionInfo = { titleClickCount.incrementAndGet() },
                )
            }
        }

        val titleNode = composeRule.onNodeWithText("Test conversation")
        val settingsNode = composeRule.onNodeWithContentDescription(context.getString(R.string.system_settings))
        titleNode.assertIsDisplayed().assertHasClickAction().performClick()
        settingsNode.assertIsDisplayed().assertHasClickAction().performClick()
        composeRule.runOnIdle {
            assertEquals(1, titleClickCount.get())
            assertEquals(1, settingsClickCount.get())
        }

        // 正常连接属于无需用户处理的成功状态，顶部既不显示绿色标记，也不占据 actions 宽度。
        composeRule
            .onNodeWithContentDescription(context.getString(R.string.connection_status_connected))
            .assertDoesNotExist()
        // 顶部不应再把会话标题下面撑出 Workspace 或状态文字第二行。
        composeRule.onNodeWithText(context.getString(R.string.session_info_workspace)).assertDoesNotExist()
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
                    onOpenSettings = {},
                    onOpenSessionInfo = {},
                )
            }
        }

        val titleNode = composeRule.onNodeWithText(longTitle)
        val settingsNode = composeRule.onNodeWithContentDescription(context.getString(R.string.system_settings))
        val connectionNode =
            composeRule.onNodeWithContentDescription(context.getString(R.string.connection_status_connecting))

        titleNode.assertIsDisplayed()
        connectionNode.assertIsDisplayed()
        settingsNode.assertIsDisplayed().assertHasClickAction()

        val titleBounds = titleNode.fetchSemanticsNode().boundsInRoot
        val connectionBounds = connectionNode.fetchSemanticsNode().boundsInRoot
        val settingsBounds = settingsNode.fetchSemanticsNode().boundsInRoot
        val rootBounds = composeRule.onRoot().fetchSemanticsNode().boundsInRoot
        // Badge 的语义边界应明显小于标准操作按钮，且附着在标题区域，而不是成为第二个 action。
        check(connectionBounds.width < settingsBounds.width / 2f)
        check(connectionBounds.center.x < settingsBounds.left)
        check(titleBounds.right <= settingsBounds.left)
        check(abs(titleBounds.center.x - rootBounds.center.x) <= 1f)
    }
}
