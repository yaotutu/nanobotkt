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
import androidx.compose.foundation.interaction.collectIsDraggedAsState
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
import androidx.compose.runtime.snapshotFlow
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
    val timelineItems =
        remember(state.messages, state.activeTurnId) {
            buildChatTimelineItems(state.messages, activeTurnId = state.activeTurnId)
        }
    val isUserDragging by listState.interactionSource.collectIsDraggedAsState()
    var jumpTargetId by remember { mutableStateOf<String?>(null) }
    var autoFollow by remember { mutableStateOf(true) }

    LaunchedEffect(state.sessionKey) {
        // 新会话从底部开始；旧会话的手动浏览状态不能泄漏到新会话。
        autoFollow = true
    }
    LaunchedEffect(listState, state.sessionKey, isUserDragging) {
        snapshotFlow { isUserDragging to listState.canScrollForward }
            .collect { (dragging, canScrollForward) ->
                if (dragging) {
                    // isScrollInProgress 同时包含程序化滚动；若用它判断，首次自动定位和点击“新消息”
                    // 也会错误关闭 auto-follow。interactionSource 只表示用户手指拖动，因此只有用户
                    // 真正离开尾部时才暂停跟随；用户在底部轻微拖动但没有离开时仍保持跟随。
                    autoFollow = !canScrollForward
                }
            }
    }

    LaunchedEffect(jumpTargetId, timelineItems, state.hasMoreBefore) {
        jumpTargetId?.let { targetId ->
            autoFollow = false
            val timelineIndex =
                timelineItems.indexOfFirst {
                    it is ChatTimelineItem.UserMessage && it.message.id == targetId
                }
            if (timelineIndex >= 0) {
                val headerOffset = if (state.hasMoreBefore) 1 else 0
                listState.animateScrollToItem(headerOffset + timelineIndex)
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
                    timelineItems = timelineItems,
                    loadOlder = viewModel::loadOlder,
                    onQuote = { quoteDraft = normalizeQuotedContext(it) },
                    onPreview = viewModel::previewFile,
                    onFork = { messageId, beforeUserIndex ->
                        viewModel.fork(messageId, beforeUserIndex, forkTitle, onSessionCreated)
                    },
                    resolveMediaUrl = viewModel::resolveMediaUrl,
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
                        val headerOffset = if (state.hasMoreBefore) 1 else 0
                        val lastIndex = headerOffset + timelineItems.lastIndex
                        if (lastIndex >= 0) {
                            autoFollow = true
                            coroutineScope.launch {
                                // 主工作区已将原始消息映射为时间轴单元；这里必须使用相同索引体系，
                                // 并复用可处理超长正文的底部定位函数，避免跳到错误消息或只到单元顶部。
                                listState.scrollToTimelineBottom(lastIndex)
                            }
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
