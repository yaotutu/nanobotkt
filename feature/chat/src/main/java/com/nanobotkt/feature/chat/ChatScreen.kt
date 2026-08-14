package com.nanobotkt.feature.chat

import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.PickVisualMediaRequest
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.expandVertically
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.shrinkVertically
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.animateScrollBy
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.KeyboardArrowDown
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.focusProperties
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.nanobotkt.core.designsystem.NanobotThemeDefaults
import com.nanobotkt.core.transport.TransportStatus
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
    transportStatus: TransportStatus,
    onOpenConversationList: () -> Unit = {},
    conversationItems: List<ConversationListItem> = emptyList(),
    archivedConversationItems: List<ConversationListItem> = emptyList(),
    selectedConversationKey: String? = null,
    onSelectConversation: (ConversationListItem) -> Unit = {},
    onNewConversation: () -> Unit = {},
    onToggleConversationPinned: (String) -> Unit = {},
    onRenameConversation: (ConversationListItem, String) -> Unit = { _, _ -> },
    onArchiveConversation: (String) -> Unit = {},
    onDeleteConversation: (ConversationListItem) -> Unit = {},
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
    var modelDialogOpen by remember { mutableStateOf(false) }
    var accessDialogOpen by remember { mutableStateOf(false) }
    var queueOpen by remember { mutableStateOf(false) }
    var configMenuOpen by remember { mutableStateOf(false) }
    // 这是纯 UI 临时状态，不进入 AppViewModel/SavedStateHandle；会话业务选择仍由 Root
    // 的 SessionSelection 负责，避免把 Bottom Sheet 的动画生命周期混入会话竞态保护。
    var conversationSheetOpen by rememberSaveable { mutableStateOf(false) }
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
        // Queue 和会话配置弹层都绑定当前会话；切换时统一关闭，避免旧会话状态覆盖到
        // 新会话之上，也避免用户误把旧配置操作应用到新会话。附件菜单不携带会话数据。
        promptNavigatorOpen = false
        sessionInfoOpen = false
        modelDialogOpen = false
        accessDialogOpen = false
        queueOpen = false
        configMenuOpen = false
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
            isHero = hero,
            onTextChange = viewModel::updateText,
            onSelectSlashCommand = viewModel::selectSlashCommand,
            onSelectSkillMention = viewModel::selectSkillMention,
            onSelectCapabilityMention = viewModel::selectCapabilityMention,
            onSend = viewModel::send,
            onStop = viewModel::stop,
            onRemoveAttachment = viewModel::removeAttachment,
            onClearQuote = viewModel::clearQuotedContext,
            onPickImages = {
                imagePicker.launch(
                    PickVisualMediaRequest(ActivityResultContracts.PickVisualMedia.ImageOnly)
                )
            },
            onPickFiles = { filePicker.launch(arrayOf("*/*")) },
            onOpenConversationList = {
                // 会话入口现在始终打开同一聊天页内的 Sheet；不再根据数据是否为空切换
                // destination，避免“还没有会话”时错误地回到旧独立页面。
                conversationSheetOpen = true
            },
        )
    }

    val headerStatus =
        resolveChatHeaderStatus(
            transportStatus = transportStatus,
            hasError = state.error != null || state.model.error != null || composer.error != null,
            active = state.activeTurnId != null,
        )
    val activeWorkspaceScope = state.workspaceScope ?: state.workspaces?.defaultScope

    // 页面骨架固定为“顶部状态栏 + 中间消息区 + 底部 Composer”。
    // Composer 不再覆盖消息列表，因此消息区只需要负责自己的滚动和跳转。
    Column(modifier = modifier.fillMaxSize().background(MaterialTheme.colorScheme.background)) {
        ChatTopStatusBar(
            title = title,
            status = headerStatus,
            queuedPrompts = composer.queuedPrompts,
            queueOpen = queueOpen,
            configMenuOpen = configMenuOpen,
            hasPromptNavigator = state.sessionKey != null && hasUserPrompts,
            hasSessionInfo = state.sessionKey != null,
            hasAccessSettings = activeWorkspaceScope != null,
            onOpenDrawer = onOpenDrawer,
            onQueueOpenChange = { queueOpen = it },
            onConfigMenuOpenChange = { configMenuOpen = it },
            onRemoveQueuedPrompt = viewModel::removeQueuedPrompt,
            onOpenPromptNavigator = { promptNavigatorOpen = true },
            onOpenSessionInfo = { sessionInfoOpen = true },
            // 顶部菜单中的会话入口必须和 Composer 左侧入口共用同一个本地 Sheet 状态，
            // 否则用户从右上角进入时会落回旧 destination，表现为按钮无响应或功能缺失。
            onOpenConversationList = { conversationSheetOpen = true },
            onToggleTheme = onToggleTheme,
            onOpenModel = { modelDialogOpen = true },
            onOpenAccess = { accessDialogOpen = true },
        )

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
            }

            SnackbarHost(
                hostState = snackbar,
                modifier =
                    Modifier.align(Alignment.BottomCenter)
                        .padding(horizontal = spacing.md, vertical = spacing.sm),
            )
        }

        AnimatedVisibility(
            visible = !state.loading && !hero && listState.canScrollForward,
            enter = fadeIn() + expandVertically(expandFrom = Alignment.Bottom),
            exit = fadeOut() + shrinkVertically(shrinkTowards = Alignment.Bottom),
            modifier = Modifier.fillMaxWidth(),
        ) {
            // 快捷入口拥有独立布局高度，位于消息区和 Composer 之间。相比原先覆盖在 LazyColumn
            // 右下角的裸文字，这种布局不会遮挡消息正文，也不会和 Snackbar 争抢同一锚点。
            Box(
                modifier =
                    Modifier.fillMaxWidth()
                        .padding(horizontal = spacing.md, vertical = spacing.xxs),
                contentAlignment = Alignment.Center,
            ) {
                JumpToLatestMessagesButton(
                    onClick = {
                        // 从 Prompt Navigator 跳转历史消息时会暂停自动跟随；用户主动回到最新消息
                        // 后必须恢复该状态，后续新增或流式消息才能继续自然跟随列表尾部。
                        autoFollow = true
                        coroutineScope.launch {
                            listState.animateScrollToBottom(state.messages.lastIndex)
                        }
                    },
                )
            }
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

    ConversationListSheet(
        items = conversationItems,
        archivedItems = archivedConversationItems,
        selectedKey = selectedConversationKey,
        visible = conversationSheetOpen,
        onDismiss = { conversationSheetOpen = false },
        onSelect = { item ->
            conversationSheetOpen = false
            onSelectConversation(item)
        },
        onNewTopic = {
            conversationSheetOpen = false
            onNewConversation()
        },
        onTogglePinned = onToggleConversationPinned,
        onRename = onRenameConversation,
        onArchive = onArchiveConversation,
        onDelete = onDeleteConversation,
    )

    if (modelDialogOpen) {
        ModelPresetDialog(
            model = state.model,
            disabled = state.activeTurnId != null || composer.sending,
            onChange = viewModel::changeModelPreset,
            onOpenSettings = onOpenModelSettings,
            onDismiss = { modelDialogOpen = false },
        )
    }

    if (accessDialogOpen && activeWorkspaceScope != null) {
        WorkspaceAccessDialog(
            scope = activeWorkspaceScope,
            controls = state.workspaces?.controls,
            disabled = state.activeTurnId != null || composer.sending,
            onChange = viewModel::setWorkspaceScope,
            onDismiss = { accessDialogOpen = false },
        )
    }
}

