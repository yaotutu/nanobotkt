package com.nanobotkt.feature.chat

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.width
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.test.SemanticsMatcher
import androidx.compose.ui.test.assert
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.dp
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.nanobotkt.core.designsystem.NanobotTheme
import java.util.concurrent.atomic.AtomicInteger
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/**
 * 验证 Chat 的空状态和 Composer 上方错误反馈，不依赖登录、真实会话或 Gateway。
 *
 * 测试刻意覆盖 320dp 宽度与 2.0 倍字体，确保文案换行由内容自然撑高，而不是通过固定高度
 * 截断；同时锁定 TalkBack live region 与关闭按钮的最小触控区。
 */
@RunWith(AndroidJUnit4::class)
class ChatStateFeedbackUiTest {
    @get:Rule val composeRule = createComposeRule()

    @Test
    fun inlineErrorIsAnnouncedAndCanBeDismissed() {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val dismissCount = AtomicInteger(0)

        composeRule.setContent {
            NanobotTheme(darkTheme = false, dynamicColor = false) {
                Box(Modifier.background(MaterialTheme.colorScheme.background)) {
                    ChatInlineErrorNotice(
                        presentation =
                            ChatErrorPresentation(
                                titleRes = R.string.chat_error_turn_rejected_title,
                                bodyRes = R.string.chat_error_turn_rejected_body,
                            ),
                        onDismiss = { dismissCount.incrementAndGet() },
                    )
                }
            }
        }

        composeRule
            .onNodeWithTag(CHAT_INLINE_ERROR_TEST_TAG, useUnmergedTree = true)
            .assertIsDisplayed()
            .assert(
                SemanticsMatcher.expectValue(
                    SemanticsProperties.LiveRegion,
                    LiveRegionMode.Assertive,
                )
            )
        composeRule
            .onNodeWithText(context.getString(R.string.chat_error_turn_rejected_title))
            .assertIsDisplayed()
        composeRule
            .onNodeWithText(context.getString(R.string.chat_error_turn_rejected_body))
            .assertIsDisplayed()

        val dismissNode =
            composeRule.onNodeWithContentDescription(
                context.getString(R.string.dismiss_chat_error)
            )
        dismissNode.assertIsDisplayed().performClick()
        composeRule.runOnIdle { assertEquals(1, dismissCount.get()) }

        // 视觉图标只有 18dp，但 IconButton 的实际语义边界必须保持至少 48dp，避免大字体或窄屏下
        // 关闭入口变成难以触达的小目标。
        val density = composeRule.density
        val dismissBounds = dismissNode.fetchSemanticsNode().boundsInRoot
        val minimumTouchTargetPx = with(density) { 48.dp.toPx() }
        check(dismissBounds.width >= minimumTouchTargetPx)
        check(dismissBounds.height >= minimumTouchTargetPx)
    }

    @Test
    fun errorAndEmptyStateRemainReadableAtLargeFontOnNarrowWidth() {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val showError = mutableStateOf(true)

        composeRule.setContent {
            NanobotTheme(darkTheme = showError.value, dynamicColor = false) {
                val currentDensity = LocalDensity.current
                CompositionLocalProvider(
                    LocalDensity provides Density(currentDensity.density, fontScale = 2f)
                ) {
                    Box(
                        modifier =
                            Modifier.width(320.dp)
                                .fillMaxSize()
                                .background(MaterialTheme.colorScheme.background)
                    ) {
                        if (showError.value) {
                            ChatInlineErrorNotice(
                                presentation =
                                    ChatErrorPresentation(
                                        titleRes = R.string.chat_error_message_too_big_title,
                                        bodyRes = R.string.chat_error_message_too_big_body,
                                    ),
                                onDismiss = {},
                            )
                        } else {
                            EmptyChat(Modifier.fillMaxSize())
                        }
                    }
                }
            }
        }

        composeRule
            .onNodeWithText(context.getString(R.string.chat_error_message_too_big_title))
            .assertIsDisplayed()
        composeRule
            .onNodeWithText(context.getString(R.string.chat_error_message_too_big_body))
            .assertIsDisplayed()
        composeRule
            .onNodeWithContentDescription(context.getString(R.string.dismiss_chat_error))
            .assertIsDisplayed()

        // 在同一个 Composition 内切换内容，避免测试 Activity 重复 setContent；同时验证空状态
        // 标题和新增说明在极端字体比例下都能完整进入布局。
        composeRule.runOnIdle { showError.value = false }
        composeRule.onNodeWithText(context.getString(R.string.empty_title)).assertIsDisplayed()
        composeRule.onNodeWithText(context.getString(R.string.empty_body)).assertIsDisplayed()
    }
}
