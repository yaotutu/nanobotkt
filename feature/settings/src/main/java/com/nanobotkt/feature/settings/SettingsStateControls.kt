package com.nanobotkt.feature.settings

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.SegmentedButton
import androidx.compose.material3.SegmentedButtonDefaults
import androidx.compose.material3.SingleChoiceSegmentedButtonRow
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp

/** 保存状态、分段选择、开关与通用间距组件。 */
@Composable
internal fun SettingsSaveFooter(
    dirty: Boolean,
    saving: Boolean,
    pendingRestart: Boolean = false,
    disabledMessage: String? = null,
    error: String? = null,
    onSave: () -> Unit,
) {
    CardDivider()
    Column(modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 16.dp)) {
        val message =
            error
                ?: disabledMessage
                ?: when {
                    pendingRestart && !dirty -> "Saved. Restart when ready."
                    dirty -> "Save changes, then restart when ready."
                    else -> "Settings are up to date."
                }
        Text(
            text = message,
            color =
                if (error != null || disabledMessage != null) {
                    MaterialTheme.colorScheme.error
                } else {
                    MaterialTheme.colorScheme.onSurfaceVariant
                },
            style = MaterialTheme.typography.bodySmall,
        )
        Spacer(Modifier.height(12.dp))
        Button(
            onClick = onSave,
            enabled = dirty && !saving && disabledMessage == null,
            contentPadding = PaddingValues(horizontal = 24.dp, vertical = 12.dp),
        ) {
            if (saving) {
                // 进度指示器继承 Button 的 contentColor，确保在主题、禁用和动态配色下
                // 均由 Material 3 状态层自动维持对比度。
                CircularProgressIndicator(
                    modifier = Modifier.size(18.dp),
                    strokeWidth = 2.dp,
                    color = androidx.compose.material3.LocalContentColor.current,
                )
                Spacer(Modifier.width(8.dp))
            }
            Text(if (saving) "Saving" else "Save")
        }
    }
}

internal fun List<Pair<String, String>>.withCurrent(value: String): List<Pair<String, String>> =
    if (value.isBlank() || any { it.first == value }) this else listOf(value to value) + this

/**
 * Material 3 单选分段按钮。
 *
 * 这里保留索引式接口以避免配置页改动业务状态模型；视觉、选择指示和最小触控目标全部交由
 * SegmentedButton 处理，不再手工拼接 Surface、阴影和圆角。
 */
@Composable
internal fun SegmentedSetting(
    options: List<String>,
    selectedIndex: Int,
    onSelected: (Int) -> Unit,
) {
    SingleChoiceSegmentedButtonRow {
        options.forEachIndexed { index, option ->
            SegmentedButton(
                selected = index == selectedIndex,
                onClick = { onSelected(index) },
                shape = SegmentedButtonDefaults.itemShape(index = index, count = options.size),
                label = {
                    Text(
                        text = option,
                        maxLines = 1,
                        style = MaterialTheme.typography.labelLarge,
                    )
                },
            )
        }
    }
}

/** 使用原生 Switch 统一轨道、拇指、状态层、动画和无障碍开关语义。 */
@Composable
internal fun ToggleSetting(checked: Boolean, onCheckedChange: (Boolean) -> Unit) {
    Switch(checked = checked, onCheckedChange = onCheckedChange)
}

@Composable
internal fun CardDivider() {
    HorizontalDivider(
        modifier = Modifier.padding(horizontal = 16.dp),
        color = MaterialTheme.colorScheme.outlineVariant,
    )
}

@Composable
internal fun GroupSpacer(height: androidx.compose.ui.unit.Dp = 24.dp) {
    Spacer(Modifier.height(height))
}

internal fun shortPath(path: String?): String {
    if (path.isNullOrBlank()) return "No workspace selected"
    if (path.length <= 30) return path
    return "…${path.takeLast(28)}"
}
