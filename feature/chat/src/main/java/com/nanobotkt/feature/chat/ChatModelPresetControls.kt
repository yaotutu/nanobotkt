package com.nanobotkt.feature.chat

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.AttachFile
import androidx.compose.material.icons.rounded.Forum
import androidx.compose.material.icons.rounded.Check
import androidx.compose.material.icons.rounded.Close
import androidx.compose.material.icons.rounded.ExpandMore
import androidx.compose.material.icons.rounded.FormatQuote
import androidx.compose.material.icons.rounded.Settings
import androidx.compose.material.icons.rounded.SmartToy
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.AssistChip
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.nanobotkt.core.model.CliAppInfo
import com.nanobotkt.core.model.McpPresetInfo
import com.nanobotkt.core.model.SkillSummary
import com.nanobotkt.core.model.SlashCommand

/** Composer 使用的模型预设入口与选择弹窗。 */
@Composable
internal fun ModelPresetControl(
    model: ChatModelSelection,
    disabled: Boolean,
    onChange: (String) -> Unit,
    onOpenSettings: () -> Unit,
    modifier: Modifier = Modifier,
    compact: Boolean = false,
    heroStyle: Boolean = false,
    heroScale: Float = 1f,
) {
    val options =
        remember(model.presets) {
            model.presets.filter { preset -> !preset.isDefault && preset.name.isNotBlank() }
        }
    var open by remember { mutableStateOf(false) }

    Row(modifier, horizontalArrangement = Arrangement.End) {
        if (compact) {
            val controlColor =
                if (heroStyle) {
                    MaterialTheme.colorScheme.surfaceContainerHigh
                } else {
                    MaterialTheme.colorScheme.surface
                }
            val labelColor = MaterialTheme.colorScheme.onSurface
            Surface(
                onClick = { if (options.isEmpty()) onOpenSettings() else open = true },
                enabled = !disabled,
                shape = CircleShape,
                color = controlColor,
                tonalElevation = if (heroStyle) 2.dp else 1.dp,
                shadowElevation = 0.dp,
                modifier =
                    Modifier.then(if (heroStyle) Modifier.fillMaxSize() else Modifier).semantics {
                        contentDescription = model.displayLabel
                    },
            ) {
                Row(
                    modifier =
                        if (heroStyle) {
                            Modifier.fillMaxSize().padding(horizontal = (8f * heroScale).dp)
                        } else {
                            Modifier.widthIn(max = 80.dp)
                                .padding(horizontal = 7.dp, vertical = 7.dp)
                        },
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement =
                        Arrangement.spacedBy(if (heroStyle) (5f * heroScale).dp else 4.dp),
                ) {
                    if (heroStyle) {
                        Box(
                            modifier =
                                Modifier.size((18f * heroScale).dp)
                                    .background(
                                        MaterialTheme.colorScheme.tertiaryContainer,
                                        CircleShape,
                                    ),
                            contentAlignment = Alignment.Center,
                        ) {
                            Icon(
                                Icons.Rounded.SmartToy,
                                contentDescription = null,
                                modifier = Modifier.size((11f * heroScale).dp),
                                tint = MaterialTheme.colorScheme.onTertiaryContainer,
                            )
                        }
                    } else {
                        Icon(
                            Icons.Rounded.SmartToy,
                            contentDescription = null,
                            modifier = Modifier.size(14.dp),
                            tint = MaterialTheme.colorScheme.tertiary,
                        )
                    }
                    Text(
                        model.displayLabel,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                        style =
                            if (heroStyle) {
                                MaterialTheme.typography.labelMedium.copy(
                                    fontWeight = FontWeight.SemiBold
                                )
                            } else {
                                MaterialTheme.typography.labelMedium
                            },
                        color = if (heroStyle) labelColor else MaterialTheme.colorScheme.onSurface,
                    )
                }
            }
        } else {
            AssistChip(
                onClick = { if (options.isEmpty()) onOpenSettings() else open = true },
                enabled = !disabled,
                label = {
                    Text(model.displayLabel, maxLines = 1, overflow = TextOverflow.Ellipsis)
                },
                trailingIcon = {
                    Icon(Icons.Rounded.ExpandMore, contentDescription = null, Modifier.size(18.dp))
                },
                modifier =
                    Modifier.widthIn(max = 220.dp).semantics {
                        contentDescription = model.displayLabel
                    },
            )
        }
    }

    if (open) {
        ModelPresetDialog(
            model = model,
            disabled = disabled,
            onChange = onChange,
            onOpenSettings = onOpenSettings,
            onDismiss = { open = false },
        )
    }
}

