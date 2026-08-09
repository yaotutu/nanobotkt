package com.nanobotkt.feature.chat

import android.content.ClipData
import android.content.ClipboardManager
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.PickVisualMediaRequest
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Add
import androidx.compose.material.icons.rounded.ArrowUpward
import androidx.compose.material.icons.automirrored.rounded.ArrowBack
import androidx.compose.material.icons.rounded.AttachFile
import androidx.compose.material.icons.automirrored.rounded.CallSplit
import androidx.compose.material.icons.rounded.Check
import androidx.compose.material.icons.rounded.Close
import androidx.compose.material.icons.rounded.ContentCopy
import androidx.compose.material.icons.rounded.FormatQuote
import androidx.compose.material.icons.rounded.Folder
import androidx.compose.material.icons.rounded.ExpandMore
import androidx.compose.material.icons.rounded.WarningAmber
import androidx.compose.material.icons.rounded.Image
import androidx.compose.material.icons.rounded.Menu
import androidx.compose.material.icons.rounded.MoreVert
import androidx.compose.material.icons.rounded.AccountTree
import androidx.compose.material.icons.rounded.Checklist
import androidx.compose.material.icons.rounded.ChatBubbleOutline
import androidx.compose.material.icons.rounded.DarkMode
import androidx.compose.material.icons.automirrored.rounded.Send
import androidx.compose.material.icons.automirrored.rounded.Toc
import androidx.compose.material.icons.rounded.Settings
import androidx.compose.material.icons.rounded.SmartToy
import androidx.compose.material.icons.rounded.Stop
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.AssistChip
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ListItem
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilledIconButton
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.TextRange
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.TextFieldValue
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlinx.coroutines.launch
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.nanobotkt.core.designsystem.NanobotThemeDefaults
import com.nanobotkt.core.model.CliAppInfo
import com.nanobotkt.core.model.FilePreviewPayload
import com.nanobotkt.core.model.McpPresetInfo
import com.nanobotkt.core.model.SkillSummary
import com.nanobotkt.core.model.SlashCommand
import com.nanobotkt.core.model.UiMessage
import com.nanobotkt.core.model.WorkspaceAccessMode
import com.nanobotkt.core.model.WorkspaceControls
import com.nanobotkt.core.model.WorkspaceScope
import com.nanobotkt.core.model.WorkspacesPayload
import com.nanobotkt.core.model.isAbsoluteWorkspacePath
import com.nanobotkt.core.model.projectNameFromPath
import com.nanobotkt.core.model.selectedProjectScope
import com.nanobotkt.core.model.shortWorkspacePath
import com.nanobotkt.core.model.withAccessMode

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ChatScreen(
    viewModel: ChatViewModel,
    title: String,
    onOpenDrawer: () -> Unit,
    onOpenModelSettings: () -> Unit,
    onToggleTheme: () -> Unit = {},
    onOpenConversationList: () -> Unit = {},
    onSessionCreated: (String) -> Unit = {},
    modifier: Modifier = Modifier,
) {
    val state by viewModel.state.collectAsStateWithLifecycle()
    val composer by viewModel.composer.collectAsStateWithLifecycle()
    val spacing = NanobotThemeDefaults.spacing
    val snackbar = remember { SnackbarHostState() }
    val context = LocalContext.current
    val forkTitle = stringResource(R.string.fork_title, title)
    var quoteDraft by remember { mutableStateOf<String?>(null) }
    var promptNavigatorOpen by remember { mutableStateOf(false) }
    var sessionInfoOpen by remember { mutableStateOf(false) }
    val imagePicker = rememberLauncherForActivityResult(ActivityResultContracts.PickMultipleVisualMedia(8)) {
        viewModel.addAttachments(it)
    }
    val filePicker = rememberLauncherForActivityResult(ActivityResultContracts.OpenMultipleDocuments()) {
        viewModel.addAttachments(it)
    }

    LaunchedEffect(state.sessionKey) {
        state.sessionKey?.let(onSessionCreated)
    }

    LaunchedEffect(state.error, state.model.error, composer.error) {
        (composer.error ?: state.model.error ?: state.error)?.let { snackbar.showSnackbar(it) }
    }

    LaunchedEffect(state.sessionKey) {
        promptNavigatorOpen = false
        sessionInfoOpen = false
    }

    val hasUserPrompts = remember(state.messages) {
        state.messages.any { it.role == "user" }
    }
    val listState = rememberLazyListState()
    val coroutineScope = rememberCoroutineScope()
    var jumpTargetId by remember { mutableStateOf<String?>(null) }
    var autoFollow by remember { mutableStateOf(true) }

    LaunchedEffect(jumpTargetId) {
        jumpTargetId?.let { targetId ->
            autoFollow = false
            val index = state.messages.indexOfFirst {
                it.role == "user" && it.id == targetId
            }
            if (index >= 0) {
                listState.animateScrollToItem(index)
            }
            jumpTargetId = null
        }
    }

    if (state.filePreviewLoading || state.filePreview != null || state.filePreviewError != null) {
        FilePreviewDialog(
            preview = state.filePreview,
            loading = state.filePreviewLoading,
            failed = state.filePreviewError != null,
            onDismiss = viewModel::closeFilePreview,
        )
    }

    quoteDraft?.let { draft ->
        AlertDialog(
            onDismissRequest = { quoteDraft = null },
            title = { Text(stringResource(R.string.quote_selection_title)) },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text(
                        stringResource(R.string.quote_selection_hint, MAX_QUOTED_CONTEXT_CHARS),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                    OutlinedTextField(
                        value = draft,
                        onValueChange = { quoteDraft = it.take(MAX_QUOTED_CONTEXT_CHARS) },
                        modifier = Modifier.fillMaxWidth().heightIn(min = 160.dp),
                        minLines = 5,
                        maxLines = 10,
                    )
                }
            },
            confirmButton = {
                TextButton(
                    enabled = draft.isNotBlank(),
                    onClick = {
                        viewModel.setQuotedContext(draft)
                        quoteDraft = null
                    },
                ) { Text(stringResource(R.string.add_context)) }
            },
            dismissButton = {
                TextButton(onClick = { quoteDraft = null }) { Text(stringResource(R.string.cancel)) }
            },
        )
    }

    val hero = state.chatId == null
    val composerContent: @Composable () -> Unit = {
        Composer(
            state = composer,
            active = state.activeTurnId != null,
            slashCommands = state.slashCommands,
            skills = state.skills,
            cliApps = state.cliApps,
            mcpPresets = state.mcpPresets,
            workspaceScope = state.workspaceScope,
            workspaces = state.workspaces,
            workspaceError = state.error?.takeIf { it == "workspace_scope_rejected" },
            model = state.model,
            isHero = hero,
            onWorkspaceChange = viewModel::setWorkspaceScope,
            onModelChange = viewModel::changeModelPreset,
            onOpenModelSettings = onOpenModelSettings,
            onTextChange = viewModel::updateText,
            onSelectSlashCommand = viewModel::selectSlashCommand,
            onSelectSkillMention = viewModel::selectSkillMention,
            onSelectCapabilityMention = viewModel::selectCapabilityMention,
            onSend = viewModel::send,
            onStop = viewModel::stop,
            onRemoveAttachment = viewModel::removeAttachment,
            onRemoveQueuedPrompt = viewModel::removeQueuedPrompt,
            onClearQuote = viewModel::clearQuotedContext,
            onPickImages = {
                imagePicker.launch(PickVisualMediaRequest(ActivityResultContracts.PickVisualMedia.ImageOnly))
            },
            onPickFiles = { filePicker.launch(arrayOf("*/*")) },
            onOpenConversationList = onOpenConversationList,
        )
    }

    // 页面骨架固定为“顶部状态栏 + 中间消息区 + 底部 Composer”。
    // Composer 不再覆盖消息列表，因此消息区只需要负责自己的滚动和跳转。
    Column(
        modifier = modifier
            .fillMaxSize()
            .background(MaterialTheme.colorScheme.background),
    ) {
        if (hero) {
            HeroTopBar(
                title = title,
                active = state.activeTurnId != null,
                queuedCount = composer.queuedPrompts.size,
                onOpenDrawer = onOpenDrawer,
                onOpenConversationList = onOpenConversationList,
                onToggleTheme = onToggleTheme,
            )
        } else {
            ConversationTopBar(
                title = title,
                active = state.activeTurnId != null,
                queuedCount = composer.queuedPrompts.size,
                hasPromptNavigator = state.sessionKey != null && hasUserPrompts,
                hasSessionInfo = state.sessionKey != null,
                onOpenDrawer = onOpenDrawer,
                onOpenConversationList = onOpenConversationList,
                onOpenPromptNavigator = { promptNavigatorOpen = true },
                onOpenSessionInfo = { sessionInfoOpen = true },
                onToggleTheme = onToggleTheme,
            )
        }

        Box(
            modifier = Modifier
                .weight(1f)
                .fillMaxWidth(),
        ) {
            if (state.loading) {
                CircularProgressIndicator(
                    modifier = Modifier.align(Alignment.Center),
                    color = MaterialTheme.colorScheme.primary,
                )
            } else if (hero) {
                EmptyChat(Modifier.fillMaxSize())
            } else {
                MessageList(
                    listState = listState,
                    state = state,
                    loadOlder = viewModel::loadOlder,
                    onQuote = { quoteDraft = normalizeQuotedContext(it) },
                    onPreview = viewModel::previewFile,
                    onFork = { messageId, beforeUserIndex ->
                        viewModel.fork(messageId, beforeUserIndex, forkTitle, onSessionCreated)
                    },
                    modifier = Modifier.fillMaxSize(),
                    autoFollow = autoFollow,
                )
                if (listState.canScrollForward) {
                    // 仅在用户离开消息尾部时显示快捷入口，不改变自动跟随和消息状态逻辑。
                    TextButton(
                        onClick = {
                            coroutineScope.launch {
                                listState.animateScrollToItem(state.messages.lastIndex)
                            }
                        },
                        modifier = Modifier
                            .align(Alignment.BottomEnd)
                            .padding(end = spacing.md, bottom = spacing.sm),
                    ) {
                        Text("↓ ${stringResource(R.string.jump_to_latest_messages)}")
                    }
                }
            }

            SnackbarHost(
                hostState = snackbar,
                modifier = Modifier
                    .align(Alignment.BottomCenter)
                    .padding(horizontal = spacing.md, vertical = spacing.sm),
            )
        }

        if (!state.loading) {
            composerContent()
        }
    }

    // Sheets
    PromptNavigatorSheet(
        messages = state.messages,
        visible = promptNavigatorOpen,
        onClose = { promptNavigatorOpen = false },
        onJumpToPrompt = { messageId -> jumpTargetId = messageId },
    )

    SessionInfoSheet(
        title = title,
        sessionKey = state.sessionKey,
        loadJobs = viewModel.loadSessionAutomations,
        visible = sessionInfoOpen,
        onClose = { sessionInfoOpen = false },
    )
}

