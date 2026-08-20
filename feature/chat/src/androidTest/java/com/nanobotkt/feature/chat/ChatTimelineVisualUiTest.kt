package com.nanobotkt.feature.chat

import android.graphics.Bitmap
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.asAndroidBitmap
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.captureToImage
import androidx.compose.ui.test.hasTestTag
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.onRoot
import androidx.compose.ui.unit.dp
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.nanobotkt.core.designsystem.NanobotTheme
import com.nanobotkt.core.model.ToolProgressEvent
import com.nanobotkt.core.model.UiMessage
import java.io.File
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/**
 * 在设备上组合渲染本轮精修涉及的生产时间轴组件。
 *
 * 测试数据全部在内存中构造，不读取真实会话、登录凭据或 Gateway；截图只包含固定示例文本，用于核对
 * 长 Prompt 气泡宽度、Markdown 标题层级和已完成 Activity 的视觉权重，不承担像素级快照断言。
 */
@RunWith(AndroidJUnit4::class)
class ChatTimelineVisualUiTest {
    @get:Rule val composeRule = createComposeRule()

    @Test
    fun polishedTimelineComponentsRenderTogether() {
        val userPrompt =
            "请检查这个 Android 聊天页面的状态转换、错误边界和长文本排版，并给出可以直接验证的改进建议。"
        val headingOne = "聊天页面的第二轮精修"
        val headingTwo = "视觉层级与阅读节奏"
        val darkTheme = mutableStateOf(false)

        composeRule.setContent {
            NanobotTheme(darkTheme = darkTheme.value, dynamicColor = false) {
                val playbackCoordinator = remember { TimelinePlaybackCoordinator() }
                Column(
                    modifier =
                        Modifier.fillMaxSize()
                            .background(androidx.compose.material3.MaterialTheme.colorScheme.background)
                            .verticalScroll(rememberScrollState())
                            .padding(horizontal = 16.dp, vertical = 20.dp)
                            .testTag(TIMELINE_VISUAL_TEST_ROOT),
                    verticalArrangement = Arrangement.spacedBy(18.dp),
                ) {
                    UserTimelineMessage(
                        message =
                            UiMessage(
                                id = "visual-user",
                                role = "user",
                                content = userPrompt,
                                createdAt = 1L,
                            ),
                        deliveryState = UserMessageDeliveryState.SENT,
                        resolveUrl = { it },
                        playbackCoordinator = playbackCoordinator,
                        onQuote = {},
                        onFork = null,
                        onRetry = null,
                        menuDismissSignal = 0,
                        highlighted = false,
                    )
                    MarkdownDocument(
                        markdown =
                            """
                            # $headingOne
                            Assistant 正文保持平面文档流，标题只负责组织内容，不应压过页面标题。

                            ## $headingTwo
                            长中文标题应减少突兀换行，同时保留 H1、H2 与正文之间的清晰层级。
                            """.trimIndent(),
                        resolveUrl = { it },
                    )
                    AgentActivityCluster(
                        item =
                            ChatTimelineItem.AgentActivity(
                                key = "activity:visual-completed",
                                messages =
                                    listOf(
                                        UiMessage(
                                            id = "visual-tool",
                                            role = "assistant",
                                            content = "",
                                            kind = "trace",
                                            createdAt = 2L,
                                            toolEvents =
                                                listOf(
                                                    ToolProgressEvent(
                                                        phase = "completed",
                                                        name = "inspect_ui",
                                                    )
                                                ),
                                        )
                                    ),
                                turnId = "visual-turn",
                                turnLatencyMs = 3_200L,
                                startedAtMs = null,
                                isStreaming = false,
                            ),
                        onPreview = {},
                    )
                }
            }
        }

        composeRule.onNode(hasTestTag(TIMELINE_VISUAL_TEST_ROOT)).assertIsDisplayed()
        composeRule.onNodeWithText(userPrompt).assertIsDisplayed()
        composeRule.onNodeWithText(headingOne).assertIsDisplayed()
        composeRule.onNodeWithText(headingTwo).assertIsDisplayed()
        saveRootScreenshot(TIMELINE_VISUAL_LIGHT_SCREENSHOT)

        // 同一批生产组件直接切换暗色主题，确认透明完成态不会消失在背景中，用户 tonal 气泡也不会
        // 因固定浅色值失去对比度。测试仍不读取任何真实会话或外部状态。
        composeRule.runOnIdle { darkTheme.value = true }
        saveRootScreenshot(TIMELINE_VISUAL_DARK_SCREENSHOT)
    }

    /** 输出到测试应用私有目录，避免截图进入仓库或携带真实用户数据。 */
    private fun saveRootScreenshot(fileName: String) {
        composeRule.waitForIdle()
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val outputDir = requireNotNull(context.getExternalFilesDir(null))
        val outputFile = File(outputDir, fileName)
        val bitmap = composeRule.onRoot().captureToImage().asAndroidBitmap()
        outputFile.outputStream().use { stream ->
            check(bitmap.compress(Bitmap.CompressFormat.PNG, 100, stream)) {
                "Failed to encode timeline visual screenshot"
            }
        }
    }

    private companion object {
        const val TIMELINE_VISUAL_TEST_ROOT = "timeline_visual_test_root"
        const val TIMELINE_VISUAL_LIGHT_SCREENSHOT = "timeline-visual-polish-light.png"
        const val TIMELINE_VISUAL_DARK_SCREENSHOT = "timeline-visual-polish-dark.png"
    }
}
