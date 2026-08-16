package com.nanobotkt.feature.settings

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.defaultMinSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.ArrowCircleUp
import androidx.compose.material.icons.outlined.Edit
import androidx.compose.material.icons.outlined.Search
import androidx.compose.material.icons.outlined.SmartToy
import androidx.compose.material.icons.outlined.Visibility
import androidx.compose.material.icons.outlined.VisibilityOff
import androidx.compose.material.icons.rounded.Add
import androidx.compose.material.icons.rounded.Check
import androidx.compose.material.icons.rounded.Remove
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.ExposedDropdownMenuAnchorType
import androidx.compose.material3.ExposedDropdownMenuBox
import androidx.compose.material3.ExposedDropdownMenuDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedIconButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp

/** 版本检查、模型选择、密钥和数值输入组件。 */
@Composable
internal fun VersionCheckRow(
    version: String,
    updateText: String?,
    checked: Boolean,
    checking: Boolean,
    onCheckVersion: () -> Unit,
) {
    Column(modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 16.dp)) {
        Text(text = "Version", style = MaterialTheme.typography.titleSmall)
        Text(
            text = if (version == "nanobot") version else "v$version",
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            style = MaterialTheme.typography.bodyMedium,
        )
        Spacer(Modifier.size(12.dp))
        OutlinedButton(onClick = onCheckVersion, enabled = !checking) {
            Icon(imageVector = Icons.Outlined.ArrowCircleUp, contentDescription = null)
            Spacer(Modifier.width(8.dp))
            Text(if (checking) "Checking..." else "Check for updates")
        }
        val status = updateText ?: if (checked) "You're up to date" else null
        if (status != null) {
            Spacer(Modifier.size(8.dp))
            Text(
                text = status,
                color =
                    if (updateText != null) {
                        MaterialTheme.colorScheme.primary
                    } else {
                        MaterialTheme.colorScheme.tertiary
                    },
                style = MaterialTheme.typography.bodySmall,
            )
        }
    }
}

/**
 * 可搜索的模型选择器。
 *
 * 外层采用 Material 3 ExposedDropdownMenuBox 保留标准锚点、展开状态和菜单定位；菜单内的
 * 搜索框仅过滤本地模型目录，不会触发网络请求。自定义模型 ID 仍通过明确菜单项提交，避免
 * 输入过程误改已保存配置。
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun ModelIdPicker(
    provider: String,
    providerConfigured: Boolean,
    showProviderLogos: Boolean,
    value: String,
    models: List<String>,
    onSelected: (String) -> Unit,
) {
    var expanded by remember { mutableStateOf(false) }
    var query by rememberSaveable(value, expanded) { mutableStateOf("") }
    val trimmedQuery = query.trim()
    val visibleModels =
        models
            .distinct()
            .filter { trimmedQuery.isBlank() || it.contains(trimmedQuery, ignoreCase = true) }
            .take(80)
    val showCustom =
        trimmedQuery.isNotBlank() && models.none { it == trimmedQuery } && trimmedQuery != value

    ExposedDropdownMenuBox(
        expanded = expanded,
        onExpandedChange = { expanded = !expanded },
        modifier = Modifier.width(280.dp),
    ) {
        OutlinedTextField(
            value = value.ifBlank { "Select image model" },
            onValueChange = {},
            readOnly = true,
            singleLine = true,
            modifier =
                Modifier.menuAnchor(ExposedDropdownMenuAnchorType.PrimaryNotEditable)
                    .fillMaxWidth(),
            textStyle = MaterialTheme.typography.bodyMedium,
            leadingIcon = {
                ProviderMark(
                    provider = provider,
                    showBrandLogos = showProviderLogos,
                    size = ProviderMarkSize.PICKER,
                    fallbackIcon = Icons.Outlined.SmartToy,
                    unconfigured = !providerConfigured,
                )
            },
            trailingIcon = { ExposedDropdownMenuDefaults.TrailingIcon(expanded = expanded) },
        )
        ExposedDropdownMenu(
            expanded = expanded,
            onDismissRequest = { expanded = false },
            modifier = Modifier.heightIn(max = 360.dp),
        ) {
            OutlinedTextField(
                value = query,
                onValueChange = { query = it },
                modifier = Modifier.fillMaxWidth().padding(horizontal = 8.dp, vertical = 4.dp),
                placeholder = { Text("Search or type model ID") },
                leadingIcon = {
                    Icon(imageVector = Icons.Outlined.Search, contentDescription = null)
                },
                singleLine = true,
                textStyle = MaterialTheme.typography.bodyMedium,
            )
            if (models.isEmpty() && trimmedQuery.isBlank()) {
                Text(
                    text = "Type the model ID supported by this provider.",
                    modifier = Modifier.padding(horizontal = 16.dp, vertical = 12.dp),
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    style = MaterialTheme.typography.bodySmall,
                )
            }
            visibleModels.forEach { modelId ->
                DropdownMenuItem(
                    leadingIcon = {
                        ProviderMark(
                            provider = provider,
                            showBrandLogos = showProviderLogos,
                            size = ProviderMarkSize.PICKER,
                            fallbackIcon = Icons.Outlined.SmartToy,
                            unconfigured = !providerConfigured,
                        )
                    },
                    text = {
                        Text(
                            text = modelId,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                        )
                    },
                    trailingIcon = {
                        if (modelId == value) {
                            Icon(
                                imageVector = Icons.Rounded.Check,
                                contentDescription = "Selected",
                                tint = MaterialTheme.colorScheme.primary,
                            )
                        }
                    },
                    onClick = {
                        onSelected(modelId)
                        expanded = false
                    },
                )
            }
            if (showCustom) {
                if (visibleModels.isNotEmpty()) CardDivider()
                DropdownMenuItem(
                    leadingIcon = {
                        Icon(imageVector = Icons.Outlined.Edit, contentDescription = null)
                    },
                    text = {
                        Text(
                            text = "Use “$trimmedQuery”",
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                        )
                    },
                    onClick = {
                        onSelected(trimmedQuery)
                        expanded = false
                    },
                )
            }
        }
    }
}

@Composable
internal fun SecretPillTextField(
    value: String,
    onValueChange: (String) -> Unit,
    placeholder: String,
    visible: Boolean,
    onToggleVisibility: () -> Unit,
) {
    OutlinedTextField(
        value = value,
        onValueChange = onValueChange,
        modifier = Modifier.width(280.dp),
        singleLine = true,
        textStyle = MaterialTheme.typography.bodyMedium,
        placeholder = {
            Text(text = placeholder, maxLines = 1, overflow = TextOverflow.Ellipsis)
        },
        trailingIcon = {
            IconButton(onClick = onToggleVisibility) {
                Icon(
                    imageVector =
                        if (visible) Icons.Outlined.VisibilityOff else Icons.Outlined.Visibility,
                    contentDescription = if (visible) "Hide API key" else "Show API key",
                )
            }
        },
        visualTransformation =
            if (visible) VisualTransformation.None else PasswordVisualTransformation(),
        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Password),
    )
}

@Composable
internal fun StoredSecretField(hint: String, onEdit: () -> Unit) {
    OutlinedTextField(
        value = hint,
        onValueChange = {},
        modifier = Modifier.width(280.dp),
        readOnly = true,
        singleLine = true,
        textStyle = MaterialTheme.typography.bodyMedium,
        trailingIcon = {
            IconButton(onClick = onEdit) {
                Icon(imageVector = Icons.Outlined.Edit, contentDescription = "Edit API key")
            }
        },
    )
}

@Composable
internal fun PillTextField(
    value: String,
    onValueChange: (String) -> Unit,
    placeholder: String = "",
    isSecret: Boolean = false,
    trailingLabel: String? = null,
    onTrailingClick: (() -> Unit)? = null,
) {
    OutlinedTextField(
        value = value,
        onValueChange = onValueChange,
        modifier = Modifier.fillMaxWidth(),
        singleLine = true,
        textStyle = MaterialTheme.typography.bodyMedium,
        placeholder = {
            Text(text = placeholder, maxLines = 1, overflow = TextOverflow.Ellipsis)
        },
        trailingIcon =
            if (trailingLabel != null && onTrailingClick != null) {
                {
                    TextButton(onClick = onTrailingClick) {
                        Text(text = trailingLabel)
                    }
                }
            } else {
                null
            },
        visualTransformation =
            if (isSecret) PasswordVisualTransformation() else VisualTransformation.None,
        keyboardOptions =
            KeyboardOptions(
                keyboardType = if (isSecret) KeyboardType.Password else KeyboardType.Text,
            ),
    )
}

/**
 * 原生图标按钮步进器。
 *
 * OutlinedIconButton 自带 48dp 触控语义和禁用状态；数值仍在 range 内收敛，防止快速连续点击
 * 或调用方传入边界值时越界。
 */
