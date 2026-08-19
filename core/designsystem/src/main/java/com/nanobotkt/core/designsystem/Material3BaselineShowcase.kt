package com.nanobotkt.core.designsystem

import android.content.res.Configuration
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.tooling.preview.Preview

/**
 * 供设计评审使用的 NanobotKT Material 3 增量样板。
 *
 * Material 3 已定义 Button、TextField、Switch 等基础组件，本样板不会重新枚举完整组件目录；
 * 它重点展示 NanobotKT 在 Material 3 之上补充的状态语义和跨 Feature 固定组合。末尾只保留少量
 * Material 基础组件，用于确认定制 Theme 没有破坏系统组件的明暗主题与交互状态。
 *
 * 样板不连接任何业务 ViewModel，因此不会改变导航、会话、网络或 WebSocket 行为。
 */
@Composable
fun Material3BaselineShowcase(darkTheme: Boolean = false) {
    NanobotTheme(darkTheme = darkTheme) {
        Material3BaselineShowcaseContent()
    }
}

@Composable
private fun Material3BaselineShowcaseContent() {
    val spacing = NanobotThemeDefaults.spacing
    val colors = MaterialTheme.colorScheme
    var input by remember { mutableStateOf("Material 3") }
    var switched by remember { mutableStateOf(true) }

    Column(
        modifier = Modifier
            .fillMaxWidth()
            .background(colors.background)
            .verticalScroll(rememberScrollState())
            .padding(vertical = spacing.md),
        verticalArrangement = Arrangement.spacedBy(spacing.md),
    ) {
        Column(
            modifier = Modifier.padding(horizontal = spacing.md),
            verticalArrangement = Arrangement.spacedBy(spacing.xs),
        ) {
            Text("NanobotKT Material 3 Overlay", style = MaterialTheme.typography.headlineSmall)
            Text(
                "Material 3 负责基础组件；这里仅验证 Quiet Technical 主题、业务状态和产品组合。",
                style = MaterialTheme.typography.bodyMedium,
                color = colors.onSurfaceVariant,
            )
        }

        Column {
            NanobotSectionHeader(text = "状态语义")
            Row(
                modifier = Modifier.padding(horizontal = spacing.md),
                horizontalArrangement = Arrangement.spacedBy(spacing.xs),
            ) {
                NanobotStatusLabel("运行中", NanobotStatusTone.Active)
                NanobotStatusLabel("已连接", NanobotStatusTone.Success)
                NanobotStatusLabel("等待配置", NanobotStatusTone.Warning)
            }
            Row(
                modifier = Modifier.padding(horizontal = spacing.md, vertical = spacing.xs),
                horizontalArrangement = Arrangement.spacedBy(spacing.xs),
            ) {
                NanobotStatusLabel("失败", NanobotStatusTone.Error)
                NanobotStatusLabel("已归档", NanobotStatusTone.Neutral)
            }
        }

        Column(modifier = Modifier.padding(horizontal = spacing.md)) {
            NanobotSummarySurface {
                Text("Gateway", style = MaterialTheme.typography.titleMedium)
                Text(
                    "紧凑摘要只承载关键状态和操作，不再制造 Hero Card 或多层 Card 嵌套。",
                    style = MaterialTheme.typography.bodyMedium,
                    color = colors.onSurfaceVariant,
                )
                NanobotStatusLabel("已连接", NanobotStatusTone.Success)
                Row(horizontalArrangement = Arrangement.spacedBy(spacing.xs)) {
                    Button(onClick = {}) { Text("管理") }
                    TextButton(onClick = {}) { Text("重新连接") }
                }
            }
        }

        Column {
            NanobotSectionHeader(text = "平面导航")
            NanobotNavigationRow(
                headline = "Models",
                supportingText = "配置 Provider 与默认模型",
                onClick = {},
            )
            NanobotRowDivider()
            NanobotNavigationRow(
                headline = "当前会话",
                supportingText = "选中态使用轻量 tonal surface",
                selected = true,
                onClick = {},
            )
        }

        // Empty 与 Error 是互斥的页面状态，这里并排展示只是为了让 Preview 能统一校验内容层级。
        Column {
            NanobotSectionHeader(text = "页面状态")
            NanobotEmptyState(
                title = "暂无 Skills",
                description = "安装或启用能力后会显示在这里。",
            )
            NanobotErrorState(
                title = "无法加载能力",
                message = "保留状态层提供的真实错误信息，并给出明确恢复入口。",
                retryLabel = "重试",
                onRetry = {},
            )
        }

        Column {
            NanobotSectionHeader(text = "Material 3 基础组件回归")
            Column(
                modifier = Modifier.padding(horizontal = spacing.md),
                verticalArrangement = Arrangement.spacedBy(spacing.xs),
            ) {
                OutlinedTextField(
                    value = input,
                    onValueChange = { input = it },
                    modifier = Modifier.fillMaxWidth(),
                    label = { Text("Theme input") },
                    singleLine = true,
                )
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.SpaceBetween,
                ) {
                    Text("使用固定品牌主题", style = MaterialTheme.typography.bodyLarge)
                    Switch(checked = switched, onCheckedChange = { switched = it })
                }
            }
        }
    }
}

/** 浅色预览：确认固定品牌 scheme、状态扩展和产品组合的默认层级。 */
@Preview(
    name = "Nanobot Overlay Light",
    showBackground = true,
    widthDp = 390,
    heightDp = 1200,
)
@Composable
private fun Material3LightPreview() {
    Material3BaselineShowcase(darkTheme = false)
}

/** 深色预览：确认 tonal surface 与 Success/Warning 扩展没有沿用浅色硬编码。 */
@Preview(
    name = "Nanobot Overlay Dark",
    showBackground = true,
    uiMode = Configuration.UI_MODE_NIGHT_YES,
    widthDp = 390,
    heightDp = 1200,
)
@Composable
private fun Material3DarkPreview() {
    Material3BaselineShowcase(darkTheme = true)
}
