package com.nanobotkt.feature.chat

import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.PickVisualMediaRequest
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
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
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
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
    transportStatus: TransportStatus,
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
    var modelDialogOpen by remember { mutableStateOf(false) }
    var accessDialogOpen by remember { mutableStateOf(false) }
    var queueOpen by remember { mutableStateOf(false) }
    var configMenuOpen by remember { mutableStateOf(false) }
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
            onOpenConversationList = onOpenConversationList,
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
