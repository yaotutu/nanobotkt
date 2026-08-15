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

/** Composer 只编排输入、附件、引用和发送状态；副作用仍由 ViewModel/Repository 承担。 */
@Composable
internal fun HeroComposer(
    state: ComposerUiState,
    active: Boolean,
    slashCommands: List<SlashCommand>,
    skills: List<SkillSummary>,
    cliApps: List<CliAppInfo>,
    mcpPresets: List<McpPresetInfo>,
    onTextChange: (String, Int) -> Unit,
    onSelectSlashCommand: (SlashCommand) -> Unit,
    onSelectSkillMention: (SkillMentionCandidate) -> Unit,
    onSelectCapabilityMention: (CapabilityMentionCandidate) -> Unit,
    onSend: () -> Unit,
    onStop: () -> Unit,
    onRemoveAttachment: (Int) -> Unit,
    onPickImages: () -> Unit,
    onPickFiles: () -> Unit,
    onOpenConversationList: () -> Unit,
) {
    ComposerLayout(
        state = state,
        active = active,
        slashCommands = slashCommands,
        skills = skills,
        cliApps = cliApps,
        mcpPresets = mcpPresets,
        placeholder = stringResource(R.string.composer_placeholder),
        onTextChange = onTextChange,
        onSelectSlashCommand = onSelectSlashCommand,
        onSelectSkillMention = onSelectSkillMention,
        onSelectCapabilityMention = onSelectCapabilityMention,
        onSend = onSend,
        onStop = onStop,
        onRemoveAttachment = onRemoveAttachment,
        onPickImages = onPickImages,
        onPickFiles = onPickFiles,
        onOpenConversationList = onOpenConversationList,
    )
}

@Composable
internal fun ConversationComposer(
    state: ComposerUiState,
    active: Boolean,
    slashCommands: List<SlashCommand>,
    skills: List<SkillSummary>,
    cliApps: List<CliAppInfo>,
    mcpPresets: List<McpPresetInfo>,
    onTextChange: (String, Int) -> Unit,
    onSelectSlashCommand: (SlashCommand) -> Unit,
    onSelectSkillMention: (SkillMentionCandidate) -> Unit,
    onSelectCapabilityMention: (CapabilityMentionCandidate) -> Unit,
    onSend: () -> Unit,
    onStop: () -> Unit,
    onRemoveAttachment: (Int) -> Unit,
    onClearQuote: () -> Unit,
    onPickImages: () -> Unit,
    onPickFiles: () -> Unit,
    onOpenConversationList: () -> Unit,
) {
    ComposerLayout(
        state = state,
        active = active,
        slashCommands = slashCommands,
        skills = skills,
        cliApps = cliApps,
        mcpPresets = mcpPresets,
        placeholder = stringResource(R.string.composer_placeholder),
        onTextChange = onTextChange,
        onSelectSlashCommand = onSelectSlashCommand,
        onSelectSkillMention = onSelectSkillMention,
        onSelectCapabilityMention = onSelectCapabilityMention,
        onSend = onSend,
        onStop = onStop,
        onRemoveAttachment = onRemoveAttachment,
        onClearQuote = onClearQuote,
        onPickImages = onPickImages,
        onPickFiles = onPickFiles,
        onOpenConversationList = onOpenConversationList,
    )
}