@Composable
internal fun NumberStepper(
    value: Int,
    range: IntRange,
    suffix: String = "",
    onValueChange: (Int) -> Unit,
) {
    val canDecrease = value > range.first
    val canIncrease = value < range.last
    Row(verticalAlignment = Alignment.CenterVertically) {
        OutlinedIconButton(
            onClick = { onValueChange((value - 1).coerceIn(range)) },
            enabled = canDecrease,
        ) {
            Icon(imageVector = Icons.Rounded.Remove, contentDescription = "Decrease")
        }
        Text(
            text = if (suffix.isBlank()) value.toString() else "$value $suffix",
            modifier = Modifier.defaultMinSize(minWidth = 88.dp).padding(horizontal = 12.dp),
            style = MaterialTheme.typography.bodyLarge,
            textAlign = androidx.compose.ui.text.style.TextAlign.Center,
        )
        OutlinedIconButton(
            onClick = { onValueChange((value + 1).coerceIn(range)) },
            enabled = canIncrease,
        ) {
            Icon(imageVector = Icons.Rounded.Add, contentDescription = "Increase")
        }
    }
}

/**
 * 只读状态标签没有对应的可点击 Material chip 语义，因此保留 Material Surface 作为语义容器，
 * 但颜色、形状和文字层级全部来自 MaterialTheme，避免维护第二套成功/中性色板。
 */
@Composable
internal fun StatusPill(text: String, positive: Boolean) {
    Surface(
        shape = MaterialTheme.shapes.small,
        color =
            if (positive) {
                MaterialTheme.colorScheme.tertiaryContainer
            } else {
                MaterialTheme.colorScheme.surfaceContainerHighest
            },
        contentColor =
            if (positive) {
                MaterialTheme.colorScheme.onTertiaryContainer
            } else {
                MaterialTheme.colorScheme.onSurfaceVariant
            },
    ) {
        Text(
            text = text,
            modifier = Modifier.padding(horizontal = 12.dp, vertical = 8.dp),
            style = MaterialTheme.typography.labelMedium,
        )
    }
}