@Composable
private fun HeroTopBar(
    title: String,
    active: Boolean,
    queuedCount: Int,
    onOpenDrawer: () -> Unit,
    onOpenConversationList: () -> Unit,
    onToggleTheme: () -> Unit,
) {
    TopStatusBar(
        title = title,
        active = active,
        queuedCount = queuedCount,
        onOpenDrawer = onOpenDrawer,
        overflowItems = listOf(
            TopBarAction(
                label = stringResource(R.string.open_conversation_list),
                icon = Icons.Rounded.ChatBubbleOutline,
                onClick = onOpenConversationList,
            ),
            TopBarAction(
                label = stringResource(R.string.toggle_theme),
                icon = Icons.Rounded.DarkMode,
                onClick = onToggleTheme,
            ),
        ),
    )
}

@Composable
private fun ConversationTopBar(
    title: String,
    active: Boolean,
    queuedCount: Int,
    hasPromptNavigator: Boolean,
    hasSessionInfo: Boolean,
    onOpenDrawer: () -> Unit,
    onOpenConversationList: () -> Unit,
    onOpenPromptNavigator: () -> Unit,
    onOpenSessionInfo: () -> Unit,
    onToggleTheme: () -> Unit,
) {
    val actions = listOfNotNull(
        hasPromptNavigator.takeIf { it }?.let {
            TopBarAction(
                label = stringResource(R.string.prompt_navigator_open),
                icon = Icons.Rounded.Checklist,
                onClick = onOpenPromptNavigator,
            )
        },
        hasSessionInfo.takeIf { it }?.let {
            TopBarAction(
                label = stringResource(R.string.session_info_title),
                icon = Icons.AutoMirrored.Rounded.Toc,
                onClick = onOpenSessionInfo,
            )
        },
        TopBarAction(
            label = stringResource(R.string.open_conversation_list),
            icon = Icons.Rounded.ChatBubbleOutline,
            onClick = onOpenConversationList,
        ),
        TopBarAction(
            label = stringResource(R.string.toggle_theme),
            icon = Icons.Rounded.DarkMode,
            onClick = onToggleTheme,
        ),
    )
    TopStatusBar(
        title = title,
        active = active,
        queuedCount = queuedCount,
        onOpenDrawer = onOpenDrawer,
        overflowItems = actions,
    )
}

private data class TopBarAction(
    val label: String,
    val icon: androidx.compose.ui.graphics.vector.ImageVector,
    val onClick: () -> Unit,
)

