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
import java.util.concurrent.atomic.AtomicReference
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/**
 * 锁定会话列表迁移到 Material 3 ListItem 后的关键行为边界。
 *
 * 测试只渲染本地 UI 模型，并通过回调记录选择和置顶请求；它不会读取 Sidebar 状态、修改
 * Gateway 会话或触发 WebSocket，因此可以在模拟器上安全验证列表组件而不产生真实数据副作用。
 */
@RunWith(AndroidJUnit4::class)
class ConversationListScreenUiTest {
    @get:Rule val composeRule = createComposeRule()

    @Test
    fun listItemKeepsConversationSelectionAndTrailingMenuActionsSeparate() {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val selectedKey = AtomicReference<String?>(null)
        val pinnedKey = AtomicReference<String?>(null)
        val item =
            ConversationListItem(
                key = "session-1",
                title = "Material conversation",
                preview = "ListItem preview",
                pinned = false,
            )

        composeRule.setContent {
            NanobotTheme(darkTheme = false, dynamicColor = false) {
                ConversationListScreen(
                    items = listOf(item),
                    selectedKey = null,
                    onBack = {},
                    onSelect = { selectedKey.set(it.key) },
                    onNewTopic = {},
                    onTogglePinned = { pinnedKey.set(it) },
                    onRename = { _, _ -> },
                    onArchive = {},
                    onDelete = {},
                )
            }
        }

        composeRule.onNodeWithText("Material conversation")
            .assertIsDisplayed()
            .assertHasClickAction()
            .performClick()
        composeRule.runOnIdle { assertEquals("session-1", selectedKey.get()) }

        // trailing menu 必须只触发管理动作，不得再次调用整行的 onSelect。
        selectedKey.set(null)
        composeRule.onNodeWithContentDescription(context.getString(R.string.topic_actions))
            .performClick()
        composeRule.onNodeWithText(context.getString(R.string.pin)).performClick()
        composeRule.runOnIdle {
            assertEquals("session-1", pinnedKey.get())
            assertEquals(null, selectedKey.get())
        }
    }
}
