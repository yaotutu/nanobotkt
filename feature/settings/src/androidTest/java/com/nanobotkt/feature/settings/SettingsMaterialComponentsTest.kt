package com.nanobotkt.feature.settings

import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Settings
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.test.assertHasClickAction
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertIsEnabled
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.assertIsOff
import androidx.compose.ui.test.assertIsOn
import androidx.compose.ui.test.assertIsSelected
import androidx.compose.ui.test.isToggleable
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.nanobotkt.core.designsystem.NanobotTheme
import java.util.concurrent.atomic.AtomicInteger
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/**
 * 验证 Settings 已迁移到 Material 3 的共享交互组件。
 *
 * 测试只在本地 Compose Host Activity 中渲染组件，不创建 SettingsViewModel、不读取凭据，
 * 也不会连接真实 Gateway；它锁定的是原生组件提供的开关、单选、按钮和点击语义。
 */
@RunWith(AndroidJUnit4::class)
class SettingsMaterialComponentsTest {
    @get:Rule val composeRule = createComposeRule()

    @Test
    fun toggleSettingUsesSwitchSemanticsAndPropagatesState() {
        var checked by mutableStateOf(false)
        composeRule.setMaterialContent {
            ToggleSetting(checked = checked, onCheckedChange = { checked = it })
        }

        val switch = composeRule.onNode(isToggleable())
        switch.assertIsDisplayed().assertIsOff().performClick()
        composeRule.runOnIdle { assertEquals(true, checked) }
        switch.assertIsOn()
    }

    @Test
    fun segmentedSettingKeepsExactlyOneSelectedOption() {
        var selectedIndex by mutableIntStateOf(0)
        composeRule.setMaterialContent {
            SegmentedSetting(
                options = listOf("Light", "Dark"),
                selectedIndex = selectedIndex,
                onSelected = { selectedIndex = it },
            )
        }

        composeRule.onNodeWithText("Light").assertIsSelected()
        composeRule.onNodeWithText("Dark").performClick().assertIsSelected()
        composeRule.runOnIdle { assertEquals(1, selectedIndex) }
    }

    @Test
    fun settingsHeaderExposesTitleAndBackAction() {
        val backCount = AtomicInteger(0)
        composeRule.setMaterialContent {
            SettingsHeader(title = "Appearance", onBack = { backCount.incrementAndGet() })
        }

        composeRule.onNodeWithText("Appearance").assertIsDisplayed()
        composeRule.onNodeWithContentDescription("Back")
            .assertIsDisplayed()
            .assertHasClickAction()
            .performClick()
        composeRule.runOnIdle { assertEquals(1, backCount.get()) }
    }

    @Test
    fun settingsRowPreservesWholeRowClickSemanticsAndText() {
        val clickCount = AtomicInteger(0)
        composeRule.setMaterialContent {
            SettingsRow(
                icon = Icons.Outlined.Settings,
                title = "System",
                subtitle = "Gateway and runtime",
                value = "Connected",
                onClick = { clickCount.incrementAndGet() },
            )
        }

        composeRule.onNodeWithText("System")
            .assertIsDisplayed()
            .assertHasClickAction()
            .performClick()
        composeRule.onNodeWithText("Gateway and runtime").assertIsDisplayed()
        composeRule.onNodeWithText("Connected").assertIsDisplayed()
        composeRule.runOnIdle { assertEquals(1, clickCount.get()) }
    }

    @Test
    fun numberStepperDisablesBoundaryActionAndClampsUpdates() {
        var value by mutableIntStateOf(1)
        composeRule.setMaterialContent {
            NumberStepper(value = value, range = 1..2, onValueChange = { value = it })
        }

        composeRule.onNodeWithContentDescription("Decrease").assertIsNotEnabled()
        composeRule.onNodeWithContentDescription("Increase")
            .assertIsEnabled()
            .performClick()
        composeRule.runOnIdle { assertEquals(2, value) }
        composeRule.onNodeWithContentDescription("Increase").assertIsNotEnabled()
        composeRule.onNodeWithContentDescription("Decrease").assertIsEnabled()
    }

    /** 统一关闭动态色，保证测试只验证组件语义，不受模拟器壁纸主题影响。 */
    private fun androidx.compose.ui.test.junit4.ComposeContentTestRule.setMaterialContent(
        content: @androidx.compose.runtime.Composable () -> Unit,
    ) {
        setContent {
            NanobotTheme(darkTheme = false, dynamicColor = false, content = content)
        }
    }
}