@Composable
private fun TopStatusBar(
    title: String,
    active: Boolean,
    queuedCount: Int,
    onOpenDrawer: () -> Unit,
    overflowItems: List<TopBarAction>,
) {
    val surface = MaterialTheme.colorScheme.surface
    val onSurface = MaterialTheme.colorScheme.onSurface
    val muted = MaterialTheme.colorScheme.onSurfaceVariant
    var menuOpen by remember { mutableStateOf(false) }

    Row(
        modifier = Modifier
            .fillMaxWidth()
            .background(surface)
            .statusBarsPadding()
            .height(64.dp)
            .padding(horizontal = 4.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        IconButton(
            onClick = onOpenDrawer,
            modifier = Modifier.size(48.dp),
        ) {
            Icon(
                Icons.Rounded.Menu,
                stringResource(R.string.open_navigation),
                modifier = Modifier.size(20.dp),
                tint = muted,
            )
        }
        Column(
            modifier = Modifier
                .weight(1f)
                .padding(horizontal = 8.dp),
        ) {
            Text(
                text = title.ifBlank { stringResource(R.string.conversation_list_title) },
                style = MaterialTheme.typography.titleMedium,
                color = onSurface,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            Row(
                horizontalArrangement = Arrangement.spacedBy(6.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                if (active) {
                    StatusLabel(
                        text = stringResource(R.string.thinking),
                        color = MaterialTheme.colorScheme.primary,
                        containerColor = MaterialTheme.colorScheme.primaryContainer,
                    )
                }
                if (queuedCount > 0) {
                    StatusLabel(
                        text = "${stringResource(R.string.queued_prompts_label)} $queuedCount",
                        color = muted,
                        containerColor = MaterialTheme.colorScheme.surfaceContainerHighest,
                    )
                }
            }
        }
        Box {
            IconButton(
                onClick = { menuOpen = true },
                modifier = Modifier.size(48.dp),
            ) {
                Icon(
                    Icons.Rounded.MoreVert,
                    contentDescription = stringResource(R.string.more_actions),
                    modifier = Modifier.size(22.dp),
                    tint = muted,
                )
            }
            DropdownMenu(
                expanded = menuOpen,
                onDismissRequest = { menuOpen = false },
            ) {
                overflowItems.forEach { action ->
                    DropdownMenuItem(
                        text = { Text(action.label) },
                        leadingIcon = { Icon(action.icon, contentDescription = null) },
                        onClick = {
                            menuOpen = false
                            action.onClick()
                        },
                    )
                }
            }
        }
    }
}

@Composable
private fun StatusLabel(
    text: String,
    color: Color,
    containerColor: Color,
) {
    Surface(
        shape = MaterialTheme.shapes.small,
        color = containerColor,
        contentColor = color,
    ) {
        Text(
            text = text,
            modifier = Modifier.padding(horizontal = 8.dp, vertical = 2.dp),
            style = MaterialTheme.typography.labelSmall,
            maxLines = 1,
        )
    }
}

@Composable
private fun EmptyChat(modifier: Modifier = Modifier) {
    BoxWithConstraints(modifier = modifier) {
        Text(
            text = stringResource(R.string.empty_title),
            modifier = Modifier.offset(
                x = 16.dp,
                y = maxHeight * 0.394f,
            ),
            color = MaterialTheme.colorScheme.onBackground,
            style = MaterialTheme.typography.displaySmall,
            maxLines = 1,
        )
    }
}

@Composable
private fun MessageList(
    listState: androidx.compose.foundation.lazy.LazyListState,
    state: ChatUiState,
    loadOlder: () -> Unit,
    onQuote: (String) -> Unit,
    onPreview: (String) -> Unit,
    onFork: (String, Int) -> Unit,
    modifier: Modifier = Modifier,
    autoFollow: Boolean = true,
) {
    val forkIndexes = remember(state.messages, state.userMessageOffset) {
        assistantForkIndexes(state.messages, state.userMessageOffset)
    }
    LaunchedEffect(state.messages.size) {
        if (autoFollow && state.messages.isNotEmpty()) listState.animateScrollToItem(state.messages.lastIndex)
    }
    LazyColumn(
        state = listState,
        modifier = modifier,
        contentPadding = PaddingValues(
            start = NanobotThemeDefaults.spacing.sm,
            top = NanobotThemeDefaults.spacing.lg,
            end = NanobotThemeDefaults.spacing.sm,
            // Composer 已经是 Column 的独立底部区域，这里只保留消息与边界的呼吸空间。
            bottom = NanobotThemeDefaults.spacing.md,
        ),
        verticalArrangement = Arrangement.spacedBy(NanobotThemeDefaults.spacing.md),
    ) {
        if (state.hasMoreBefore) {
            item {
                TextButton(onClick = loadOlder, enabled = !state.loadingOlder, modifier = Modifier.fillMaxWidth()) {
                    if (state.loadingOlder) CircularProgressIndicator(Modifier.size(18.dp), strokeWidth = 2.dp)
                    else Text(stringResource(R.string.load_older))
                }
            }
        }
        itemsIndexed(state.messages, key = { _, message -> message.id }) { index, message ->
            MessageBubble(
                message = message,
                forkIndex = forkIndexes.getOrNull(index),
                onQuote = { onQuote(message.content) },
                onPreview = onPreview,
                onFork = { forkIndexes.getOrNull(index)?.let { onFork(message.id, it) } },
            )
        }
    }
}

@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun MessageBubble(
    message: UiMessage,
    forkIndex: Int?,
    onQuote: () -> Unit,
    onPreview: (String) -> Unit,
    onFork: () -> Unit,
) {
    val user = message.role == "user"
    val textColor = MaterialTheme.colorScheme.onSurface
    val mutedColor = MaterialTheme.colorScheme.onSurfaceVariant
    // 用户消息使用 primaryContainer，助手消息保持页面背景，阅读层级更接近 MD3 的 tonal surface。
    val userBubbleColor = MaterialTheme.colorScheme.primaryContainer
    val userTextColor = MaterialTheme.colorScheme.onPrimaryContainer
    val context = LocalContext.current
    val clipboard = remember(context) { context.getSystemService(ClipboardManager::class.java) }
    val copyText: (String) -> Boolean = { text ->
        clipboard?.let {
            runCatching { it.setPrimaryClip(ClipData.newPlainText("message", text)) }.isSuccess
        } ?: false
    }
    var copied by remember(message.id) { mutableStateOf(false) }
    var reasoningOpen by remember(message.id) { mutableStateOf(false) }
    LaunchedEffect(copied) {
        if (copied) {
            kotlinx.coroutines.delay(1_500L)
            copied = false
        }
    }
    val elapsedMs = message.latencyMs
        ?: message.completedAt?.minus(message.createdAt)?.coerceAtLeast(0L)

    if (user) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.End,
        ) {
            Column(horizontalAlignment = Alignment.End) {
                Surface(
                    modifier = Modifier
                        .widthIn(min = 54.dp, max = 320.dp)
                        .combinedClickable(
                            onClick = {},
                            onLongClick = { copyText(message.content) },
                        ),
                    shape = MaterialTheme.shapes.extraLarge,
                    color = userBubbleColor,
                    tonalElevation = 0.dp,
                    shadowElevation = 0.dp,
                ) {
                    Box(
                        modifier = Modifier.padding(horizontal = 18.dp, vertical = 10.dp),
                        contentAlignment = Alignment.Center,
                    ) {
                        Text(
                            text = message.content,
                            color = userTextColor,
                            style = MaterialTheme.typography.bodyLarge,
                        )
                    }
                }
                Spacer(Modifier.height(10.dp))
                IconButton(
                    onClick = { copyText(message.content) },
                    modifier = Modifier.size(40.dp),
                ) {
                    Icon(
                        Icons.Rounded.ContentCopy,
                        stringResource(R.string.copy),
                        modifier = Modifier.size(17.dp),
                        tint = mutedColor,
                    )
                }
            }
        }
        return
    }

    Column(
        modifier = Modifier
            .fillMaxWidth()
            .combinedClickable(
                onClick = {},
                onLongClick = onQuote,
            ),
    ) {
        val reasoning = message.reasoning
        if (!reasoning.isNullOrBlank()) {
            Row(
                modifier = Modifier
                    .height(28.dp)
                    .combinedClickable(
                        onClick = { reasoningOpen = !reasoningOpen },
                        onLongClick = {},
                    ),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text(
                    text = when {
                        message.reasoningStreaming == true -> "Thinking…"
                        elapsedMs != null -> "Thought for ${(elapsedMs / 1_000L).coerceAtLeast(1L)}s"
                        else -> "Thought"
                    },
                    color = mutedColor,
                    style = MaterialTheme.typography.bodyMedium,
                )
                Icon(
                    Icons.Rounded.ExpandMore,
                    contentDescription = null,
                    modifier = Modifier.padding(start = 3.dp).size(13.dp),
                    tint = mutedColor,
                )
            }
            AnimatedVisibility(reasoningOpen) {
                Text(
                    text = reasoning,
                    modifier = Modifier.padding(top = 4.dp, bottom = 10.dp),
                    color = mutedColor,
                    style = MaterialTheme.typography.bodySmall.copy(lineHeight = 20.sp),
                )
            }
            Spacer(Modifier.height(22.dp))
        }

        if (message.content.isNotBlank()) {
            Text(
                text = message.content,
                color = textColor,
                style = MaterialTheme.typography.bodyLarge,
            )
        }
        message.toolEvents?.takeIf { it.isNotEmpty() }?.let { tools ->
            Text(
                pluralStringResource(R.plurals.tool_count, tools.size, tools.size),
                modifier = Modifier.padding(top = 8.dp),
                style = MaterialTheme.typography.labelMedium,
                color = mutedColor,
            )
        }
        message.fileEdits?.forEach { edit ->
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(top = 5.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(4.dp),
            ) {
                Text(
                    "${edit.path}  +${edit.added} -${edit.deleted}",
                    modifier = Modifier.weight(1f),
                    style = MaterialTheme.typography.labelMedium,
                    color = MaterialTheme.colorScheme.primary,
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis,
                )
                TextButton(
                    onClick = { onPreview(edit.absolutePath ?: edit.path) },
                    contentPadding = PaddingValues(horizontal = 8.dp, vertical = 0.dp),
                ) {
                    Text(stringResource(R.string.file_preview))
                }
            }
        }
        if (message.isStreaming == true) {
            CircularProgressIndicator(
                modifier = Modifier.padding(top = 8.dp).size(14.dp),
                strokeWidth = 1.5.dp,
                color = mutedColor,
            )
        }
        if (message.isStreaming != true && message.content.isNotBlank()) {
            Row(
                modifier = Modifier.padding(top = 9.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                val copyLabel = stringResource(if (copied) R.string.copied else R.string.copy)
                IconButton(
                    onClick = {
                        if (copyText(message.content)) copied = true
                    },
                    modifier = Modifier.size(40.dp),
                ) {
                    Icon(
                        imageVector = if (copied) Icons.Rounded.Check else Icons.Rounded.ContentCopy,
                        contentDescription = copyLabel,
                        modifier = Modifier.size(17.dp),
                        tint = mutedColor,
                    )
                }
                if (forkIndex != null) {
                    IconButton(onClick = onFork, modifier = Modifier.size(40.dp)) {
                        Icon(
                            Icons.AutoMirrored.Rounded.CallSplit,
                            stringResource(R.string.fork),
                            modifier = Modifier.size(18.dp),
                            tint = mutedColor,
                        )
                    }
                }
                elapsedMs?.let {
                    Text(
                        text = formatMessageLatency(it),
                        modifier = Modifier.padding(start = 8.dp),
                        color = mutedColor,
                        style = MaterialTheme.typography.labelSmall,
                    )
                }
            }
        }
    }
}

@Composable
private fun FilePreviewDialog(
    preview: FilePreviewPayload?,
    loading: Boolean,
    failed: Boolean,
    onDismiss: () -> Unit,
) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(R.string.file_preview_title)) },
        text = {
            when {
                loading -> CircularProgressIndicator(modifier = Modifier.size(24.dp))
                failed -> Text(
                    stringResource(R.string.file_preview_load_failed),
                    color = MaterialTheme.colorScheme.error,
                )
                preview != null -> Column(
                    modifier = Modifier
                        .fillMaxWidth()
                        .heightIn(max = 460.dp)
                        .verticalScroll(rememberScrollState()),
                    verticalArrangement = Arrangement.spacedBy(6.dp),
                ) {
                    Text(
                        preview.displayPath,
                        style = MaterialTheme.typography.titleSmall,
                        fontWeight = FontWeight.Medium,
                    )
                    Text(
                        stringResource(R.string.file_preview_language, preview.language),
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                    Text(
                        stringResource(R.string.file_preview_size, preview.size),
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                    if (preview.truncated) {
                        Text(
                            stringResource(R.string.file_preview_truncated),
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.error,
                        )
                    }
                    Surface(
                        modifier = Modifier.fillMaxWidth(),
                        color = MaterialTheme.colorScheme.surfaceVariant,
                        shape = MaterialTheme.shapes.small,
                    ) {
                        Text(
                            preview.content,
                            modifier = Modifier.padding(10.dp),
                            style = MaterialTheme.typography.bodySmall,
                        )
                    }
                }
            }
        },
        confirmButton = {
            TextButton(onClick = onDismiss) { Text(stringResource(R.string.close)) }
        },
    )
}

private fun formatMessageLatency(durationMs: Long): String {
    if (durationMs < 1_000L) return "${durationMs.coerceAtLeast(0L)}ms"
    val tenths = (durationMs.coerceAtLeast(0L) + 50L) / 100L
    return if (tenths % 10L == 0L) "${tenths / 10L}s"
    else "${tenths / 10L}.${tenths % 10L}s"
}


@Composable
private fun ComposerTextField(
    state: ComposerUiState,
    modifier: Modifier,
    placeholder: String,
    textColor: Color,
    mutedColor: Color,
    onTextChange: (String, Int) -> Unit,
    onSend: () -> Unit,
) {
    val hasDraft = state.text.isNotBlank() ||
        state.attachments.isNotEmpty() ||
        !state.quotedContext.isNullOrBlank()

    BasicTextField(
        value = TextFieldValue(
            text = state.text,
            selection = TextRange(state.cursorPosition.coerceIn(0, state.text.length)),
        ),
        onValueChange = { value -> onTextChange(value.text, value.selection.end) },
        modifier = modifier
            .heightIn(min = 48.dp, max = 128.dp)
            .semantics { contentDescription = placeholder },
        enabled = !state.sending,
        textStyle = MaterialTheme.typography.bodyLarge.copy(color = textColor),
        keyboardOptions = KeyboardOptions(imeAction = ImeAction.Send),
        keyboardActions = KeyboardActions(onSend = { if (hasDraft && !state.sending) onSend() }),
        maxLines = 5,
        decorationBox = { innerTextField ->
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 4.dp, vertical = 4.dp),
            ) {
                if (state.text.isEmpty()) {
                    Text(
                        text = placeholder,
                        color = mutedColor,
                        style = MaterialTheme.typography.bodyLarge,
                    )
                }
                innerTextField()
            }
        },
    )
}