@Composable
internal fun ModelPresetDialog(
    model: ChatModelSelection,
    disabled: Boolean,
    onChange: (String) -> Unit,
    onOpenSettings: () -> Unit,
    onDismiss: () -> Unit,
) {
    val options =
        remember(model.presets) {
            model.presets.filter { preset -> !preset.isDefault && preset.name.isNotBlank() }
        }
    var requestedPreset by remember { mutableStateOf<String?>(null) }

    // 只有服务端确认 activePreset 已切换后才关闭 Dialog；这样网络请求失败时用户仍能看到
    // 错误状态，而不会误以为模型已经切换成功。
    LaunchedEffect(requestedPreset, model.pendingPreset, model.error, model.activePreset) {
        val requested = requestedPreset ?: return@LaunchedEffect
        if (model.pendingPreset == null && model.error == null && model.activePreset == requested) {
            requestedPreset = null
            onDismiss()
        }
    }

    AlertDialog(
        onDismissRequest = { if (model.pendingPreset == null) onDismiss() },
        title = { Text(stringResource(R.string.model_select_title)) },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                Text(
                    stringResource(R.string.model_select_help),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                LazyColumn(
                    modifier = Modifier.fillMaxWidth().heightIn(max = 420.dp),
                    verticalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    items(options, key = { it.name }) { preset ->
                        val selected = preset.name == model.activePreset
                        val loading = preset.name == model.pendingPreset
                        val provider = preset.resolvedProvider ?: preset.provider
                        Surface(
                            onClick = {
                                if (model.pendingPreset == null) {
                                    if (selected) {
                                        onDismiss()
                                    } else {
                                        requestedPreset = preset.name
                                        onChange(preset.name)
                                    }
                                }
                            },
                            enabled = model.pendingPreset == null && !disabled,
                            modifier =
                                Modifier.fillMaxWidth().semantics {
                                    contentDescription = preset.label.ifBlank { preset.name }
                                },
                            color =
                                if (selected) {
                                    MaterialTheme.colorScheme.secondaryContainer
                                } else {
                                    MaterialTheme.colorScheme.surface
                                },
                            shape = MaterialTheme.shapes.medium,
                        ) {
                            Row(
                                modifier = Modifier.fillMaxWidth().padding(12.dp),
                                verticalAlignment = Alignment.CenterVertically,
                                horizontalArrangement = Arrangement.spacedBy(12.dp),
                            ) {
                                Column(Modifier.weight(1f)) {
                                    Text(
                                        preset.label.ifBlank { preset.name },
                                        style = MaterialTheme.typography.labelLarge,
                                    )
                                    Text(
                                        listOf(provider, preset.model)
                                            .filter(String::isNotBlank)
                                            .joinToString(" · "),
                                        maxLines = 1,
                                        overflow = TextOverflow.Ellipsis,
                                        style = MaterialTheme.typography.bodySmall,
                                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                                    )
                                }
                                when {
                                    loading ->
                                        CircularProgressIndicator(
                                            Modifier.size(20.dp),
                                            strokeWidth = 2.dp,
                                        )
                                    selected -> Icon(Icons.Rounded.Check, contentDescription = null)
                                }
                            }
                        }
                    }
                    item {
                        TextButton(
                            onClick = {
                                onDismiss()
                                onOpenSettings()
                            },
                            enabled = model.pendingPreset == null && !disabled,
                            modifier = Modifier.fillMaxWidth(),
                        ) {
                            Icon(
                                Icons.Rounded.Settings,
                                contentDescription = null,
                                Modifier.size(18.dp),
                            )
                            Text(
                                stringResource(R.string.model_settings),
                                Modifier.padding(start = 8.dp),
                            )
                        }
                    }
                }
            }
        },
        confirmButton = {},
        dismissButton = {
            TextButton(onClick = onDismiss, enabled = model.pendingPreset == null) {
                Text(stringResource(R.string.cancel))
            }
        },
    )
}
