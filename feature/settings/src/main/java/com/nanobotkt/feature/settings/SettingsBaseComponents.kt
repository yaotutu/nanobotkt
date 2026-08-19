package com.nanobotkt.feature.settings

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.KeyboardArrowRight
import androidx.compose.material.icons.outlined.Dns
import androidx.compose.material.icons.rounded.Check
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.ExposedDropdownMenuAnchorType
import androidx.compose.material3.ExposedDropdownMenuBox
import androidx.compose.material3.ExposedDropdownMenuDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.nanobotkt.core.designsystem.NanobotNavigationRow
import com.nanobotkt.core.designsystem.NanobotSectionHeader

/** Settings 页面分组、行、说明块与基础表单布局。 */
/** Settings 页面共享表单组件，避免各能力页重复视觉和输入规则。 */
@Composable
internal fun OpenSectionPage(
    title: String,
    description: String,
    icon: ImageVector,
    onOpen: () -> Unit,
) {
    SettingsGroup(title) {
        EmptySettingsRow(
            icon = icon,
            title = "Open $title",
            subtitle = description,
            action = "Open",
            onClick = onOpen,
        )
    }
}

@Composable
internal fun SettingsGroup(title: String, content: @Composable ColumnScope.() -> Unit) {
    // Settings 分组只通过标题、留白和行分隔线建立层级；不再为每组内容额外创建大圆角 Card。
    // 复杂表单仍可在自身组件中使用 Surface，但不能让整页退化成重复的卡片堆叠。
    NanobotSectionHeader(text = title)
    Column(modifier = Modifier.fillMaxWidth(), content = content)
}