/**
 * Chat 输入区的唯一视觉骨架。
 *
 * 这里把“附件/引用等临时上下文”和“可编辑输入框”分成两个层级：上层只承载当前草稿上下文，
 * 下层负责输入与主操作。Queue 已上移到顶部状态栏，Composer 不再保留常驻队列区域。
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun ComposerLayout(
    state: ComposerUiState,
    active: Boolean,
    slashCommands: List<SlashCommand>,
    skills: List<SkillSummary>,
    cliApps: List<CliAppInfo>,
    mcpPresets: List<McpPresetInfo>,
    placeholder: String,
    onTextChange: (String, Int) -> Unit,
    onSelectSlashCommand: (SlashCommand) -> Unit,
    onSelectSkillMention: (SkillMentionCandidate) -> Unit,
    onSelectCapabilityMention: (CapabilityMentionCandidate) -> Unit,
    onSend: () -> Unit,
    onStop: () -> Unit,
    onRemoveAttachment: (Int) -> Unit,
    onClearQuote: () -> Unit = {},
    onPickImages: () -> Unit,
    onPickFiles: () -> Unit,
    onOpenConversationList: () -> Unit,
) {
    val hasDraft =
        state.text.isNotBlank() ||
            state.attachments.isNotEmpty() ||
            !state.quotedContext.isNullOrBlank()
    val stopButton = active && !hasDraft
    val sendEnabled =
        stopButton ||
            (!state.sending && !state.voice.isRecording && !state.voice.isTranscribing && hasDraft)
    val slashSuggestions =
        if (state.slashMenuDismissed) emptyList()
        else visibleSlashCommands(state.text, slashCommands, active)
    val skillSuggestions =
        if (state.slashMenuDismissed) emptyList()
        else
            skillMentionCandidates(
                skillMentionQuery(state.text, state.cursorPosition),
                skills,
                state.recentCommands,
            )
    val capabilitySuggestions =
        if (state.mentionMenuDismissed) emptyList()
        else
            capabilityMentionCandidates(
                capabilityMentionQuery(state.text, state.cursorPosition),
                cliApps,
                mcpPresets,
            )
    // 先在组合阶段解析无障碍文案，避免在 semantics 的非 Composable 接收器中读取资源。
    val openConversationDescription = stringResource(R.string.open_conversation_list)
    val mutedColor = MaterialTheme.colorScheme.onSurfaceVariant
    val inputContainerColor = MaterialTheme.colorScheme.surfaceContainer
    val conversationContainerColor = MaterialTheme.colorScheme.surfaceContainerHigh
    val actionColor =
        if (stopButton || (sendEnabled && hasDraft)) {
            MaterialTheme.colorScheme.primary
        } else {
            MaterialTheme.colorScheme.surfaceVariant
        }
    val actionContentColor =
        if (stopButton || (sendEnabled && hasDraft)) {
            MaterialTheme.colorScheme.onPrimary
        } else {
            MaterialTheme.colorScheme.onSurfaceVariant
        }

    Surface(
        modifier =
            Modifier.fillMaxWidth()
                // Composer 是页面布局的一部分，Insets 只作用于这一整块底栏，消息区不会被覆盖。
                .navigationBarsPadding()
                .imePadding(),
        // 底栏自身不再绘制整块色带，让时间轴的页面背景自然延伸到底部；真正需要边界的
        // 只有会话入口和输入胶囊，避免动态配色下出现一整条厚重的浅紫色区域。
        color = Color.Transparent,
        contentColor = MaterialTheme.colorScheme.onSurface,
    ) {
        Column(
            modifier = Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 6.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            when {
                slashSuggestions.isNotEmpty() ->
                    SlashCommandSuggestions(slashSuggestions, onSelectSlashCommand)
                skillSuggestions.isNotEmpty() ->
                    SkillMentionSuggestions(skillSuggestions, onSelectSkillMention)
                capabilitySuggestions.isNotEmpty() ->
                    CapabilityMentionSuggestions(capabilitySuggestions, onSelectCapabilityMention)
            }

            state.quotedContext?.takeIf(String::isNotBlank)?.let { quote ->
                Surface(
                    modifier = Modifier.fillMaxWidth(),
                    shape = MaterialTheme.shapes.large,
                    color = MaterialTheme.colorScheme.secondaryContainer,
                ) {
                    Row(
                        modifier =
                            Modifier.padding(start = 12.dp, top = 8.dp, bottom = 8.dp, end = 4.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Icon(
                            Icons.Rounded.FormatQuote,
                            contentDescription = null,
                            tint = MaterialTheme.colorScheme.onSecondaryContainer,
                        )
                        Spacer(Modifier.width(8.dp))
                        Text(
                            text = quote,
                            modifier = Modifier.weight(1f),
                            maxLines = 2,
                            overflow = TextOverflow.Ellipsis,
                            color = MaterialTheme.colorScheme.onSecondaryContainer,
                            style = MaterialTheme.typography.bodySmall,
                        )
                        IconButton(onClick = onClearQuote, modifier = Modifier.size(48.dp)) {
                            Icon(
                                Icons.Rounded.Close,
                                contentDescription = stringResource(R.string.remove_quoted_context),
                            )
                        }
                    }
                }
            }

            if (state.attachments.isNotEmpty() || state.encodingCount > 0) {
                ComposerContextStrip(
                    icon = Icons.Rounded.AttachFile,
                    label = stringResource(R.string.attachments),
                ) {
                    state.attachments.take(3).forEachIndexed { index, attachment ->
                        AssistChip(
                            onClick = { onRemoveAttachment(index) },
                            label = {
                                Text(
                                    attachment.name,
                                    maxLines = 1,
                                    overflow = TextOverflow.Ellipsis,
                                )
                            },
                            trailingIcon = {
                                Icon(
                                    Icons.Rounded.Close,
                                    contentDescription = stringResource(R.string.remove_attachment),
                                    modifier = Modifier.size(16.dp),
                                )
                            },
                        )
                    }
                    if (state.encodingCount > 0) {
                        CircularProgressIndicator(
                            modifier = Modifier.size(20.dp),
                            strokeWidth = 2.dp,
                        )
                    }
                }
            }

            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.Bottom,
                horizontalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                // 高频会话入口继续独立于输入框，但用“会话组图标 + 短标签”明确表达用途。
                // 胶囊维持 48dp 高度以满足触控要求，同时避免单气泡图标被误解为发送消息。
                Surface(
                    onClick = onOpenConversationList,
                    modifier =
                        Modifier.height(48.dp).semantics {
                            contentDescription = openConversationDescription
                        },
                    shape = CircleShape,
                    color = conversationContainerColor,
                ) {
                    Row(
                        modifier = Modifier.padding(horizontal = 12.dp),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(6.dp),
                    ) {
                        Icon(
                            Icons.Rounded.Forum,
                            contentDescription = null,
                            modifier = Modifier.size(19.dp),
                            tint = mutedColor,
                        )
                        Text(
                            text = stringResource(R.string.conversation_button_label),
                            style = MaterialTheme.typography.labelLarge,
                            color = MaterialTheme.colorScheme.onSurface,
                            fontWeight = FontWeight.SemiBold,
                        )
                    }
                }

                // 附件、文本和发送始终属于同一输入胶囊。细描边负责从页面背景中分离输入区，
                // 不再依赖额外的底栏色带；内部不加纵向 padding，静止态总高保持为 48dp。
                Surface(
                    modifier = Modifier.weight(1f),
                    shape = MaterialTheme.shapes.extraLarge,
                    color = inputContainerColor,
                    border =
                        BorderStroke(
                            1.dp,
                            MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.55f),
                        ),
                ) {
                    Row(
                        modifier = Modifier.padding(horizontal = 2.dp),
                        verticalAlignment = Alignment.Bottom,
                    ) {
                        AttachmentMenuButton(
                            enabled =
                                !state.sending &&
                                    !state.voice.isRecording &&
                                    !state.voice.isTranscribing,
                            onPickImages = onPickImages,
                            onPickFiles = onPickFiles,
                        )
                        ComposerTextField(
                            state = state,
                            modifier = Modifier.weight(1f),
                            placeholder =
                                if (active && !hasDraft) {
                                    stringResource(R.string.composer_placeholder_streaming)
                                } else {
                                    placeholder
                                },
                            textColor = MaterialTheme.colorScheme.onSurface,
                            mutedColor = mutedColor,
                            onTextChange = onTextChange,
                            onSend = onSend,
                        )
                        ComposerPrimaryActionButton(
                            showSendAction = hasDraft,
                            stopButton = stopButton,
                            sendEnabled = sendEnabled,
                            sending = state.sending,
                            // 空草稿时只保留弱化的发送图标，避免右侧出现第二个常驻实心圆。
                            controlColor = Color.Transparent,
                            sendColor = actionColor,
                            sendContentColor = actionContentColor,
                            onSend = onSend,
                            onStop = onStop,
                        )
                    }
                }
            }
        }
    }
}

@Composable
internal fun ComposerContextStrip(
    icon: androidx.compose.ui.graphics.vector.ImageVector,
    label: String,
    content: @Composable RowScope.() -> Unit,
) {
    Surface(
        modifier = Modifier.fillMaxWidth(),
        shape = MaterialTheme.shapes.large,
        color = MaterialTheme.colorScheme.surfaceContainerHigh,
    ) {
        Row(
            modifier =
                Modifier.fillMaxWidth()
                    .horizontalScroll(rememberScrollState())
                    .padding(horizontal = 12.dp, vertical = 4.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(6.dp),
        ) {
            Icon(
                icon,
                contentDescription = null,
                modifier = Modifier.size(18.dp),
                tint = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Text(
                label,
                style = MaterialTheme.typography.labelMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            content()
        }
    }
}

@Composable
internal fun Composer(
    state: ComposerUiState,
    active: Boolean,
    slashCommands: List<SlashCommand>,
    skills: List<SkillSummary>,
    cliApps: List<CliAppInfo>,
    mcpPresets: List<McpPresetInfo>,
    isHero: Boolean,
    onTextChange: (String, Int) -> Unit,
    onSelectSlashCommand: (SlashCommand) -> Unit,
    onSelectSkillMention: (SkillMentionCandidate) -> Unit,
    onSelectCapabilityMention: (CapabilityMentionCandidate) -> Unit,
    onSend: () -> Unit,
    onStop: () -> Unit,
    onRemoveAttachment: (Int) -> Unit,
    onClearQuote: () -> Unit,
    onPickImages: () -> Unit,
    onPickFiles: () -> Unit,
    onOpenConversationList: () -> Unit,
) {
    if (isHero) {
        HeroComposer(
            state = state,
            active = active,
            slashCommands = slashCommands,
            skills = skills,
            cliApps = cliApps,
            mcpPresets = mcpPresets,
            onTextChange = onTextChange,
            onSelectSlashCommand = onSelectSlashCommand,
            onSelectSkillMention = onSelectSkillMention,
            onSelectCapabilityMention = onSelectCapabilityMention,
            onSend = onSend,
            onStop = onStop,
            onRemoveAttachment = onRemoveAttachment,
            onPickImages = onPickImages,
            onPickFiles = onPickFiles,
            onOpenConversationList = onOpenConversationList,
        )
    } else {
        ConversationComposer(
            state = state,
            active = active,
            slashCommands = slashCommands,
            skills = skills,
            cliApps = cliApps,
            mcpPresets = mcpPresets,
            onTextChange = onTextChange,
            onSelectSlashCommand = onSelectSlashCommand,
            onSelectSkillMention = onSelectSkillMention,
            onSelectCapabilityMention = onSelectCapabilityMention,
            onSend = onSend,
            onStop = onStop,
            onRemoveAttachment = onRemoveAttachment,
            onClearQuote = onClearQuote,
            onPickImages = onPickImages,
            onPickFiles = onPickFiles,
            onOpenConversationList = onOpenConversationList,
        )
    }
}

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
