package com.nanobotkt.core.designsystem

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.defaultMinSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.ListItem
import androidx.compose.material3.ListItemDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.Immutable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp

/**
 * Nanobot 跨 Feature 的业务状态语义。
 *
 * 该枚举描述产品状态而不是组件外观：Active/Error 复用 Material 角色，Success/Warning 使用主题扩展，
 * Neutral 只表达普通元数据。Feature 应先判断业务语义，再选择 tone，禁止按“哪个颜色好看”传值。
 */
enum class NanobotStatusTone {
    Neutral,
    Active,
    Success,
    Warning,
    Error,
}

@Immutable
private data class ResolvedStatusColors(
    val foreground: Color,
    val container: Color,
)

/** 统一产品 Section 标题；不重复定义 Material 标题层级，只固定 Nanobot 的留白和低强调颜色。 */
@Composable
fun NanobotSectionHeader(
    text: String,
    modifier: Modifier = Modifier,
) {
    Text(
        text = text,
        modifier = modifier.padding(horizontal = 16.dp, vertical = 8.dp),
        style = MaterialTheme.typography.labelLarge,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
    )
}

/**
 * Nanobot 的平面导航行组合。
 *
 * ListItem 的排版、状态层和触控语义仍由 Material 3 提供；本组合仅统一普通行透明、选中行 tonal、
 * 单行标题和最多两行摘要的产品规则。表单行、开关行和复杂业务行不应强行套用本组件。
 */
@Composable
fun NanobotNavigationRow(
    headline: String,
    modifier: Modifier = Modifier,
    supportingText: String? = null,
    selected: Boolean = false,
    enabled: Boolean = true,
    onClick: (() -> Unit)? = null,
    leadingContent: (@Composable () -> Unit)? = null,
    trailingContent: (@Composable () -> Unit)? = null,
) {
    val interactiveModifier = modifier
        .fillMaxWidth()
        .defaultMinSize(minHeight = 56.dp)
        .then(
            if (onClick != null) {
                Modifier.clickable(enabled = enabled, onClick = onClick)
            } else {
                Modifier
            },
        )

    ListItem(
        modifier = interactiveModifier,
        colors = ListItemDefaults.colors(
            containerColor = if (selected) {
                MaterialTheme.colorScheme.secondaryContainer
            } else {
                Color.Transparent
            },
            headlineColor = if (enabled) {
                MaterialTheme.colorScheme.onSurface
            } else {
                MaterialTheme.colorScheme.onSurface.copy(alpha = 0.38f)
            },
            supportingColor = if (enabled) {
                MaterialTheme.colorScheme.onSurfaceVariant
            } else {
                MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.38f)
            },
        ),
        headlineContent = {
            Text(
                text = headline,
                style = MaterialTheme.typography.bodyLarge,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
        },
        supportingContent = supportingText?.takeIf(String::isNotBlank)?.let { text ->
            {
                Text(
                    text = text,
                    style = MaterialTheme.typography.bodyMedium,
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis,
                )
            }
        },
        leadingContent = leadingContent,
        trailingContent = trailingContent,
    )
}

/**
 * 小型只读状态标签。它不是 FilterChip：没有点击和筛选语义，只用“状态点 + 文字”表达当前事实，
 * 因而不会让能力列表充满可交互但实际不可点击的 Chip。
 */
@Composable
fun NanobotStatusLabel(
    label: String,
    tone: NanobotStatusTone,
    modifier: Modifier = Modifier,
) {
    val colors = resolveStatusColors(tone)
    Surface(
        modifier = modifier,
        shape = CircleShape,
        color = colors.container,
        contentColor = colors.foreground,
    ) {
        Row(
            modifier = Modifier.padding(horizontal = 9.dp, vertical = 5.dp),
            horizontalArrangement = Arrangement.spacedBy(6.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Box(modifier = Modifier.size(6.dp)) {
                Surface(
                    modifier = Modifier.matchParentSize(),
                    shape = CircleShape,
                    color = colors.foreground,
                ) {}
            }
            Text(text = label, style = MaterialTheme.typography.labelMedium)
        }
    }
}

/**
 * 首页或详情页顶部的紧凑摘要容器。它只提供一层低强调 tonal surface，不承担业务结构，
 * 从而避免 Feature 再出现“高亮卡片中嵌套卡片”的层级竞争。
 */
@Composable
fun NanobotSummarySurface(
    modifier: Modifier = Modifier,
    content: @Composable ColumnScope.() -> Unit,
) {
    Surface(
        modifier = modifier.fillMaxWidth(),
        shape = MaterialTheme.shapes.large,
        color = MaterialTheme.colorScheme.surfaceContainerLow,
        contentColor = MaterialTheme.colorScheme.onSurface,
    ) {
        Column(
            modifier = Modifier.padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
            content = content,
        )
    }
}

/** 统一空状态的内容层级；插图和业务操作仍由具体 Feature 决定，避免形成万能空页面。 */
@Composable
fun NanobotEmptyState(
    title: String,
    modifier: Modifier = Modifier,
    description: String? = null,
) {
    Column(
        modifier = modifier
            .fillMaxWidth()
            .padding(horizontal = 24.dp, vertical = 32.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        Text(text = title, style = MaterialTheme.typography.titleMedium)
        description?.takeIf(String::isNotBlank)?.let {
            Text(
                text = it,
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}

/**
 * 统一可恢复错误状态。错误信息与恢复动作被放在同一区域，但不伪造错误原因；Feature 仍需传入
 * 后端或本地状态层提供的真实信息。没有恢复能力时省略 onRetry，而不是显示无效按钮。
 */
@Composable
fun NanobotErrorState(
    title: String,
    modifier: Modifier = Modifier,
    message: String? = null,
    retryLabel: String = "重试",
    onRetry: (() -> Unit)? = null,
) {
    Column(
        modifier = modifier
            .fillMaxWidth()
            .padding(horizontal = 24.dp, vertical = 24.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        Text(
            text = title,
            style = MaterialTheme.typography.titleMedium,
            color = MaterialTheme.colorScheme.error,
        )
        message?.takeIf(String::isNotBlank)?.let {
            Text(
                text = it,
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        onRetry?.let { retry ->
            TextButton(onClick = retry) { Text(retryLabel) }
        }
    }
}

/** 平面列表的统一分隔线，缩进与 Navigation Row 的正文起点保持一致。 */
@Composable
fun NanobotRowDivider(modifier: Modifier = Modifier) {
    HorizontalDivider(
        modifier = modifier.padding(start = 56.dp),
        color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.6f),
    )
}

@Composable
private fun resolveStatusColors(tone: NanobotStatusTone): ResolvedStatusColors {
    val scheme = MaterialTheme.colorScheme
    val status = NanobotThemeDefaults.statusColors
    return when (tone) {
        NanobotStatusTone.Neutral -> ResolvedStatusColors(
            foreground = scheme.onSurfaceVariant,
            container = scheme.surfaceContainerHigh,
        )
        NanobotStatusTone.Active -> ResolvedStatusColors(
            foreground = scheme.onPrimaryContainer,
            container = scheme.primaryContainer,
        )
        NanobotStatusTone.Success -> ResolvedStatusColors(
            foreground = status.onSuccessContainer,
            container = status.successContainer,
        )
        NanobotStatusTone.Warning -> ResolvedStatusColors(
            foreground = status.onWarningContainer,
            container = status.warningContainer,
        )
        NanobotStatusTone.Error -> ResolvedStatusColors(
            foreground = scheme.onErrorContainer,
            container = scheme.errorContainer,
        )
    }
}
