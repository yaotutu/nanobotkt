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
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.nanobotkt.core.model.CliAppInfo
import com.nanobotkt.core.model.McpPresetInfo
import com.nanobotkt.core.model.SkillSummary
import com.nanobotkt.core.model.SlashCommand

/** Composer 只编排输入、附件、引用和发送状态；网络、文件与会话副作用仍由 ViewModel/Repository 承担。 */
@Composable
internal fun HeroComposer(
    state: ComposerUiState,
    active: Boolean,
    stopping: Boolean = false,
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
) {
    ComposerLayout(
        state = state,
        active = active,
        stopping = stopping,
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
    )
}

@Composable
internal fun ConversationComposer(
    state: ComposerUiState,
    active: Boolean,
    stopping: Boolean = false,
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
) {
    ComposerLayout(
        state = state,
        active = active,
        stopping = stopping,
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
    stopping: Boolean = false,
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
    val mutedColor = MaterialTheme.colorScheme.onSurfaceVariant
    val inputContainerColor = MaterialTheme.colorScheme.surfaceContainerLow
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
        // 只有输入容器，避免动态配色下出现一整条厚重色带。
        color = Color.Transparent,
        contentColor = MaterialTheme.colorScheme.onSurface,
    ) {
        Column(
            modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 8.dp),
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

            // 会话列表属于页面导航而不是消息草稿，因此入口已经迁移到顶部栏。底部只保留一个
            // 完整宽度的输入容器，避免“Chats 胶囊 + 输入胶囊”把有限横向空间切成两块。
            Surface(
                modifier = Modifier.fillMaxWidth(),
                shape = MaterialTheme.shapes.extraLarge,
                color = inputContainerColor,
                border =
                    BorderStroke(
                        1.dp,
                        MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.48f),
                    ),
                tonalElevation = 1.dp,
                shadowElevation = 0.dp,
            ) {
                Row(
                    modifier = Modifier.padding(horizontal = 4.dp),
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
                        stopping = stopping,
                        // 空草稿时只保留弱化的发送图标；有草稿或运行中才形成高强调主操作。
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

@Composable
internal fun ComposerContextStrip(
    icon: ImageVector,
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
    stopping: Boolean = false,
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
) {
    if (isHero) {
        HeroComposer(
            state = state,
            active = active,
            stopping = stopping,
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
        )
    } else {
        ConversationComposer(
            state = state,
            active = active,
            stopping = stopping,
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
        )
    }
}
