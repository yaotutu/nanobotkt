package com.nanobotkt.core.designsystem

import android.content.res.Configuration
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AssistChip
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.Checkbox
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TextField
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp

/**
 * 供设计评审使用的 Material 3 基础视觉样板。
 *
 * 该样板只展示颜色、字体、形状、间距、组件状态和明暗主题，不连接任何业务 ViewModel，
 * 因此不会改变导航、会话、网络或 WebSocket 行为。确认基线时可直接在 Android Studio
 * 的 Compose Preview 中并排查看 Light/Dark 两个预览，后续页面改造再复用同一套 token。
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
    var text by remember { mutableStateOf("") }
    var outlinedText by remember { mutableStateOf("Material 3") }
    var checked by remember { mutableStateOf(true) }
    var switched by remember { mutableStateOf(false) }

    Column(
        modifier = Modifier
            .fillMaxWidth()
            .background(colors.background)
            .verticalScroll(rememberScrollState())
            .padding(spacing.md),
        verticalArrangement = Arrangement.spacedBy(spacing.md),
    ) {
        Column(verticalArrangement = Arrangement.spacedBy(spacing.xs)) {
            Text("Material 3 基础视觉样板", style = MaterialTheme.typography.headlineSmall)
            Text(
                "固定色彩角色、完整 type scale、统一形状与 4/8dp 间距基线。",
                style = MaterialTheme.typography.bodyMedium,
                color = colors.onSurfaceVariant,
            )
        }

        Card(
            modifier = Modifier.fillMaxWidth(),
            shape = MaterialTheme.shapes.large,
            colors = CardDefaults.cardColors(containerColor = colors.surfaceVariant),
        ) {
            Column(
                modifier = Modifier.padding(spacing.md),
                verticalArrangement = Arrangement.spacedBy(spacing.xs),
            ) {
                Text("颜色层级", style = MaterialTheme.typography.titleMedium)
                Text(
                    "Surface / Surface Variant / Primary Container / Error Container",
                    style = MaterialTheme.typography.bodyMedium,
                    color = colors.onSurfaceVariant,
                )
                LinearProgressIndicator(modifier = Modifier.fillMaxWidth())
                AssistChip(
                    onClick = {},
                    label = { Text("辅助状态") },
                )
            }
        }

        Text("组件状态", style = MaterialTheme.typography.titleLarge)
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(spacing.xs),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Button(onClick = {}) { Text("主要") }
            OutlinedButton(onClick = {}) { Text("次要") }
            TextButton(onClick = {}) { Text("文字") }
        }

        Column(verticalArrangement = Arrangement.spacedBy(spacing.xs)) {
            TextField(
                value = text,
                onValueChange = { text = it },
                modifier = Modifier.fillMaxWidth(),
                label = { Text("Filled input") },
                placeholder = { Text("输入内容") },
                singleLine = true,
            )
            OutlinedTextField(
                value = outlinedText,
                onValueChange = { outlinedText = it },
                modifier = Modifier.fillMaxWidth(),
                label = { Text("Outlined input") },
                singleLine = true,
            )
        }

        HorizontalDivider()

        Column(verticalArrangement = Arrangement.spacedBy(spacing.xs)) {
            Text("交互状态", style = MaterialTheme.typography.titleMedium)
            Row(verticalAlignment = Alignment.CenterVertically) {
                Checkbox(checked = checked, onCheckedChange = { checked = it })
                Text("已选中", style = MaterialTheme.typography.bodyLarge)
            }
            Row(verticalAlignment = Alignment.CenterVertically) {
                RadioButton(selected = !checked, onClick = { checked = false })
                Text("未选中", style = MaterialTheme.typography.bodyLarge)
            }
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.SpaceBetween,
            ) {
                Text("开关状态", style = MaterialTheme.typography.bodyLarge)
                Switch(checked = switched, onCheckedChange = { switched = it })
            }
            Spacer(Modifier.height(spacing.xxs))
            Text(
                "错误状态示例：这是一条使用 errorContainer / onErrorContainer 的提示。",
                modifier = Modifier
                    .fillMaxWidth()
                    .background(colors.errorContainer, MaterialTheme.shapes.medium)
                    .padding(spacing.sm),
                color = colors.onErrorContainer,
                style = MaterialTheme.typography.bodyMedium,
            )
        }
    }
}

/** 浅色预览：用于确认固定 scheme 的背景、层级和默认组件状态。 */
@Preview(
    name = "Material 3 Light",
    showBackground = true,
    widthDp = 390,
    heightDp = 844,
)
@Composable
private fun Material3LightPreview() {
    Material3BaselineShowcase(darkTheme = false)
}

/** 深色预览：用于确认深色 tonal role 与文字对比度没有沿用浅色硬编码。 */
@Preview(
    name = "Material 3 Dark",
    showBackground = true,
    uiMode = Configuration.UI_MODE_NIGHT_YES,
    widthDp = 390,
    heightDp = 844,
)
@Composable
private fun Material3DarkPreview() {
    Material3BaselineShowcase(darkTheme = true)
}