@Composable
internal fun SettingsRow(
    icon: ImageVector,
    title: String,
    subtitle: String? = null,
    value: String? = null,
    selected: Boolean = false,
    showChevron: Boolean = true,
    leadingProvider: String? = null,
    valueLogoProvider: String? = null,
    showBrandLogos: Boolean = false,
    onClick: (() -> Unit)? = null,
    /** 可选的尾部操作，避免为了增加编辑/排序按钮而改变整行点击语义。 */
    trailingContent: (@Composable () -> Unit)? = null,
) {
    NanobotNavigationRow(
        headline = title,
        supportingText = subtitle,
        selected = selected,
        onClick = onClick,
        leadingContent = {
            if (!leadingProvider.isNullOrBlank()) {
                ProviderMark(
                    provider = leadingProvider,
                    showBrandLogos = showBrandLogos,
                    size = ProviderMarkSize.LIST,
                    fallbackIcon = icon,
                )
            } else {
                // 普通 Settings 图标保持裸图标，避免每一行都出现同权重的彩色方块。
                Icon(
                    imageVector = icon,
                    contentDescription = null,
                    modifier = Modifier.size(24.dp),
                    tint = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        },
        trailingContent = {
            Row(verticalAlignment = Alignment.CenterVertically) {
                if (
                    !valueLogoProvider.isNullOrBlank() &&
                        showBrandLogos &&
                        providerBrand(valueLogoProvider) != null
                ) {
                    ProviderMark(
                        provider = valueLogoProvider,
                        showBrandLogos = true,
                        size = ProviderMarkSize.PICKER,
                        fallbackIcon = icon,
                        hideWhenUnavailable = true,
                    )
                    Spacer(Modifier.width(8.dp))
                }
                if (!value.isNullOrBlank()) {
                    Text(
                        text = value,
                        style = MaterialTheme.typography.labelLarge,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                }
                trailingContent?.let {
                    Spacer(Modifier.width(4.dp))
                    it()
                }
                if (showChevron) {
                    Spacer(Modifier.width(4.dp))
                    Icon(
                        imageVector = Icons.AutoMirrored.Rounded.KeyboardArrowRight,
                        contentDescription = null,
                        tint = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
        },
    )
}

@Composable
internal fun EmptySettingsRow(
    icon: ImageVector,
    title: String,
    subtitle: String,
    action: String? = null,
    onClick: (() -> Unit)? = null,
) {
    SettingsRow(
        icon = icon,
        title = title,
        subtitle = subtitle,
        value = action,
        showChevron = onClick != null,
        onClick = onClick,
    )
}

@Composable
internal fun PreferenceBlock(
    title: String,
    description: String,
    content: (@Composable () -> Unit)? = null,
) {
    Column(modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 15.dp)) {
        Text(title, style = MaterialTheme.typography.titleMedium)
        Spacer(Modifier.height(4.dp))
        Text(
            description,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            style = MaterialTheme.typography.bodyMedium,
        )
        if (content != null) {
            Spacer(Modifier.height(14.dp))
            content()
        }
    }
}

@Composable
internal fun FormSettingRow(
    title: String,
    description: String? = null,
    content: @Composable ColumnScope.() -> Unit,
) {
    Column(modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 14.dp)) {
        Text(
            text = title,
            style = MaterialTheme.typography.titleSmall,
        )
        if (!description.isNullOrBlank()) {
            Spacer(Modifier.height(3.dp))
            Text(
                text = description,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                style = MaterialTheme.typography.bodySmall,
            )
        }
        Spacer(Modifier.height(11.dp))
        content()
    }
}

@Composable
internal fun ReadOnlyFormRow(title: String, value: String) {
    FormSettingRow(title) {
        Text(
            text = value,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            style = MaterialTheme.typography.bodyMedium,
            maxLines = 2,
            overflow = TextOverflow.Ellipsis,
        )
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun PillPicker(
    value: String,
    options: List<Pair<String, String>>,
    showProviderLogos: Boolean = false,
    onSelected: (String) -> Unit,
) {
    var expanded by remember { mutableStateOf(false) }
    val currentLabel =
        options.firstOrNull { it.first == value }?.second ?: value.ifBlank { "Select" }
    ExposedDropdownMenuBox(
        expanded = expanded,
        onExpandedChange = { if (options.isNotEmpty()) expanded = !expanded },
        modifier = Modifier.width(240.dp),
    ) {
        OutlinedTextField(
            value = currentLabel,
            onValueChange = {},
            readOnly = true,
            enabled = options.isNotEmpty(),
            singleLine = true,
            modifier =
                Modifier.menuAnchor(ExposedDropdownMenuAnchorType.PrimaryNotEditable)
                    .fillMaxWidth(),
            textStyle = MaterialTheme.typography.bodyMedium,
            leadingIcon =
                if (showProviderLogos && value.isNotBlank()) {
                    {
                        ProviderMark(
                            provider = value,
                            showBrandLogos = true,
                            size = ProviderMarkSize.PICKER,
                            fallbackIcon = Icons.Outlined.Dns,
                        )
                    }
                } else {
                    null
                },
            trailingIcon = { ExposedDropdownMenuDefaults.TrailingIcon(expanded = expanded) },
        )
        ExposedDropdownMenu(
            expanded = expanded,
            onDismissRequest = { expanded = false },
            modifier = Modifier.heightIn(max = 320.dp),
        ) {
            options.forEach { (optionValue, label) ->
                DropdownMenuItem(
                    leadingIcon =
                        if (showProviderLogos) {
                            {
                                ProviderMark(
                                    provider = optionValue,
                                    showBrandLogos = true,
                                    size = ProviderMarkSize.PICKER,
                                    fallbackIcon = Icons.Outlined.Dns,
                                )
                            }
                        } else {
                            null
                        },
                    text = { Text(label, style = MaterialTheme.typography.bodyMedium) },
                    trailingIcon = {
                        if (optionValue == value) {
                            Icon(
                                Icons.Rounded.Check,
                                null,
                                tint = MaterialTheme.colorScheme.primary,
                            )
                        }
                    },
                    onClick = {
                        expanded = false
                        onSelected(optionValue)
                    },
                )
            }
        }
    }
}
