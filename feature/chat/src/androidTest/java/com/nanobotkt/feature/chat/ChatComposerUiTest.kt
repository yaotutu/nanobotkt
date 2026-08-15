package com.nanobotkt.feature.chat

import android.graphics.Bitmap
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.asAndroidBitmap
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.test.assertHasClickAction
import androidx.compose.ui.test.assertIsEnabled
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.captureToImage
import androidx.compose.ui.test.hasTestTag
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.onRoot
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performTextInput
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.nanobotkt.core.designsystem.NanobotTheme
import java.io.File
import java.util.concurrent.atomic.AtomicInteger
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/**
 * 在设备上直接渲染生产 Composer 的轻量仪器测试。
 *
 * 测试刻意使用空白本地状态，不读取登录凭据、真实会话或 Gateway 数据。这样既能验证输入组件
 * 的真实 Compose 布局与交互，也不会把用户数据写入截图、UI dump 或测试日志。
 */
@RunWith(AndroidJUnit4::class)
class ChatComposerUiTest {
    @get:Rule val composeRule = createComposeRule()

    @Test
    fun composerRendersCompactControlsAndKeepsCoreActionsReachable() {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val composerState = mutableStateOf(ComposerUiState())
        val darkTheme = mutableStateOf(false)
        val conversationOpenCount = AtomicInteger(0)
        val imagePickCount = AtomicInteger(0)

        composeRule.setContent {
            NanobotTheme(darkTheme = darkTheme.value, dynamicColor = false) {
                Column(
                    modifier =
                        Modifier.fillMaxSize()
                            .background(androidx.compose.material3.MaterialTheme.colorScheme.background)
                            .testTag(COMPOSER_TEST_ROOT),
                ) {
                    // Spacer 模拟真实聊天页的时间轴，把 Composer 固定在屏幕底部，确保截图中的
                    // 尺寸关系、系统导航栏避让和实际页面一致，而不是孤立地测量一个组件。
                    Spacer(Modifier.weight(1f))
                    ComposerLayout(
                        state = composerState.value,
                        active = false,
                        slashCommands = emptyList(),
                        skills = emptyList(),
                        cliApps = emptyList(),
                        mcpPresets = emptyList(),
                        placeholder = context.getString(R.string.composer_placeholder),
                        onTextChange = { text, cursor ->
                            composerState.value =
                                composerState.value.copy(text = text, cursorPosition = cursor)
                        },
                        onSelectSlashCommand = {},
                        onSelectSkillMention = {},
                        onSelectCapabilityMention = {},
                        onSend = {},
                        onStop = {},
                        onRemoveAttachment = {},
                        onPickImages = { imagePickCount.incrementAndGet() },
                        onPickFiles = {},
                        onOpenConversationList = { conversationOpenCount.incrementAndGet() },
                    )
                }
            }
        }

        val conversationDescription = context.getString(R.string.open_conversation_list)
        val attachmentDescription = context.getString(R.string.add_attachment)
        val sendDescription = context.getString(R.string.send)
        val placeholder = context.getString(R.string.composer_placeholder)

        composeRule.onNode(hasTestTag(COMPOSER_TEST_ROOT)).assertIsDisplayed()
        composeRule.onNodeWithText(context.getString(R.string.conversation_button_label))
            .assertIsDisplayed()
        composeRule.onNodeWithContentDescription(conversationDescription)
            .assertHasClickAction()
            .performClick()
        composeRule.runOnIdle { assertEquals(1, conversationOpenCount.get()) }

        // 空草稿时发送动作保持可见但不可用；输入文字后应立即变为可发送状态。
        composeRule.onNodeWithContentDescription(sendDescription).assertIsNotEnabled()
        saveRootScreenshot(SCREENSHOT_LIGHT_EMPTY)

        composeRule.onNodeWithContentDescription(attachmentDescription).performClick()
        composeRule.onNodeWithText(context.getString(R.string.attach_image))
            .assertIsDisplayed()
            .performClick()
        composeRule.runOnIdle { assertEquals(1, imagePickCount.get()) }

        composeRule.onNodeWithContentDescription(placeholder).performTextInput("Simulator draft")
        composeRule.onNodeWithContentDescription(sendDescription).assertIsEnabled()
        saveRootScreenshot(SCREENSHOT_LIGHT_DRAFT)

        // 同一生产组件切换暗色主题后再次截图，验证透明底栏与描边没有依赖浅色背景。
        composeRule.runOnIdle { darkTheme.value = true }
        saveRootScreenshot(SCREENSHOT_DARK_DRAFT)
    }

    private fun saveRootScreenshot(fileName: String) {
        composeRule.waitForIdle()
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val outputDir = requireNotNull(context.getExternalFilesDir(null))
        val outputFile = File(outputDir, fileName)
        val bitmap = composeRule.onRoot().captureToImage().asAndroidBitmap()
        outputFile.outputStream().use { stream ->
            check(bitmap.compress(Bitmap.CompressFormat.PNG, 100, stream)) {
                "Failed to encode Composer screenshot"
            }
        }
    }

    private companion object {
        const val COMPOSER_TEST_ROOT = "composer_test_root"
        const val SCREENSHOT_LIGHT_EMPTY = "composer-light-empty.png"
        const val SCREENSHOT_LIGHT_DRAFT = "composer-light-draft.png"
        const val SCREENSHOT_DARK_DRAFT = "composer-dark-draft.png"
    }
}
