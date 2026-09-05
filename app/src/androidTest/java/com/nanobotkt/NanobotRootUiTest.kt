package com.nanobotkt

import androidx.compose.ui.test.assertHasClickAction
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.nanobotkt.core.designsystem.NanobotTheme
import com.nanobotkt.feature.auth.GatewayConfigurationError
import java.util.concurrent.atomic.AtomicInteger
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/**
 * 锁定 Gateway 不可达页面的两条恢复路径。
 *
 * 该测试只渲染本地 Compose UI，不读取或写入真实凭据，也不会向 Gateway 发起请求。
 * “重试”和“重新登录”必须始终作为独立可点击操作存在，避免地址配置错误时用户只能
 * 对同一个地址反复重试。
 */
@RunWith(AndroidJUnit4::class)
class NanobotRootUiTest {
    @get:Rule val composeRule = createComposeRule()

    @Test
    fun unreachableGatewayOffersRetryAndLoginAgain() {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val retryCount = AtomicInteger(0)
        val loginAgainCount = AtomicInteger(0)
        val serverUrl = "http://192.168.55.201:8765"

        composeRule.setContent {
            NanobotTheme(darkTheme = false, dynamicColor = false) {
                UnreachableScreen(
                    error = GatewayConfigurationError.Timeout,
                    serverUrl = serverUrl,
                    onRetry = { retryCount.incrementAndGet() },
                    onLoginAgain = { loginAgainCount.incrementAndGet() },
                )
            }
        }

        // 当前失败目标需要明确显示，帮助用户判断是不是地址配置错了。
        composeRule.onNodeWithText(serverUrl).assertIsDisplayed()

        composeRule
            .onNodeWithText(context.getString(R.string.retry))
            .assertIsDisplayed()
            .assertHasClickAction()
            .performClick()
        composeRule.runOnIdle { assertEquals(1, retryCount.get()) }

        composeRule
            .onNodeWithText(context.getString(R.string.login_again))
            .assertIsDisplayed()
            .assertHasClickAction()
            .performClick()
        composeRule.runOnIdle { assertEquals(1, loginAgainCount.get()) }
    }
}