/**
 * 加号和发送共用一个位置，避免低频入口长期占据输入区空间。
 * 只有当前没有可发送草稿时才打开二级菜单；附件或引用本身也是可发送 payload，
 * 因此即使文字为空，只要已经选中了附件/引用，也必须保留发送能力。
 *
 * 输入区右侧的动作入口。
 *
 * 这里刻意把“更多”做成单一 BottomSheet，而不是 DropdownMenu：模型和权限都是低频配置，
 * 但它们仍需要完整的选择内容。点击模型或权限时只切换当前 Sheet 的内容页，不创建第二个
 * Dialog 或导航页面，避免出现“弹层里面再套弹层”以及两个窗口同时抢焦点。
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun ComposerActionButton(
    showSendAction: Boolean,
    stopButton: Boolean,
    sendEnabled: Boolean,
    sending: Boolean,
    voiceRecording: Boolean,
    voiceTranscribing: Boolean,
    controlColor: Color,
    sendColor: Color,
    sendContentColor: Color,
    workspaceScope: WorkspaceScope?,
    workspaces: WorkspacesPayload?,
    model: ChatModelSelection,
    active: Boolean,
    onWorkspaceChange: (WorkspaceScope) -> Unit,
    onModelChange: (String) -> Unit,
    onOpenModelSettings: () -> Unit,
    onPickImages: () -> Unit,
    onPickFiles: () -> Unit,
    onSend: () -> Unit,
    onStop: () -> Unit,
) {
    var moreSheetOpen by remember { mutableStateOf(false) }
    var moreSheetPage by remember { mutableStateOf(ComposerMorePage.Root) }
    val sheetState = androidx.compose.material3.rememberModalBottomSheetState(skipPartiallyExpanded = true)
    val coroutineScope = rememberCoroutineScope()
    val activeScope = workspaceScope ?: workspaces?.defaultScope

    /**
     * 关闭“更多”面板，并在动画真正结束后执行后续动作。
     * 图片和文件会把动作交给系统选择器；模型设置则会离开当前面板进入 Settings。
     * 统一从这里收口，可以避免系统选择器或 Settings 与 BottomSheet 同时挂在窗口上。
     */
    fun dismissSheetThen(action: () -> Unit) {
        coroutineScope.launch {
            sheetState.hide()
            moreSheetOpen = false
            moreSheetPage = ComposerMorePage.Root
            action()
        }
    }

    /**
     * 模型和权限不再关闭 Sheet 后打开第二个 Dialog，而是在同一个 Sheet 内切换内容页。
     * 这样用户始终知道自己仍在“+”菜单中，返回也只需回到一级菜单，不会产生弹层套弹层的错觉。
     */
    fun showMorePage(page: ComposerMorePage) {
        moreSheetPage = page
    }

    val enabled = when {
        stopButton -> true
        showSendAction -> sendEnabled
        else -> !voiceRecording && !voiceTranscribing && !sending
    }
    Box {
        Surface(
            onClick = {
                when {
                    stopButton -> onStop()
                    showSendAction -> onSend()
                    else -> {
                        moreSheetPage = ComposerMorePage.Root
                        moreSheetOpen = true
                    }
                }
            },
            enabled = enabled,
            modifier = Modifier.size(48.dp),
            shape = CircleShape,
            color = if (showSendAction || stopButton) sendColor else controlColor,
            tonalElevation = 2.dp,
            shadowElevation = 0.dp,
        ) {
            Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                when {
                    sending -> CircularProgressIndicator(
                        Modifier.size(20.dp),
                        strokeWidth = 2.dp,
                        color = sendContentColor,
                    )
                    stopButton -> Icon(
                        Icons.Rounded.Stop,
                        contentDescription = stringResource(R.string.stop),
                        tint = sendContentColor,
                    )
                    showSendAction -> Icon(
                        Icons.Rounded.ArrowUpward,
                        contentDescription = stringResource(R.string.send),
                        tint = sendContentColor,
                    )
                    else -> Icon(
                        Icons.Rounded.Add,
                        contentDescription = stringResource(R.string.composer_more),
                        modifier = Modifier.size(20.dp),
                        tint = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
        }

        if (moreSheetOpen) {
            ModalBottomSheet(
                onDismissRequest = {
                    moreSheetOpen = false
                    moreSheetPage = ComposerMorePage.Root
                },
                sheetState = sheetState,
            ) {
                ComposerMoreSheet(
                    page = moreSheetPage,
                    model = model,
                    activeScope = activeScope,
                    controls = workspaces?.controls,
                    modelEnabled = model.enabled && !sending,
                    accessEnabled = activeScope != null && !active,
                    disabled = sending || active,
                    onAction = { action ->
                        when (action) {
                            ComposerMoreAction.Images -> dismissSheetThen(onPickImages)
                            ComposerMoreAction.Files -> dismissSheetThen(onPickFiles)
                            ComposerMoreAction.Model -> showMorePage(ComposerMorePage.Model)
                            ComposerMoreAction.Access -> showMorePage(ComposerMorePage.Access)
                        }
                    },
                    onModelChange = onModelChange,
                    onOpenModelSettings = { dismissSheetThen(onOpenModelSettings) },
                    onWorkspaceChange = onWorkspaceChange,
                    onDismissSheet = { dismissSheetThen {} },
                    onBack = { moreSheetPage = ComposerMorePage.Root },
                )
            }
        }
    }
}

/** “+”菜单在同一个 BottomSheet 内的页面，不创建第二个 Dialog 或导航页面。 */
private enum class ComposerMorePage {
    Root,
    Model,
    Access,
}

/** 更多菜单中可执行的动作；它只描述一级面板上的目标。 */
private enum class ComposerMoreAction {
    Images,
    Files,
    Model,
    Access,
}

/**
 * “+”菜单的单一 BottomSheet 容器。
 * Root/Model/Access 是同一层中的内容切换，不会同时存在多个弹层；
 * 因此模型和权限选择都能保持上下文，并且返回手势只作用于当前 Sheet。
 */
@Composable
private fun ComposerMoreSheet(
    page: ComposerMorePage,
    model: ChatModelSelection,
    activeScope: WorkspaceScope?,
    controls: WorkspaceControls?,
    modelEnabled: Boolean,
    accessEnabled: Boolean,
    disabled: Boolean,
    onAction: (ComposerMoreAction) -> Unit,
    onModelChange: (String) -> Unit,
    onOpenModelSettings: () -> Unit,
    onWorkspaceChange: (WorkspaceScope) -> Unit,
    onDismissSheet: () -> Unit,
    onBack: () -> Unit,
) {
    when (page) {
        ComposerMorePage.Root -> ComposerMoreRootPage(
            model = model,
            activeScope = activeScope,
            modelEnabled = modelEnabled,
            accessEnabled = accessEnabled,
            onAction = onAction,
        )
        ComposerMorePage.Model -> ComposerMoreModelPage(
            model = model,
            disabled = !modelEnabled || disabled,
            onChange = onModelChange,
            onOpenSettings = onOpenModelSettings,
            onDismissSheet = onDismissSheet,
            onBack = onBack,
        )
        ComposerMorePage.Access -> ComposerMoreAccessPage(
            scope = activeScope,
            controls = controls,
            disabled = !accessEnabled || disabled,
            onChange = onWorkspaceChange,
            onDismissSheet = onDismissSheet,
            onBack = onBack,
        )
    }
}

@Composable
private fun ComposerMoreRootPage(
    model: ChatModelSelection,
    activeScope: WorkspaceScope?,
    modelEnabled: Boolean,
    accessEnabled: Boolean,
    onAction: (ComposerMoreAction) -> Unit,
) {
    val accessLabel = activeScope?.let { scope ->
        stringResource(
            if (scope.accessMode == WorkspaceAccessMode.FULL) {
                R.string.workspace_access_full_short
            } else {
                R.string.workspace_access_default_short
            },
        )
    }
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .navigationBarsPadding()
            .padding(bottom = 12.dp),
    ) {
        Text(
            text = stringResource(R.string.composer_more),
            modifier = Modifier.padding(horizontal = 24.dp, vertical = 8.dp),
            style = MaterialTheme.typography.titleMedium,
        )
        ComposerMoreSheetRow(
            icon = Icons.Rounded.Image,
            title = stringResource(R.string.attach_image),
            onClick = { onAction(ComposerMoreAction.Images) },
        )
        ComposerMoreSheetRow(
            icon = Icons.Rounded.AttachFile,
            title = stringResource(R.string.attach_file),
            onClick = { onAction(ComposerMoreAction.Files) },
        )
        HorizontalDivider(modifier = Modifier.padding(vertical = 8.dp))
        ComposerMoreSheetRow(
            icon = Icons.Rounded.SmartToy,
            title = stringResource(R.string.model_select_title),
            value = model.displayLabel,
            showChevron = true,
            enabled = modelEnabled,
            onClick = { onAction(ComposerMoreAction.Model) },
        )
        if (activeScope != null) {
            ComposerMoreSheetRow(
                icon = Icons.Rounded.Folder,
                title = stringResource(R.string.workspace_access_title),
                value = accessLabel.orEmpty(),
                showChevron = true,
                enabled = accessEnabled,
                onClick = { onAction(ComposerMoreAction.Access) },
            )
        }
    }
}

