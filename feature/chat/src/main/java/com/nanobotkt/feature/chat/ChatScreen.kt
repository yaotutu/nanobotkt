package com.nanobotkt.feature.chat

import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.PickVisualMediaRequest
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.Toc
import androidx.compose.material.icons.rounded.ChatBubbleOutline
import androidx.compose.material.icons.rounded.Checklist
import androidx.compose.material.icons.rounded.DarkMode
import androidx.compose.material.icons.rounded.Menu
import androidx.compose.material.icons.rounded.MoreVert
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.nanobotkt.core.designsystem.NanobotThemeDefaults
import kotlinx.coroutines.launch

/** Chat 页面组合入口与顶部状态区域。复杂消息和输入组件按职责放在同包文件中。 */
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
    val imagePicker =
        rememberLauncherForActivityResult(ActivityResultContracts.PickMultipleVisualMedia(8)) {
            viewModel.addAttachments(it)
        }
    val filePicker =
        rememberLauncherForActivityResult(ActivityResultContracts.OpenMultipleDocuments()) {
            viewModel.addAttachments(it)
        }

    LaunchedEffect(state.sessionKey) { state.sessionKey?.let(onSessionCreated) }

    LaunchedEffect(state.error, state.model.error, composer.error) {
        (composer.error ?: state.model.error ?: state.error)?.let { snackbar.showSnackbar(it) }
    }

    LaunchedEffect(state.sessionKey) {
        promptNavigatorOpen = false
        sessionInfoOpen = false
    }

    val hasUserPrompts = remember(state.messages) { state.messages.any { it.role == "user" } }
    val listState = rememberLazyListState()
    val coroutineScope = rememberCoroutineScope()
    var jumpTargetId by remember { mutableStateOf<String?>(null) }
    var autoFollow by remember { mutableStateOf(true) }

    LaunchedEffect(jumpTargetId) {
        jumpTargetId?.let { targetId ->
            autoFollow = false
            val index = state.messages.indexOfFirst { it.role == "user" && it.id == targetId }
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
                ) {
                    Text(stringResource(R.string.add_context))
                }
            },
            dismissButton = {
                TextButton(onClick = { quoteDraft = null }) {
                    Text(stringResource(R.string.cancel))
                }
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
                imagePicker.launch(
                    PickVisualMediaRequest(ActivityResultContracts.PickVisualMedia.ImageOnly)
                )
            },
            onPickFiles = { filePicker.launch(arrayOf("*/*")) },
            onOpenConversationList = onOpenConversationList,
        )
    }

    // 页面骨架固定为“顶部状态栏 + 中间消息区 + 底部 Composer”。
    // Composer 不再覆盖消息列表，因此消息区只需要负责自己的滚动和跳转。
    Column(modifier = modifier.fillMaxSize().background(MaterialTheme.colorScheme.background)) {
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

        Box(modifier = Modifier.weight(1f).fillMaxWidth()) {
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
                        modifier =
                            Modifier.align(Alignment.BottomEnd)
                                .padding(end = spacing.md, bottom = spacing.sm),
                    ) {
                        Text("↓ ${stringResource(R.string.jump_to_latest_messages)}")
                    }
                }
            }

            SnackbarHost(
                hostState = snackbar,
                modifier =
                    Modifier.align(Alignment.BottomCenter)
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
internal fun HeroTopBar(
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
        overflowItems =
            listOf(
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
internal fun ConversationTopBar(
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
    val actions =
        listOfNotNull(
            hasPromptNavigator
                .takeIf { it }
                ?.let {
                    TopBarAction(
                        label = stringResource(R.string.prompt_navigator_open),
                        icon = Icons.Rounded.Checklist,
                        onClick = onOpenPromptNavigator,
                    )
                },
            hasSessionInfo
                .takeIf { it }
                ?.let {
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

internal data class TopBarAction(
    val label: String,
    val icon: androidx.compose.ui.graphics.vector.ImageVector,
    val onClick: () -> Unit,
)

@Composable
internal fun TopStatusBar(
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
        modifier =
            Modifier.fillMaxWidth()
                .background(surface)
                .statusBarsPadding()
                .height(64.dp)
                .padding(horizontal = 4.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        IconButton(onClick = onOpenDrawer, modifier = Modifier.size(48.dp)) {
            Icon(
                Icons.Rounded.Menu,
                stringResource(R.string.open_navigation),
                modifier = Modifier.size(20.dp),
                tint = muted,
            )
        }
        Column(modifier = Modifier.weight(1f).padding(horizontal = 8.dp)) {
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
            IconButton(onClick = { menuOpen = true }, modifier = Modifier.size(48.dp)) {
                Icon(
                    Icons.Rounded.MoreVert,
                    contentDescription = stringResource(R.string.more_actions),
                    modifier = Modifier.size(22.dp),
                    tint = muted,
                )
            }
            DropdownMenu(expanded = menuOpen, onDismissRequest = { menuOpen = false }) {
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
internal fun StatusLabel(text: String, color: Color, containerColor: Color) {
    Surface(shape = MaterialTheme.shapes.small, color = containerColor, contentColor = color) {
        Text(
            text = text,
            modifier = Modifier.padding(horizontal = 8.dp, vertical = 2.dp),
            style = MaterialTheme.typography.labelSmall,
            maxLines = 1,
        )
    }
}

@Composable
internal fun EmptyChat(modifier: Modifier = Modifier) {
    BoxWithConstraints(modifier = modifier) {
        Text(
            text = stringResource(R.string.empty_title),
            modifier = Modifier.offset(x = 16.dp, y = maxHeight * 0.394f),
            color = MaterialTheme.colorScheme.onBackground,
            style = MaterialTheme.typography.displaySmall,
            maxLines = 1,
        )
    }
}
