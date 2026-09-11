package com.nanobotkt.feature.chat

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.test.assertCountEquals
import androidx.compose.ui.test.assertHasClickAction
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onAllNodesWithContentDescription
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onAllNodesWithText
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
    fun drawerShowsNewConversationAction() {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val newTopicRequested = AtomicReference(false)

        composeRule.setContent {
            NanobotTheme(darkTheme = false, dynamicColor = false) {
                ConversationListDrawerContent(
                    items = emptyList(),
                    selectedKey = null,
                    onSelect = {},
                    onNewTopic = { newTopicRequested.set(true) },
                    onTogglePinned = {},
                    onRename = { _, _ -> },
                    onArchive = {},
                    onDelete = {},
                )
            }
        }

        // Drawer 自己必须提供新建入口，不能只验证回调存在；这个断言锁定按钮的可见性和可点击语义。
        composeRule.onNodeWithText(context.getString(R.string.conversation_list_title))
            .assertIsDisplayed()
        composeRule.onNodeWithContentDescription(
            context.getString(R.string.conversation_new_topic),
        )
            .assertIsDisplayed()
            .assertHasClickAction()
            .performClick()
        composeRule.runOnIdle { assertEquals(true, newTopicRequested.get()) }
    }

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

    @Test
    fun pinningMovesItemAfterMenuDismissAndListRemainsInteractive() {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val selectedKey = AtomicReference<String?>(null)

        composeRule.setContent {
            var items by remember {
                mutableStateOf(
                    listOf(
                        ConversationListItem(
                            key = "session-1",
                            title = "Move to pinned",
                            preview = "First",
                            pinned = false,
                        ),
                        ConversationListItem(
                            key = "session-2",
                            title = "Still clickable",
                            preview = "Second",
                            pinned = false,
                        ),
                    ),
                )
            }
            NanobotTheme(darkTheme = false, dynamicColor = false) {
                ConversationListScreen(
                    items = items,
                    selectedKey = null,
                    onBack = {},
                    onSelect = { selectedKey.set(it.key) },
                    onNewTopic = {},
                    onTogglePinned = { key ->
                        // 模拟 Root 收到 optimistic Sidebar 状态后立即把行移入 pinned 分区。
                        items = items.map { item ->
                            if (item.key == key) item.copy(pinned = !item.pinned) else item
                        }
                    },
                    onRename = { _, _ -> },
                    onArchive = {},
                    onDelete = {},
                )
            }
        }

        composeRule.onAllNodesWithContentDescription(context.getString(R.string.topic_actions))[0]
            .performClick()
        composeRule.onNodeWithText(context.getString(R.string.pin)).performClick()
        composeRule.waitForIdle()

        composeRule.onNodeWithText(context.getString(R.string.conversation_pinned_section)).assertIsDisplayed()
        composeRule.onAllNodesWithText(context.getString(R.string.pin)).assertCountEquals(0)
        // Popup 已经退出后，列表中的其他行仍能正常消费点击，锁定“置顶后列表卡死”的回归边界。
        composeRule.onNodeWithText("Still clickable").performClick()
        composeRule.runOnIdle { assertEquals("session-2", selectedKey.get()) }
    }

    @Test
    fun conversationSheetDoesNotDuplicateGlobalSettingsEntry() {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        composeRule.setContent {
            NanobotTheme(darkTheme = false, dynamicColor = false) {
                ConversationListSheet(
                    items = emptyList(),
                    archivedItems = emptyList(),
                    selectedKey = null,
                    visible = true,
                    onDismiss = {},
                    onSelect = {},
                    onNewTopic = {},
                    onTogglePinned = {},
                    onRename = { _, _ -> },
                    onArchive = {},
                    onDelete = {},
                )
            }
        }

        composeRule.onNodeWithText(context.getString(R.string.conversation_list_title))
            .assertIsDisplayed()
        composeRule.onNodeWithContentDescription(
            context.getString(R.string.conversation_new_topic)
        )
            .assertIsDisplayed()
            .assertHasClickAction()
        // Settings 已经由聊天首页顶部入口承载；会话 Sheet 中不再渲染重复的全局设置按钮，
        // 从而避免用户在两个层级看到同一个入口并误以为它们作用域不同。
        composeRule.onAllNodesWithContentDescription(
            context.getString(R.string.system_settings)
        ).assertCountEquals(0)
    }

    @Test
    fun pendingConversationDisablesMutationMenu() {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        composeRule.setContent {
            NanobotTheme(darkTheme = false, dynamicColor = false) {
                ConversationListScreen(
                    items = listOf(
                        ConversationListItem(
                            key = "session-pending",
                            title = "Pending conversation",
                            preview = "Waiting",
                            pinned = false,
                            pending = true,
                        ),
                    ),
                    selectedKey = null,
                    onBack = {},
                    onSelect = {},
                    onNewTopic = {},
                    onTogglePinned = {},
                    onRename = { _, _ -> },
                    onArchive = {},
                    onDelete = {},
                )
            }
        }

        composeRule.onNodeWithContentDescription(context.getString(R.string.topic_actions))
            .assertIsNotEnabled()
    }
}