/** 模型选择页复用原有的服务端确认逻辑，但视觉上仍属于同一个 BottomSheet。 */
@Composable
private fun ComposerMoreModelPage(
    model: ChatModelSelection,
    disabled: Boolean,
    onChange: (String) -> Unit,
    onOpenSettings: () -> Unit,
    onDismissSheet: () -> Unit,
    onBack: () -> Unit,
) {
    val options = remember(model.presets) {
        model.presets.filter { preset -> !preset.isDefault && preset.name.isNotBlank() }
    }
    var requestedPreset by remember { mutableStateOf<String?>(null) }

    LaunchedEffect(requestedPreset, model.pendingPreset, model.error, model.activePreset) {
        val requested = requestedPreset ?: return@LaunchedEffect
        if (model.pendingPreset == null && model.error == null && model.activePreset == requested) {
            requestedPreset = null
            onDismissSheet()
        }
    }

    ComposerMorePageHeader(
        title = stringResource(R.string.model_select_title),
        onBack = onBack,
    )
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .navigationBarsPadding()
            .padding(horizontal = 20.dp)
            .padding(bottom = 12.dp),
        verticalArrangement = Arrangement.spacedBy(10.dp),
    ) {
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
                            if (selected) onDismissSheet()
                            else {
                                requestedPreset = preset.name
                                onChange(preset.name)
                            }
                        }
                    },
                    enabled = model.pendingPreset == null && !disabled,
                    modifier = Modifier.fillMaxWidth(),
                    color = if (selected) MaterialTheme.colorScheme.secondaryContainer else MaterialTheme.colorScheme.surface,
                    shape = MaterialTheme.shapes.medium,
                ) {
                    Row(
                        modifier = Modifier.fillMaxWidth().padding(12.dp),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(12.dp),
                    ) {
                        Column(Modifier.weight(1f)) {
                            Text(preset.label.ifBlank { preset.name }, style = MaterialTheme.typography.labelLarge)
                            Text(
                                listOf(provider, preset.model).filter(String::isNotBlank).joinToString(" · "),
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis,
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                        }
                        when {
                            loading -> CircularProgressIndicator(Modifier.size(20.dp), strokeWidth = 2.dp)
                            selected -> Icon(Icons.Rounded.Check, contentDescription = null)
                        }
                    }
                }
            }
            item {
                TextButton(
                    onClick = onOpenSettings,
                    enabled = model.pendingPreset == null && !disabled,
                    modifier = Modifier.fillMaxWidth(),
                ) {
                    Icon(Icons.Rounded.Settings, contentDescription = null, Modifier.size(18.dp))
                    Text(stringResource(R.string.model_settings), Modifier.padding(start = 8.dp))
                }
            }
        }
    }
}

/** 权限选择与模型选择共用同一 Sheet 页面骨架，不再从 Sheet 跳到 AlertDialog。 */
@Composable
private fun ComposerMoreAccessPage(
    scope: WorkspaceScope?,
    controls: WorkspaceControls?,
    disabled: Boolean,
    onChange: (WorkspaceScope) -> Unit,
    onDismissSheet: () -> Unit,
    onBack: () -> Unit,
) {
    if (scope == null) return
    ComposerMorePageHeader(
        title = stringResource(R.string.workspace_access_title),
        onBack = onBack,
    )
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .navigationBarsPadding()
            .padding(horizontal = 20.dp)
            .padding(bottom = 12.dp),
        verticalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        ComposerMoreAccessOption(
            selected = scope.accessMode == WorkspaceAccessMode.RESTRICTED,
            enabled = !disabled,
            title = stringResource(R.string.workspace_access_default),
            description = stringResource(R.string.workspace_access_default_description),
            onClick = {
                onChange(scope.withAccessMode(WorkspaceAccessMode.RESTRICTED))
                onDismissSheet()
            },
        )
        ComposerMoreAccessOption(
            selected = scope.accessMode == WorkspaceAccessMode.FULL,
            enabled = !disabled && controls?.canUseFullAccess != false,
            title = stringResource(R.string.workspace_access_full),
            description = stringResource(R.string.workspace_access_full_description),
            onClick = {
                onChange(scope.withAccessMode(WorkspaceAccessMode.FULL))
                onDismissSheet()
            },
        )
    }
}

@Composable
private fun ComposerMorePageHeader(
    title: String,
    onBack: () -> Unit,
) {
    Row(
        modifier = Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 4.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        IconButton(onClick = onBack) {
            Icon(Icons.AutoMirrored.Rounded.ArrowBack, contentDescription = stringResource(R.string.cancel))
        }
        Text(title, style = MaterialTheme.typography.titleMedium)
    }
}