/**
 * 显示“回到最新消息”的明确按钮容器。
 *
 * FilledTonalButton 在浅色和深色主题下都使用 Material 语义色，同时默认提供可点击语义；额外的
 * 48dp 最小高度保证键盘弹出、可用空间变小时仍保留合格的触控目标。
 */
@Composable
private fun JumpToLatestMessagesButton(
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val spacing = NanobotThemeDefaults.spacing
    FilledTonalButton(
        onClick = onClick,
        modifier =
            modifier
                .heightIn(min = spacing.touchTarget)
                // 输入框获得焦点且 IME 打开时，按钮不抢占 Compose 焦点，避免键盘在按下与
                // 抬起之间收起并触发布局位移，从而保证一次触摸能够稳定执行滚动动作。
                .focusProperties { canFocus = false },
        shape = MaterialTheme.shapes.extraLarge,
        contentPadding = PaddingValues(horizontal = spacing.md, vertical = spacing.xs),
    ) {
        // 按钮文字已经向 TalkBack 说明动作，图标仅作为方向提示，避免重复朗读。
        Icon(
            imageVector = Icons.Rounded.KeyboardArrowDown,
            contentDescription = null,
            modifier = Modifier.size(20.dp),
        )
        Spacer(Modifier.width(spacing.xs))
        Text(
            text = stringResource(R.string.jump_to_latest_messages),
            style = MaterialTheme.typography.labelLarge,
        )
    }
}

/**
 * 将列表真正滚到内容底部，而不只是把最后一条消息的顶部滚入视口。
 *
 * `animateScrollToItem(lastIndex)` 对超长的最后一条消息只会先定位到该消息顶部，因此仍可能留下
 * 可继续向下滚动的正文。定位完成后再按“最后一项高度 + 一个视口高度”补滚，LazyColumn 会在真实
 * 边界自动截断距离，从而兼容普通消息、超长消息和列表底部 content padding。
 */
private suspend fun LazyListState.animateScrollToBottom(lastIndex: Int) {
    if (lastIndex < 0) return

    animateScrollToItem(lastIndex)
    if (!canScrollForward) return

    val lastItem = layoutInfo.visibleItemsInfo.firstOrNull { it.index == lastIndex } ?: return
    animateScrollBy(lastItem.size.toFloat() + layoutInfo.viewportSize.height)
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