@Composable
private fun ComposerMoreAccessOption(
    selected: Boolean,
    enabled: Boolean,
    title: String,
    description: String,
    onClick: () -> Unit,
) {
    Surface(
        onClick = onClick,
        modifier = Modifier.fillMaxWidth(),
        enabled = enabled,
        color = if (selected) MaterialTheme.colorScheme.secondaryContainer else MaterialTheme.colorScheme.surface,
        shape = MaterialTheme.shapes.medium,
    ) {
        Column(Modifier.padding(12.dp)) {
            Text(title, style = MaterialTheme.typography.labelLarge)
            Text(description, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
    }
}

@Composable
private fun ComposerMoreSheetRow(
    icon: androidx.compose.ui.graphics.vector.ImageVector,
    title: String,
    value: String? = null,
    showChevron: Boolean = false,
    enabled: Boolean = true,
    onClick: () -> Unit,
) {
    Surface(
        onClick = onClick,
        enabled = enabled,
        modifier = Modifier.fillMaxWidth(),
        color = Color.Transparent,
    ) {
        ListItem(
            headlineContent = { Text(title) },
            supportingContent = value?.takeIf(String::isNotBlank)?.let { current ->
                { Text(current, maxLines = 1, overflow = TextOverflow.Ellipsis) }
            },
            leadingContent = { Icon(icon, contentDescription = null) },
            trailingContent = if (showChevron) {
                { Icon(Icons.Rounded.ExpandMore, contentDescription = null) }
            } else {
                null
            },
        )
    }
}

@Composable
private fun HeroComposer(
    state: ComposerUiState,
    active: Boolean,
    slashCommands: List<SlashCommand>,
    skills: List<SkillSummary>,
    cliApps: List<CliAppInfo>,
    mcpPresets: List<McpPresetInfo>,
    workspaceScope: WorkspaceScope?,
    workspaces: WorkspacesPayload?,
    workspaceError: String?,
    model: ChatModelSelection,
    onWorkspaceChange: (WorkspaceScope) -> Unit,
    onModelChange: (String) -> Unit,
    onOpenModelSettings: () -> Unit,
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
        workspaceScope = workspaceScope,
        workspaces = workspaces,
        workspaceError = workspaceError,
        model = model,
        placeholder = stringResource(R.string.composer_placeholder),
        onWorkspaceChange = onWorkspaceChange,
        onModelChange = onModelChange,
        onOpenModelSettings = onOpenModelSettings,
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
private fun ConversationComposer(
    state: ComposerUiState,
    active: Boolean,
    slashCommands: List<SlashCommand>,
    skills: List<SkillSummary>,
    cliApps: List<CliAppInfo>,
    mcpPresets: List<McpPresetInfo>,
    workspaceScope: WorkspaceScope?,
    workspaces: WorkspacesPayload?,
    workspaceError: String?,
    model: ChatModelSelection,
    onWorkspaceChange: (WorkspaceScope) -> Unit,
    onModelChange: (String) -> Unit,
    onOpenModelSettings: () -> Unit,
    onTextChange: (String, Int) -> Unit,
    onSelectSlashCommand: (SlashCommand) -> Unit,
    onSelectSkillMention: (SkillMentionCandidate) -> Unit,
    onSelectCapabilityMention: (CapabilityMentionCandidate) -> Unit,
    onSend: () -> Unit,
    onStop: () -> Unit,
    onRemoveAttachment: (Int) -> Unit,
    onRemoveQueuedPrompt: (String) -> Unit,
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
        workspaceScope = workspaceScope,
        workspaces = workspaces,
        workspaceError = workspaceError,
        model = model,
        placeholder = stringResource(R.string.composer_placeholder),
        onWorkspaceChange = onWorkspaceChange,
        onModelChange = onModelChange,
        onOpenModelSettings = onOpenModelSettings,
        onTextChange = onTextChange,
        onSelectSlashCommand = onSelectSlashCommand,
        onSelectSkillMention = onSelectSkillMention,
        onSelectCapabilityMention = onSelectCapabilityMention,
        onSend = onSend,
        onStop = onStop,
        onRemoveAttachment = onRemoveAttachment,
        onRemoveQueuedPrompt = onRemoveQueuedPrompt,
        onClearQuote = onClearQuote,
        onPickImages = onPickImages,
        onPickFiles = onPickFiles,
        onOpenConversationList = onOpenConversationList,
    )
}

/**
 * Chat 输入区的唯一视觉骨架。
 *
 * 这里把“状态附件/引用/排队提示”和“可编辑输入框”分成两个层级：
 * 上层只承载上下文，下层才负责输入与主操作。这样状态变化不会把输入框的
 * 左右按钮推来推去，也不会让消息列表看起来像被浮动卡片遮挡。
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun ComposerLayout(
    state: ComposerUiState,
    active: Boolean,
    slashCommands: List<SlashCommand>,
    skills: List<SkillSummary>,
    cliApps: List<CliAppInfo>,
    mcpPresets: List<McpPresetInfo>,
    workspaceScope: WorkspaceScope?,
    workspaces: WorkspacesPayload?,
    workspaceError: String?,
    model: ChatModelSelection,
    placeholder: String,
    onWorkspaceChange: (WorkspaceScope) -> Unit,
    onModelChange: (String) -> Unit,
    onOpenModelSettings: () -> Unit,
    onTextChange: (String, Int) -> Unit,
    onSelectSlashCommand: (SlashCommand) -> Unit,
    onSelectSkillMention: (SkillMentionCandidate) -> Unit,
    onSelectCapabilityMention: (CapabilityMentionCandidate) -> Unit,
    onSend: () -> Unit,
    onStop: () -> Unit,
    onRemoveAttachment: (Int) -> Unit,
    onRemoveQueuedPrompt: (String) -> Unit = {},
    onClearQuote: () -> Unit = {},
    onPickImages: () -> Unit,
    onPickFiles: () -> Unit,
    onOpenConversationList: () -> Unit,
) {
    val hasDraft = state.text.isNotBlank() ||
        state.attachments.isNotEmpty() ||
        !state.quotedContext.isNullOrBlank()
    val stopButton = active && !hasDraft
    val sendEnabled = stopButton ||
        (!state.sending && !state.voice.isRecording && !state.voice.isTranscribing && hasDraft)
    val slashSuggestions = if (state.slashMenuDismissed) emptyList()
    else visibleSlashCommands(state.text, slashCommands, active)
    val skillSuggestions = if (state.slashMenuDismissed) emptyList()
    else skillMentionCandidates(skillMentionQuery(state.text, state.cursorPosition), skills, state.recentCommands)
    val capabilitySuggestions = if (state.mentionMenuDismissed) emptyList()
    else capabilityMentionCandidates(
        capabilityMentionQuery(state.text, state.cursorPosition),
        cliApps,
        mcpPresets,
    )
    val mutedColor = MaterialTheme.colorScheme.onSurfaceVariant
    val inputContainerColor = MaterialTheme.colorScheme.surfaceContainer
    val actionContainerColor = MaterialTheme.colorScheme.surfaceContainerHigh
    val actionColor = if (stopButton || (sendEnabled && hasDraft)) {
        MaterialTheme.colorScheme.primary
    } else {
        MaterialTheme.colorScheme.surfaceVariant
    }
    val actionContentColor = if (stopButton || (sendEnabled && hasDraft)) {
        MaterialTheme.colorScheme.onPrimary
    } else {
        MaterialTheme.colorScheme.onSurfaceVariant
    }

    Surface(
        modifier = Modifier
            .fillMaxWidth()
            // Composer 是页面布局的一部分，Insets 只作用于这一整块底栏，消息区不会被覆盖。
            .navigationBarsPadding()
            .imePadding(),
        color = MaterialTheme.colorScheme.surfaceContainerLow,
        contentColor = MaterialTheme.colorScheme.onSurface,
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 16.dp, vertical = 8.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            when {
                slashSuggestions.isNotEmpty() -> SlashCommandSuggestions(slashSuggestions, onSelectSlashCommand)
                skillSuggestions.isNotEmpty() -> SkillMentionSuggestions(skillSuggestions, onSelectSkillMention)
                capabilitySuggestions.isNotEmpty() -> CapabilityMentionSuggestions(
                    capabilitySuggestions,
                    onSelectCapabilityMention,
                )
            }

            if (state.queuedPrompts.isNotEmpty()) {
                ComposerContextStrip(
                    icon = Icons.Rounded.Checklist,
                    label = stringResource(R.string.queued_prompts_label),
                ) {
                    state.queuedPrompts.take(2).forEach { prompt ->
                        AssistChip(
                            onClick = { onRemoveQueuedPrompt(prompt.id) },
                            label = {
                                Text(
                                    queuedPromptPreview(prompt),
                                    maxLines = 1,
                                    overflow = TextOverflow.Ellipsis,
                                )
                            },
                            trailingIcon = {
                                Icon(
                                    Icons.Rounded.Close,
                                    contentDescription = stringResource(R.string.remove_queued_prompt),
                                    modifier = Modifier.size(16.dp),
                                )
                            },
                        )
                    }
                }
            }

            state.quotedContext?.takeIf(String::isNotBlank)?.let { quote ->
                Surface(
                    modifier = Modifier.fillMaxWidth(),
                    shape = MaterialTheme.shapes.large,
                    color = MaterialTheme.colorScheme.secondaryContainer,
                ) {
                    Row(
                        modifier = Modifier.padding(start = 12.dp, top = 8.dp, bottom = 8.dp, end = 4.dp),
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
                        IconButton(
                            onClick = onClearQuote,
                            modifier = Modifier.size(48.dp),
                        ) {
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

            Surface(
                modifier = Modifier.fillMaxWidth(),
                shape = MaterialTheme.shapes.extraLarge,
                color = inputContainerColor,
                tonalElevation = 1.dp,
            ) {
                Column(
                    modifier = Modifier.padding(horizontal = 12.dp, vertical = 8.dp),
                    verticalArrangement = Arrangement.spacedBy(4.dp),
                ) {
                    ComposerTextField(
                        state = state,
                        modifier = Modifier.fillMaxWidth(),
                        placeholder = placeholder,
                        textColor = MaterialTheme.colorScheme.onSurface,
                        mutedColor = mutedColor,
                        onTextChange = onTextChange,
                        onSend = onSend,
                    )
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        // 该入口继续打开会话列表；麦克风功能已移除，不在输入区保留误导性的录音图标。
                        IconButton(
                            onClick = onOpenConversationList,
                            modifier = Modifier.size(48.dp),
                        ) {
                            Icon(
                                Icons.Rounded.ChatBubbleOutline,
                                contentDescription = stringResource(R.string.open_conversation_list),
                                tint = mutedColor,
                            )
                        }
                        Spacer(Modifier.width(4.dp))
                        Text(
                            text = when {
                                state.voice.isTranscribing -> stringResource(R.string.transcribing)
                                state.voice.isRecording -> stringResource(R.string.recording)
                                active && !hasDraft -> stringResource(R.string.composer_placeholder_streaming)
                                else -> stringResource(R.string.composer_ready_label)
                            },
                            modifier = Modifier.weight(1f),
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                            style = MaterialTheme.typography.labelMedium,
                            color = mutedColor,
                        )
                        ComposerActionButton(
                            showSendAction = hasDraft,
                            stopButton = stopButton,
                            sendEnabled = sendEnabled,
                            sending = state.sending,
                            voiceRecording = state.voice.isRecording,
                            voiceTranscribing = state.voice.isTranscribing,
                            controlColor = actionContainerColor,
                            sendColor = actionColor,
                            sendContentColor = actionContentColor,
                            onSend = onSend,
                            onStop = onStop,
                            workspaceScope = workspaceScope,
                            workspaces = workspaces,
                            model = model,
                            active = active,
                            onWorkspaceChange = onWorkspaceChange,
                            onModelChange = onModelChange,
                            onOpenModelSettings = onOpenModelSettings,
                            onPickImages = onPickImages,
                            onPickFiles = onPickFiles,
                        )
                    }
                }
            }
        }
    }
}

@Composable
private fun ComposerContextStrip(
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
            modifier = Modifier
                .fillMaxWidth()
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
private fun Composer(
    state: ComposerUiState,
    active: Boolean,
    slashCommands: List<SlashCommand>,
    skills: List<SkillSummary>,
    cliApps: List<CliAppInfo>,
    mcpPresets: List<McpPresetInfo>,
    workspaceScope: WorkspaceScope?,
    workspaces: WorkspacesPayload?,
    workspaceError: String?,
    model: ChatModelSelection,
    isHero: Boolean,
    onWorkspaceChange: (WorkspaceScope) -> Unit,
    onModelChange: (String) -> Unit,
    onOpenModelSettings: () -> Unit,
    onTextChange: (String, Int) -> Unit,
    onSelectSlashCommand: (SlashCommand) -> Unit,
    onSelectSkillMention: (SkillMentionCandidate) -> Unit,
    onSelectCapabilityMention: (CapabilityMentionCandidate) -> Unit,
    onSend: () -> Unit,
    onStop: () -> Unit,
    onRemoveAttachment: (Int) -> Unit,
    onRemoveQueuedPrompt: (String) -> Unit,
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
            workspaceScope = workspaceScope,
            workspaces = workspaces,
            workspaceError = workspaceError,
            model = model,
            onWorkspaceChange = onWorkspaceChange,
            onModelChange = onModelChange,
            onOpenModelSettings = onOpenModelSettings,
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
            workspaceScope = workspaceScope,
            workspaces = workspaces,
            workspaceError = workspaceError,
            model = model,
            onWorkspaceChange = onWorkspaceChange,
            onModelChange = onModelChange,
            onOpenModelSettings = onOpenModelSettings,
            onTextChange = onTextChange,
            onSelectSlashCommand = onSelectSlashCommand,
            onSelectSkillMention = onSelectSkillMention,
            onSelectCapabilityMention = onSelectCapabilityMention,
            onSend = onSend,
            onStop = onStop,
            onRemoveAttachment = onRemoveAttachment,
            onRemoveQueuedPrompt = onRemoveQueuedPrompt,
            onClearQuote = onClearQuote,
            onPickImages = onPickImages,
            onPickFiles = onPickFiles,
            onOpenConversationList = onOpenConversationList,
        )
    }
}

@Composable
private fun ModelPresetControl(
    model: ChatModelSelection,
    disabled: Boolean,
    onChange: (String) -> Unit,
    onOpenSettings: () -> Unit,
    modifier: Modifier = Modifier,
    compact: Boolean = false,
    heroStyle: Boolean = false,
    heroScale: Float = 1f,
) {
    val options = remember(model.presets) {
        model.presets.filter { preset -> !preset.isDefault && preset.name.isNotBlank() }
    }
    var open by remember { mutableStateOf(false) }

    Row(modifier, horizontalArrangement = Arrangement.End) {
        if (compact) {
            val controlColor = if (heroStyle) {
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
                modifier = Modifier
                    .then(if (heroStyle) Modifier.fillMaxSize() else Modifier)
                    .semantics { contentDescription = model.displayLabel },
            ) {
                Row(
                    modifier = if (heroStyle) {
                        Modifier.fillMaxSize().padding(horizontal = (8f * heroScale).dp)
                    } else {
                        Modifier.widthIn(max = 80.dp).padding(horizontal = 7.dp, vertical = 7.dp)
                    },
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(if (heroStyle) (5f * heroScale).dp else 4.dp),
                ) {
                    if (heroStyle) {
                        Box(
                            modifier = Modifier
                                .size((18f * heroScale).dp)
                                .background(MaterialTheme.colorScheme.tertiaryContainer, CircleShape),
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
                        style = if (heroStyle) {
                            MaterialTheme.typography.labelMedium.copy(
                                fontWeight = FontWeight.SemiBold,
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
                onClick = {
                    if (options.isEmpty()) onOpenSettings() else open = true
                },
                enabled = !disabled,
                label = {
                    Text(
                        model.displayLabel,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                },
                trailingIcon = {
                    Icon(Icons.Rounded.ExpandMore, contentDescription = null, Modifier.size(18.dp))
                },
                modifier = Modifier
                    .widthIn(max = 220.dp)
                    .semantics { contentDescription = model.displayLabel },
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
private fun ModelPresetDialog(
    model: ChatModelSelection,
    disabled: Boolean,
    onChange: (String) -> Unit,
    onOpenSettings: () -> Unit,
    onDismiss: () -> Unit,
) {
    val options = remember(model.presets) {
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
        onDismissRequest = {
            if (model.pendingPreset == null) onDismiss()
        },
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
                            modifier = Modifier
                                .fillMaxWidth()
                                .semantics {
                                    contentDescription = preset.label.ifBlank { preset.name }
                                },
                            color = if (selected) {
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
                                    loading -> CircularProgressIndicator(Modifier.size(20.dp), strokeWidth = 2.dp)
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
                            Icon(Icons.Rounded.Settings, contentDescription = null, Modifier.size(18.dp))
                            Text(stringResource(R.string.model_settings), Modifier.padding(start = 8.dp))
                        }
                    }
                }
            }
        },
        confirmButton = {},
        dismissButton = {
            TextButton(
                onClick = onDismiss,
                enabled = model.pendingPreset == null,
            ) { Text(stringResource(R.string.cancel)) }
        },
    )
}

@Composable
private fun WorkspaceControls(
    scope: WorkspaceScope?,
    catalog: WorkspacesPayload?,
    error: String?,
    isHero: Boolean,
    disabled: Boolean,
    onChange: (WorkspaceScope) -> Unit,
    modifier: Modifier = Modifier,
    compact: Boolean = false,
    heroStyle: Boolean = false,
) {
    val defaultScope = catalog?.defaultScope
    val controls = catalog?.controls
    val selectedProject = selectedProjectScope(scope, defaultScope)
    var projectDialogOpen by remember { mutableStateOf(false) }
    var accessDialogOpen by remember { mutableStateOf(false) }
    var pathDraft by remember { mutableStateOf("") }
    var pathError by remember { mutableStateOf<String?>(null) }

    if (scope == null && defaultScope == null) return

    Row(
        modifier = modifier,
        horizontalArrangement = Arrangement.spacedBy(8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        if (isHero && !compact && defaultScope != null && controls?.canChangeProject != false) {
            val label = selectedProject?.projectName
                ?: selectedProject?.projectPath?.let(::projectNameFromPath)
                ?: stringResource(R.string.workspace_project_placeholder)
            AssistChip(
                onClick = {
                    pathDraft = selectedProject?.projectPath.orEmpty()
                    pathError = null
                    projectDialogOpen = true
                },
                enabled = !disabled,
                label = { Text(label, maxLines = 1, overflow = TextOverflow.Ellipsis) },
                leadingIcon = { Icon(Icons.Rounded.Folder, contentDescription = null, Modifier.size(18.dp)) },
                trailingIcon = { Icon(Icons.Rounded.ExpandMore, contentDescription = null, Modifier.size(18.dp)) },
                modifier = Modifier.weight(1f).semantics {
                    contentDescription = label
                },
            )
        }
        val activeScope = scope ?: defaultScope
        if (activeScope != null) {
            val full = activeScope.accessMode == WorkspaceAccessMode.FULL
            val accessLabel = stringResource(
                if (full) R.string.workspace_access_full_short else R.string.workspace_access_default_short,
            )
            if (compact) {
                val controlColor = if (heroStyle) {
                    MaterialTheme.colorScheme.surfaceContainerHigh
                } else {
                    MaterialTheme.colorScheme.surface
                }
                // FULL 权限仍然保留警示语义，但使用主题 tertiary 角色，不重新引入旧橙色。
                val accentColor = MaterialTheme.colorScheme.tertiary
                val labelColor = if (heroStyle && full) {
                    accentColor
                } else {
                    MaterialTheme.colorScheme.onSurface
                }
                Surface(
                    onClick = { accessDialogOpen = true },
                    enabled = !disabled,
                    shape = CircleShape,
                    color = controlColor,
                    tonalElevation = if (heroStyle) 2.dp else 1.dp,
                    shadowElevation = 0.dp,
                    modifier = Modifier
                        .then(if (heroStyle) Modifier.fillMaxSize() else Modifier)
                        .semantics { contentDescription = accessLabel },
                ) {
                    Row(
                        modifier = if (heroStyle) {
                            Modifier.fillMaxSize().padding(horizontal = 4.dp)
                        } else {
                            Modifier.padding(horizontal = 7.dp, vertical = 7.dp)
                        },
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(3.dp),
                    ) {
                        if (full) {
                            Icon(
                                Icons.Rounded.WarningAmber,
                                contentDescription = null,
                                modifier = Modifier.size(14.dp),
                                tint = if (heroStyle) accentColor else MaterialTheme.colorScheme.tertiary,
                            )
                        }
                        Text(
                            text = if (heroStyle) "$accessLabel …" else accessLabel,
                            maxLines = 1,
                            overflow = TextOverflow.Clip,
                            style = if (heroStyle) {
                                MaterialTheme.typography.labelMedium.copy(
                                    fontWeight = FontWeight.Medium,
                                )
                            } else {
                                MaterialTheme.typography.labelMedium
                            },
                            color = labelColor,
                        )
                        Icon(
                            Icons.Rounded.ExpandMore,
                            contentDescription = null,
                            modifier = Modifier.size(12.dp),
                            tint = if (heroStyle) labelColor else MaterialTheme.colorScheme.onSurface,
                        )
                    }
                }
            } else {
                AssistChip(
                    onClick = { accessDialogOpen = true },
                    enabled = !disabled,
                    label = { Text(accessLabel) },
                    leadingIcon = {
                        if (full) Icon(
                            Icons.Rounded.WarningAmber,
                            contentDescription = null,
                            modifier = Modifier.size(18.dp),
                            tint = MaterialTheme.colorScheme.tertiary,
                        )
                    },
                    trailingIcon = { Icon(Icons.Rounded.ExpandMore, contentDescription = null, Modifier.size(18.dp)) },
                    modifier = Modifier.semantics {
                        contentDescription = accessLabel
                    },
                )
            }
        }
    }

    if (error != null) {
        Text(
            stringResource(R.string.workspace_scope_rejected),
            color = MaterialTheme.colorScheme.error,
            style = MaterialTheme.typography.labelMedium,
        )
    }

    if (projectDialogOpen && defaultScope != null) {
        fun applyPath(path: String, projectName: String? = null) {
            val trimmed = path.trim()
            if (!isAbsoluteWorkspacePath(trimmed)) {
                pathError = "absolute_path_required"
                return
            }
            val base = scope ?: defaultScope
            onChange(
                base.copy(
                    projectPath = trimmed,
                    projectName = projectName ?: projectNameFromPath(trimmed),
                    restrictToWorkspace = base.accessMode == WorkspaceAccessMode.RESTRICTED,
                ),
            )
            pathError = null
            projectDialogOpen = false
        }
        AlertDialog(
            onDismissRequest = { projectDialogOpen = false },
            icon = { Icon(Icons.Rounded.Folder, contentDescription = null) },
            title = { Text(stringResource(R.string.workspace_project_title)) },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                    Surface(
                        onClick = { applyPath(defaultScope.projectPath, defaultScope.projectName) },
                        shape = MaterialTheme.shapes.medium,
                        color = MaterialTheme.colorScheme.surfaceContainerHigh,
                        modifier = Modifier.fillMaxWidth(),
                    ) {
                        Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(2.dp)) {
                            Text(stringResource(R.string.workspace_default_project), style = MaterialTheme.typography.labelLarge)
                            Text(
                                shortWorkspacePath(defaultScope.projectPath),
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis,
                            )
                        }
                    }
                    OutlinedTextField(
                        value = pathDraft,
                        onValueChange = {
                            pathDraft = it
                            pathError = null
                        },
                        modifier = Modifier.fillMaxWidth(),
                        enabled = !disabled,
                        singleLine = true,
                        label = { Text(stringResource(R.string.workspace_manual_path)) },
                        placeholder = { Text(stringResource(R.string.workspace_path_example)) },
                        isError = pathError != null,
                        supportingText = pathError?.let {
                            { Text(stringResource(R.string.workspace_absolute_path_required)) }
                        },
                        keyboardOptions = KeyboardOptions(imeAction = ImeAction.Done),
                        keyboardActions = KeyboardActions(onDone = { applyPath(pathDraft) }),
                    )
                }
            },
            confirmButton = {
                TextButton(
                    enabled = !disabled && pathDraft.isNotBlank(),
                    onClick = { applyPath(pathDraft) },
                ) { Text(stringResource(R.string.workspace_use_path)) }
            },
            dismissButton = {
                TextButton(onClick = { projectDialogOpen = false }) {
                    Text(stringResource(R.string.cancel))
                }
            },
        )
    }

    val activeScope = scope ?: defaultScope
    if (accessDialogOpen && activeScope != null) {
        WorkspaceAccessDialog(
            scope = activeScope,
            controls = controls,
            disabled = disabled,
            onChange = onChange,
            onDismiss = { accessDialogOpen = false },
        )
    }

}

@Composable
private fun WorkspaceAccessDialog(
    scope: WorkspaceScope,
    controls: WorkspaceControls?,
    disabled: Boolean,
    onChange: (WorkspaceScope) -> Unit,
    onDismiss: () -> Unit,
) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(R.string.workspace_access_title)) },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                Surface(
                    onClick = {
                        onChange(scope.withAccessMode(WorkspaceAccessMode.RESTRICTED))
                        onDismiss()
                    },
                    modifier = Modifier.fillMaxWidth(),
                    enabled = !disabled,
                    color = if (scope.accessMode == WorkspaceAccessMode.RESTRICTED) {
                        MaterialTheme.colorScheme.secondaryContainer
                    } else {
                        MaterialTheme.colorScheme.surface
                    },
                    shape = MaterialTheme.shapes.medium,
                ) {
                    Column(Modifier.padding(12.dp)) {
                        Text(stringResource(R.string.workspace_access_default), style = MaterialTheme.typography.labelLarge)
                        Text(
                            stringResource(R.string.workspace_access_default_description),
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                }
                Surface(
                    onClick = {
                        if (controls?.canUseFullAccess != false) {
                            onChange(scope.withAccessMode(WorkspaceAccessMode.FULL))
                            onDismiss()
                        }
                    },
                    modifier = Modifier.fillMaxWidth(),
                    enabled = !disabled && controls?.canUseFullAccess != false,
                    color = if (scope.accessMode == WorkspaceAccessMode.FULL) {
                        MaterialTheme.colorScheme.tertiaryContainer
                    } else {
                        MaterialTheme.colorScheme.surface
                    },
                    shape = MaterialTheme.shapes.medium,
                ) {
                    Column(Modifier.padding(12.dp)) {
                        Text(
                            stringResource(R.string.workspace_access_full),
                            style = MaterialTheme.typography.labelLarge,
                            color = MaterialTheme.colorScheme.tertiary,
                        )
                        Text(
                            stringResource(R.string.workspace_access_full_description),
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                }
            }
        },
        confirmButton = {
            TextButton(onClick = onDismiss) {
                Text(stringResource(R.string.cancel))
            }
        },
    )
}

@Composable
private fun SlashCommandSuggestions(
    commands: List<SlashCommand>,
    onSelect: (SlashCommand) -> Unit,
) {
    Card(
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.surfaceContainerHigh,
        ),
    ) {
        Column(Modifier.fillMaxWidth()) {
            Text(
                text = stringResource(R.string.slash_commands_label),
                modifier = Modifier.padding(horizontal = 16.dp, vertical = 10.dp),
                style = MaterialTheme.typography.labelMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            commands.forEachIndexed { index, command ->
                val commandLabel = listOf(command.command, command.argHint)
                    .filter(String::isNotBlank)
                    .joinToString(" ")
                val supportingText = command.description.ifBlank { command.title }
                val accessibilityLabel = stringResource(
                    R.string.slash_command_suggestion,
                    commandLabel,
                    supportingText,
                )
                if (index > 0) HorizontalDivider()
                Surface(
                    onClick = { onSelect(command) },
                    color = MaterialTheme.colorScheme.surfaceContainerHigh,
                    modifier = Modifier
                        .fillMaxWidth()
                        .semantics { contentDescription = accessibilityLabel },
                ) {
                    Column(
                        modifier = Modifier.padding(horizontal = 16.dp, vertical = 10.dp),
                        verticalArrangement = Arrangement.spacedBy(2.dp),
                    ) {
                        Text(
                            text = commandLabel,
                            style = MaterialTheme.typography.labelLarge,
                            color = MaterialTheme.colorScheme.primary,
                        )
                        if (supportingText.isNotBlank()) {
                            Text(
                                text = supportingText,
                                maxLines = 2,
                                overflow = TextOverflow.Ellipsis,
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun SkillMentionSuggestions(
    candidates: List<SkillMentionCandidate>,
    onSelect: (SkillMentionCandidate) -> Unit,
) {
    MentionSuggestionsCard(label = stringResource(R.string.skills_mentions_label)) {
        candidates.forEachIndexed { index, candidate ->
            if (index > 0) HorizontalDivider()
            MentionSuggestionRow(
                primary = candidate.command,
                supporting = candidate.skill.description,
                typeLabel = candidate.skill.source,
                accessibilityLabel = stringResource(
                    R.string.skill_mention_suggestion,
                    candidate.command,
                    candidate.skill.description,
                ),
                onClick = { onSelect(candidate) },
            )
        }
    }
}

@Composable
private fun CapabilityMentionSuggestions(
    candidates: List<CapabilityMentionCandidate>,
    onSelect: (CapabilityMentionCandidate) -> Unit,
) {
    MentionSuggestionsCard(label = stringResource(R.string.capability_mentions_label)) {
        candidates.forEachIndexed { index, candidate ->
            if (index > 0) HorizontalDivider()
            val details = when (candidate) {
                is CapabilityMentionCandidate.Cli -> Triple(
                    candidate.app.displayName,
                    candidate.app.description,
                    stringResource(R.string.capability_type_cli),
                )
                is CapabilityMentionCandidate.Mcp -> Triple(
                    candidate.preset.displayName,
                    candidate.preset.description,
                    stringResource(R.string.capability_type_mcp),
                )
            }
            MentionSuggestionRow(
                primary = "@${candidate.name}",
                supporting = listOf(details.first, details.second)
                    .filter(String::isNotBlank)
                    .distinct()
                    .joinToString(" · "),
                typeLabel = details.third,
                accessibilityLabel = stringResource(
                    R.string.capability_mention_suggestion,
                    candidate.name,
                    details.third,
                    details.second,
                ),
                onClick = { onSelect(candidate) },
            )
        }
    }
}

@Composable
private fun MentionSuggestionsCard(
    label: String,
    content: @Composable () -> Unit,
) {
    Card(
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.surfaceContainerHigh,
        ),
    ) {
        Column(Modifier.fillMaxWidth()) {
            Text(
                text = label,
                modifier = Modifier.padding(horizontal = 16.dp, vertical = 10.dp),
                style = MaterialTheme.typography.labelMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            content()
        }
    }
}

@Composable
private fun MentionSuggestionRow(
    primary: String,
    supporting: String,
    typeLabel: String,
    accessibilityLabel: String,
    onClick: () -> Unit,
) {
    Surface(
        onClick = onClick,
        color = MaterialTheme.colorScheme.surfaceContainerHigh,
        modifier = Modifier
            .fillMaxWidth()
            .semantics { contentDescription = accessibilityLabel },
    ) {
        Row(
            modifier = Modifier.padding(horizontal = 16.dp, vertical = 10.dp),
            horizontalArrangement = Arrangement.spacedBy(12.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
                Text(
                    text = primary,
                    style = MaterialTheme.typography.labelLarge,
                    color = MaterialTheme.colorScheme.primary,
                )
                if (supporting.isNotBlank()) {
                    Text(
                        text = supporting,
                        maxLines = 2,
                        overflow = TextOverflow.Ellipsis,
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
            if (typeLabel.isNotBlank()) {
                Text(
                    text = typeLabel,
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSecondaryContainer,
                    modifier = Modifier
                        .background(
                            MaterialTheme.colorScheme.secondaryContainer,
                            MaterialTheme.shapes.small,
                        )
                        .padding(horizontal = 8.dp, vertical = 4.dp),
                )
            }
        }
    }
}

@Composable
private fun queuedPromptPreview(prompt: QueuedPrompt): String = when {
    prompt.text.isNotBlank() -> prompt.text.trim()
    !prompt.quotedContext.isNullOrBlank() -> prompt.quotedContext.trim()
    prompt.attachments.isNotEmpty() -> stringResource(R.string.queued_attachment_count, prompt.attachments.size)
    else -> stringResource(R.string.queued_prompt_fallback)
}
