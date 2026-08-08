package com.nanobotkt.feature.chat

import android.Manifest
import android.content.ClipData
import android.content.ClipboardManager
import android.content.pm.PackageManager
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.PickVisualMediaRequest
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
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
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Add
import androidx.compose.material.icons.rounded.ArrowUpward
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
import androidx.compose.material.icons.rounded.Mic
import androidx.compose.material.icons.rounded.AccountTree
import androidx.compose.material.icons.rounded.Checklist
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
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
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
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.onClick
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.TextRange
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.TextFieldValue
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.luminance
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.nanobotkt.core.model.CliAppInfo
import com.nanobotkt.core.model.FilePreviewPayload
import com.nanobotkt.core.model.McpPresetInfo
import com.nanobotkt.core.model.SkillSummary
import com.nanobotkt.core.model.SlashCommand
import com.nanobotkt.core.model.UiMessage
import com.nanobotkt.core.model.WorkspaceAccessMode
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
    onSessionCreated: (String) -> Unit = {},
    modifier: Modifier = Modifier,
) {
    val state by viewModel.state.collectAsStateWithLifecycle()
    val composer by viewModel.composer.collectAsStateWithLifecycle()
    val snackbar = remember { SnackbarHostState() }
    val context = LocalContext.current
    val forkTitle = stringResource(R.string.fork_title, title)
    var quoteDraft by remember { mutableStateOf<String?>(null) }
    var promptNavigatorOpen by remember { mutableStateOf(false) }
    var sessionInfoOpen by remember { mutableStateOf(false) }
    var microphoneGranted by remember {
        mutableStateOf(
            context.checkSelfPermission(Manifest.permission.RECORD_AUDIO) == PackageManager.PERMISSION_GRANTED,
        )
    }
    val microphonePermission = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestPermission(),
    ) { granted ->
        microphoneGranted = granted
        if (!granted) viewModel.startVoiceRecording(permissionGranted = false)
    }
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
            onVoiceStart = {
                if (microphoneGranted) {
                    viewModel.startVoiceRecording(permissionGranted = true)
                } else {
                    microphonePermission.launch(Manifest.permission.RECORD_AUDIO)
                }
            },
            onVoiceStop = viewModel::stopVoiceRecording,
        )
    }

    Box(modifier = modifier.fillMaxSize()) {
        if (hero) {
            val dark = MaterialTheme.colorScheme.background.luminance() < 0.5f
            val heroBackground = if (dark) Color(0xFF101113) else Color.White
            Box(
                modifier = Modifier
                    .fillMaxSize()
                    .background(heroBackground),
            ) {
                if (state.loading) {
                    CircularProgressIndicator(
                        modifier = Modifier.align(Alignment.Center),
                        color = if (dark) Color.White else Color(0xFF33343A),
                    )
                } else {
                    EmptyChat(Modifier.fillMaxSize())
                    Box(
                        modifier = Modifier
                            .fillMaxWidth()
                            .align(Alignment.BottomCenter),
                    ) {
                        composerContent()
                    }
                }
                HeroTopBar(
                    onOpenDrawer = onOpenDrawer,
                    onToggleTheme = onToggleTheme,
                    dark = dark,
                    modifier = Modifier.align(Alignment.TopCenter),
                )
                SnackbarHost(
                    hostState = snackbar,
                    modifier = Modifier
                        .align(Alignment.BottomCenter)
                        .navigationBarsPadding()
                        .padding(horizontal = 16.dp, vertical = 184.dp),
                )
            }
        } else {
            val dark = MaterialTheme.colorScheme.background.luminance() < 0.5f
            val conversationBackground = if (dark) Color(0xFF101113) else Color.White
            Box(
                modifier = Modifier
                    .fillMaxSize()
                    .background(conversationBackground),
            ) {
                if (state.loading) {
                    CircularProgressIndicator(
                        modifier = Modifier.align(Alignment.Center),
                        color = if (dark) Color.White else Color(0xFF33343A),
                    )
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
                        modifier = Modifier
                            .fillMaxSize()
                            // 顶部工具栏悬浮在消息列表上方，列表需要同时避开工具栏和状态栏。
                            .padding(top = 44.dp)
                            .statusBarsPadding(),
                        autoFollow = autoFollow,
                    )
                    Box(
                        modifier = Modifier
                            .fillMaxWidth()
                            .align(Alignment.BottomCenter),
                    ) {
                        composerContent()
                    }
                }
                ConversationTopBar(
                    hasPromptNavigator = state.sessionKey != null && hasUserPrompts,
                    hasSessionInfo = state.sessionKey != null,
                    onOpenDrawer = onOpenDrawer,
                    onOpenPromptNavigator = { promptNavigatorOpen = true },
                    onOpenSessionInfo = { sessionInfoOpen = true },
                    onToggleTheme = onToggleTheme,
                    dark = dark,
                    modifier = Modifier.align(Alignment.TopCenter),
                )
                SnackbarHost(
                    hostState = snackbar,
                    modifier = Modifier
                        .align(Alignment.BottomCenter)
                        .navigationBarsPadding()
                        .padding(horizontal = 16.dp, vertical = 142.dp),
                )
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
}

@Composable
private fun HeroTopBar(
    onOpenDrawer: () -> Unit,
    onToggleTheme: () -> Unit,
    dark: Boolean,
    modifier: Modifier = Modifier,
) {
    val iconTint = if (dark) Color(0xFFB8B8BA) else Color(0xFF777779)
    Row(
        modifier = modifier
            .fillMaxWidth()
            .statusBarsPadding()
            .height(44.dp)
            .padding(horizontal = 6.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        IconButton(
            onClick = onOpenDrawer,
            modifier = Modifier.size(40.dp),
        ) {
            Icon(
                Icons.Rounded.Menu,
                stringResource(R.string.open_navigation),
                modifier = Modifier.size(16.dp),
                tint = iconTint,
            )
        }
        Spacer(Modifier.weight(1f))
        IconButton(
            onClick = onToggleTheme,
            modifier = Modifier.size(40.dp),
        ) {
            Icon(
                Icons.Rounded.DarkMode,
                contentDescription = "Toggle theme",
                modifier = Modifier.size(17.dp),
                tint = iconTint,
            )
        }
    }
}


@Composable
private fun ConversationTopBar(
    hasPromptNavigator: Boolean,
    hasSessionInfo: Boolean,
    onOpenDrawer: () -> Unit,
    onOpenPromptNavigator: () -> Unit,
    onOpenSessionInfo: () -> Unit,
    onToggleTheme: () -> Unit,
    dark: Boolean,
    modifier: Modifier = Modifier,
) {
    val iconTint = if (dark) Color(0xFFB8B8BA) else Color(0xFF777779)
    val background = if (dark) Color(0xFF101113) else Color.White
    Row(
        modifier = modifier
            .fillMaxWidth()
            .statusBarsPadding()
            .height(44.dp)
            .background(background)
            .padding(start = 6.dp, end = 10.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        IconButton(onClick = onOpenDrawer, modifier = Modifier.size(40.dp)) {
            Icon(
                Icons.Rounded.Menu,
                stringResource(R.string.open_navigation),
                modifier = Modifier.size(16.dp),
                tint = iconTint,
            )
        }
        Text(text = "😊", modifier = Modifier.padding(start = 5.dp), fontSize = 12.sp)
        Spacer(Modifier.weight(1f))
        if (hasPromptNavigator) {
            IconButton(onClick = onOpenPromptNavigator, modifier = Modifier.size(36.dp)) {
                Icon(
                    Icons.Rounded.Checklist,
                    stringResource(R.string.prompt_navigator_open),
                    modifier = Modifier.size(17.dp),
                    tint = iconTint,
                )
            }
        }
        if (hasSessionInfo) {
            IconButton(onClick = onOpenSessionInfo, modifier = Modifier.size(36.dp)) {
                Icon(
                    Icons.AutoMirrored.Rounded.Toc,
                    stringResource(R.string.session_info_title),
                    modifier = Modifier.size(17.dp),
                    tint = iconTint,
                )
            }
        }
        IconButton(onClick = onToggleTheme, modifier = Modifier.size(36.dp)) {
            Icon(
                Icons.Rounded.DarkMode,
                contentDescription = "Toggle theme",
                modifier = Modifier.size(17.dp),
                tint = iconTint,
            )
        }
    }
}
@Composable
private fun EmptyChat(modifier: Modifier = Modifier) {
    val dark = MaterialTheme.colorScheme.background.luminance() < 0.5f
    BoxWithConstraints(modifier = modifier) {
        val titleSize = (maxWidth.value * 0.09f).coerceIn(28f, 36f)
        Text(
            text = stringResource(R.string.empty_title),
            modifier = Modifier.offset(
                x = 14.dp,
                y = maxHeight * 0.394f,
            ),
            color = if (dark) Color(0xFFF4F4F5) else Color(0xFF151515),
            style = MaterialTheme.typography.headlineMedium.copy(
                fontWeight = FontWeight.Normal,
                fontSize = titleSize.sp,
                lineHeight = (titleSize * 1.18f).sp,
                letterSpacing = 0.sp,
            ),
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
        contentPadding = PaddingValues(start = 12.dp, top = 20.dp, end = 12.dp, bottom = 144.dp),
        verticalArrangement = Arrangement.spacedBy(18.dp),
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
    val dark = MaterialTheme.colorScheme.background.luminance() < 0.5f
    val textColor = if (dark) Color(0xFFF2F2F3) else Color(0xFF151517)
    val mutedColor = if (dark) Color(0xFF9A9A9E) else Color(0xFF858589)
    val userBubbleColor = if (dark) Color(0xFF202125) else Color(0xFFF7F7F7)
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
                    shape = RoundedCornerShape(20.dp),
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
                            color = textColor,
                            style = MaterialTheme.typography.bodyLarge.copy(
                                fontSize = 16.sp,
                                lineHeight = 24.sp,
                            ),
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
                    style = MaterialTheme.typography.bodyMedium.copy(
                        fontSize = 14.sp,
                        lineHeight = 20.sp,
                    ),
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
                style = MaterialTheme.typography.bodyLarge.copy(
                    fontSize = 16.sp,
                    lineHeight = 25.sp,
                ),
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
                    color = Color(0xFFFF5A2A),
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
                        style = MaterialTheme.typography.labelSmall.copy(fontSize = 12.sp),
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
                        shape = RoundedCornerShape(8.dp),
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
    onVoiceStart: () -> Unit,
    onVoiceStop: (cancelled: Boolean, maxReached: Boolean) -> Unit,
) {
    val hasDraft = state.text.isNotBlank() || state.attachments.isNotEmpty() || !state.quotedContext.isNullOrBlank()
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
    var attachmentMenuOpen by remember { mutableStateOf(false) }
    val dark = MaterialTheme.colorScheme.background.luminance() < 0.5f
    val cardColor = if (dark) Color(0xFF1A1B1E) else Color(0xFFFAFAFA)
    val controlColor = if (dark) Color(0xFF292A2E) else Color.White
    val textColor = if (dark) Color(0xFFF4F4F5) else Color(0xFF171719)
    val mutedColor = if (dark) Color(0xFFA8A8AB) else Color(0xFF949496)
    val sendColor = when {
        stopButton || (sendEnabled && hasDraft) -> if (dark) Color(0xFFE4E4E5) else Color(0xFF3C3C40)
        else -> if (dark) Color(0xFF6F7074) else Color(0xFF929295)
    }
    val sendContentColor = if (dark && sendEnabled) Color(0xFF18191B) else Color.White

    BoxWithConstraints(
        modifier = Modifier
            .fillMaxWidth()
            .navigationBarsPadding()
            .imePadding(),
    ) {
        val scale = (maxWidth.value / 400f).coerceIn(0.8f, 1f)
        val cardHeight = (152f * scale).dp
        val widthProgress = (maxWidth.value - 320f).coerceIn(0f, 80f)
        val workspaceWidth = (72f + widthProgress * 0.3f).dp
        val modelWidth = (82f + widthProgress * 0.1f).dp
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = (12f * scale).dp)
                .padding(bottom = (32f * scale).dp),
            verticalArrangement = Arrangement.spacedBy((6f * scale).dp),
        ) {
            when {
                slashSuggestions.isNotEmpty() -> SlashCommandSuggestions(slashSuggestions, onSelectSlashCommand)
                skillSuggestions.isNotEmpty() -> SkillMentionSuggestions(skillSuggestions, onSelectSkillMention)
                capabilitySuggestions.isNotEmpty() -> CapabilityMentionSuggestions(
                    capabilitySuggestions,
                    onSelectCapabilityMention,
                )
            }
            if (state.attachments.isNotEmpty() || state.encodingCount > 0) {
                Row(horizontalArrangement = Arrangement.spacedBy(6.dp), modifier = Modifier.fillMaxWidth()) {
                    state.attachments.take(3).forEachIndexed { index, attachment ->
                        AssistChip(
                            onClick = { onRemoveAttachment(index) },
                            label = { Text(attachment.name, maxLines = 1, overflow = TextOverflow.Ellipsis) },
                            trailingIcon = { Icon(Icons.Rounded.Close, contentDescription = null, Modifier.size(16.dp)) },
                        )
                    }
                    if (state.encodingCount > 0) {
                        CircularProgressIndicator(Modifier.size(22.dp), strokeWidth = 2.dp)
                    }
                }
            }
            VoiceStatus(state.voice)
            Surface(
                modifier = Modifier
                    .fillMaxWidth()
                    .height(cardHeight),
                shape = RoundedCornerShape((30f * scale).dp),
                color = cardColor,
                tonalElevation = 0.dp,
                shadowElevation = (4f * scale).dp,
            ) {
                Column(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(
                            start = (12f * scale).dp,
                            top = (26f * scale).dp,
                            end = (12f * scale).dp,
                            bottom = (25f * scale).dp,
                        ),
                ) {
                    BasicTextField(
                        value = TextFieldValue(
                            text = state.text,
                            selection = TextRange(state.cursorPosition.coerceIn(0, state.text.length)),
                        ),
                        onValueChange = { value -> onTextChange(value.text, value.selection.end) },
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(start = (4f * scale).dp)
                            .heightIn(min = (40f * scale).dp, max = (58f * scale).dp),
                        enabled = !state.voice.isRecording && !state.voice.isTranscribing && !state.sending,
                        textStyle = MaterialTheme.typography.bodyLarge.copy(
                            color = textColor,
                            fontSize = (16f * scale).coerceAtLeast(14f).sp,
                            lineHeight = (23f * scale).coerceAtLeast(20f).sp,
                        ),
                        keyboardOptions = KeyboardOptions(imeAction = ImeAction.Send),
                        keyboardActions = KeyboardActions(onSend = { if (hasDraft && !state.sending) onSend() }),
                        maxLines = 6,
                        decorationBox = { innerTextField ->
                            Box(Modifier.fillMaxWidth()) {
                                if (state.text.isEmpty()) {
                                    Text(
                                        text = "Ask anything...",
                                        color = mutedColor,
                                        style = MaterialTheme.typography.bodyLarge.copy(
                                            fontSize = (16f * scale).coerceAtLeast(14f).sp,
                                            lineHeight = (23f * scale).coerceAtLeast(20f).sp,
                                        ),
                                    )
                                }
                                innerTextField()
                            }
                        },
                    )
                    Spacer(Modifier.weight(1f))
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .height((44f * scale).dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Box {
                            Surface(
                                onClick = { attachmentMenuOpen = true },
                                enabled = !state.voice.isRecording && !state.sending,
                                modifier = Modifier.size((44f * scale).dp),
                                shape = CircleShape,
                                color = controlColor,
                                shadowElevation = (2f * scale).dp,
                            ) {
                                Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                                    Icon(
                                        Icons.Rounded.Add,
                                        stringResource(R.string.attach_file),
                                        modifier = Modifier.size((20f * scale).dp),
                                        tint = mutedColor,
                                    )
                                }
                            }
                            DropdownMenu(attachmentMenuOpen, { attachmentMenuOpen = false }) {
                                DropdownMenuItem(
                                    text = { Text(stringResource(R.string.attach_image)) },
                                    leadingIcon = { Icon(Icons.Rounded.Image, null) },
                                    onClick = { attachmentMenuOpen = false; onPickImages() },
                                )
                                DropdownMenuItem(
                                    text = { Text(stringResource(R.string.attach_file)) },
                                    leadingIcon = { Icon(Icons.Rounded.AttachFile, null) },
                                    onClick = { attachmentMenuOpen = false; onPickFiles() },
                                )
                            }
                        }
                        Spacer(Modifier.width((11f * scale).dp))
                        WorkspaceControls(
                            scope = workspaceScope,
                            catalog = workspaces,
                            error = workspaceError,
                            isHero = false,
                            compact = true,
                            heroStyle = true,
                            disabled = active,
                            onChange = onWorkspaceChange,
                            modifier = Modifier
                                .width(workspaceWidth)
                                .height((38f * scale).dp),
                        )
                        Spacer(Modifier.width((10f * scale).dp))
                        ModelPresetControl(
                            model = model,
                            disabled = state.sending || !model.enabled,
                            onChange = onModelChange,
                            onOpenSettings = onOpenModelSettings,
                            compact = true,
                            heroStyle = true,
                            heroScale = scale,
                            modifier = Modifier
                                .width(modelWidth)
                                .height((38f * scale).dp),
                        )
                        Spacer(Modifier.weight(1f))
                        VoiceRecordButton(
                            recording = state.voice.isRecording,
                            enabled = !active && !state.voice.isTranscribing && !state.sending,
                            onStart = onVoiceStart,
                            onStop = onVoiceStop,
                            compact = true,
                            heroStyle = true,
                            modifier = Modifier.size((34f * scale).dp),
                        )
                        Spacer(Modifier.width((12f * scale).dp))
                        Surface(
                            onClick = if (stopButton) onStop else onSend,
                            enabled = sendEnabled,
                            modifier = Modifier.size((44f * scale).dp),
                            shape = CircleShape,
                            color = sendColor,
                            shadowElevation = (2f * scale).dp,
                        ) {
                            Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                                when {
                                    state.sending -> CircularProgressIndicator(
                                        Modifier.size((18f * scale).dp),
                                        strokeWidth = 2.dp,
                                        color = sendContentColor,
                                    )
                                    stopButton -> Icon(
                                        Icons.Rounded.Stop,
                                        stringResource(R.string.stop),
                                        modifier = Modifier.size((20f * scale).dp),
                                        tint = sendContentColor,
                                    )
                                    else -> Icon(
                                        Icons.Rounded.ArrowUpward,
                                        stringResource(R.string.send),
                                        modifier = Modifier.size((21f * scale).dp),
                                        tint = sendContentColor,
                                    )
                                }
                            }
                        }
                    }
                }
            }
        }
    }
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
    onVoiceStart: () -> Unit,
    onVoiceStop: (cancelled: Boolean, maxReached: Boolean) -> Unit,
) {
    val hasDraft = state.text.isNotBlank() || state.attachments.isNotEmpty() || !state.quotedContext.isNullOrBlank()
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
    var attachmentMenuOpen by remember { mutableStateOf(false) }
    val dark = MaterialTheme.colorScheme.background.luminance() < 0.5f
    val cardColor = if (dark) Color(0xFF1A1B1E) else Color(0xFFFAFAFA)
    val controlColor = if (dark) Color(0xFF292A2E) else Color.White
    val textColor = if (dark) Color(0xFFF4F4F5) else Color(0xFF171719)
    val mutedColor = if (dark) Color(0xFFA8A8AB) else Color(0xFF949496)
    val sendColor = when {
        stopButton || (sendEnabled && hasDraft) -> if (dark) Color(0xFFE4E4E5) else Color(0xFF3C3C40)
        else -> if (dark) Color(0xFF6F7074) else Color(0xFF929295)
    }
    val sendContentColor = if (dark && sendEnabled) Color(0xFF18191B) else Color.White

    BoxWithConstraints(
        modifier = Modifier
            .fillMaxWidth()
            .navigationBarsPadding()
            .imePadding(),
    ) {
        val scale = (maxWidth.value / 400f).coerceIn(0.8f, 1f)
        val widthProgress = (maxWidth.value - 320f).coerceIn(0f, 80f)
        val cardHeight = (116f * scale).dp
        val workspaceWidth = (66f + widthProgress * 0.15f).dp
        val modelWidth = (76f + widthProgress * 0.2375f).dp
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 16.dp)
                .padding(bottom = (12f * scale).dp),
            verticalArrangement = Arrangement.spacedBy((6f * scale).dp),
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
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(6.dp),
                ) {
                    state.queuedPrompts.take(2).forEach { prompt ->
                        AssistChip(
                            onClick = { onRemoveQueuedPrompt(prompt.id) },
                            label = {
                                Text(
                                    prompt.text.ifBlank { stringResource(R.string.queued_prompt_fallback) },
                                    maxLines = 1,
                                    overflow = TextOverflow.Ellipsis,
                                )
                            },
                            trailingIcon = { Icon(Icons.Rounded.Close, null, Modifier.size(15.dp)) },
                        )
                    }
                }
            }
            state.quotedContext?.let { quote ->
                Surface(
                    shape = RoundedCornerShape(14.dp),
                    color = if (dark) Color(0xFF242529) else Color.White,
                    shadowElevation = 1.dp,
                ) {
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(start = 12.dp, top = 7.dp, bottom = 7.dp, end = 4.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Text(
                            quote,
                            modifier = Modifier.weight(1f),
                            maxLines = 2,
                            overflow = TextOverflow.Ellipsis,
                            color = mutedColor,
                            style = MaterialTheme.typography.bodySmall,
                        )
                        IconButton(onClick = onClearQuote, modifier = Modifier.size(32.dp)) {
                            Icon(Icons.Rounded.Close, stringResource(R.string.remove_quoted_context), Modifier.size(16.dp))
                        }
                    }
                }
            }
            if (state.attachments.isNotEmpty() || state.encodingCount > 0) {
                Row(horizontalArrangement = Arrangement.spacedBy(6.dp), modifier = Modifier.fillMaxWidth()) {
                    state.attachments.take(3).forEachIndexed { index, attachment ->
                        AssistChip(
                            onClick = { onRemoveAttachment(index) },
                            label = { Text(attachment.name, maxLines = 1, overflow = TextOverflow.Ellipsis) },
                            trailingIcon = { Icon(Icons.Rounded.Close, null, Modifier.size(15.dp)) },
                        )
                    }
                    if (state.encodingCount > 0) {
                        CircularProgressIndicator(Modifier.size(20.dp), strokeWidth = 1.5.dp)
                    }
                }
            }
            VoiceStatus(state.voice)
            Surface(
                modifier = Modifier.fillMaxWidth().height(cardHeight),
                shape = RoundedCornerShape((28f * scale).dp),
                color = cardColor,
                tonalElevation = 0.dp,
                shadowElevation = (3f * scale).dp,
            ) {
                Column(
                    modifier = Modifier
                        .fillMaxSize()
                        .padding(
                            start = (12f * scale).dp,
                            top = (14f * scale).dp,
                            end = (12f * scale).dp,
                            bottom = (15f * scale).dp,
                        ),
                ) {
                    BasicTextField(
                        value = TextFieldValue(
                            text = state.text,
                            selection = TextRange(state.cursorPosition.coerceIn(0, state.text.length)),
                        ),
                        onValueChange = { value -> onTextChange(value.text, value.selection.end) },
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(start = (2f * scale).dp)
                            .heightIn(min = (32f * scale).dp, max = (46f * scale).dp),
                        enabled = !state.voice.isRecording && !state.voice.isTranscribing && !state.sending,
                        textStyle = MaterialTheme.typography.bodyLarge.copy(
                            color = textColor,
                            fontSize = (16f * scale).coerceAtLeast(14f).sp,
                            lineHeight = (22f * scale).coerceAtLeast(19f).sp,
                        ),
                        keyboardOptions = KeyboardOptions(imeAction = ImeAction.Send),
                        keyboardActions = KeyboardActions(onSend = { if (hasDraft && !state.sending) onSend() }),
                        maxLines = 5,
                        decorationBox = { innerTextField ->
                            Box(Modifier.fillMaxWidth()) {
                                if (state.text.isEmpty()) {
                                    Text(
                                        text = "Type your message...",
                                        color = mutedColor,
                                        style = MaterialTheme.typography.bodyLarge.copy(
                                            fontSize = (16f * scale).coerceAtLeast(14f).sp,
                                            lineHeight = (22f * scale).coerceAtLeast(19f).sp,
                                        ),
                                    )
                                }
                                innerTextField()
                            }
                        },
                    )
                    Spacer(Modifier.weight(1f))
                    Row(
                        modifier = Modifier.fillMaxWidth().height((44f * scale).dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Box {
                            Surface(
                                onClick = { attachmentMenuOpen = true },
                                enabled = !state.voice.isRecording && !state.sending,
                                modifier = Modifier.size((44f * scale).dp),
                                shape = CircleShape,
                                color = controlColor,
                                shadowElevation = (2f * scale).dp,
                            ) {
                                Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                                    Icon(
                                        Icons.Rounded.Add,
                                        stringResource(R.string.attach_file),
                                        modifier = Modifier.size((20f * scale).dp),
                                        tint = mutedColor,
                                    )
                                }
                            }
                            DropdownMenu(attachmentMenuOpen, { attachmentMenuOpen = false }) {
                                DropdownMenuItem(
                                    text = { Text(stringResource(R.string.attach_image)) },
                                    leadingIcon = { Icon(Icons.Rounded.Image, null) },
                                    onClick = { attachmentMenuOpen = false; onPickImages() },
                                )
                                DropdownMenuItem(
                                    text = { Text(stringResource(R.string.attach_file)) },
                                    leadingIcon = { Icon(Icons.Rounded.AttachFile, null) },
                                    onClick = { attachmentMenuOpen = false; onPickFiles() },
                                )
                            }
                        }
                        Spacer(Modifier.width((8f * scale).dp))
                        WorkspaceControls(
                            scope = workspaceScope,
                            catalog = workspaces,
                            error = workspaceError,
                            isHero = false,
                            compact = true,
                            heroStyle = true,
                            disabled = active,
                            onChange = onWorkspaceChange,
                            modifier = Modifier.width(workspaceWidth).height((38f * scale).dp),
                        )
                        Spacer(Modifier.width((16f * scale).dp))
                        ModelPresetControl(
                            model = model,
                            disabled = state.sending || !model.enabled,
                            onChange = onModelChange,
                            onOpenSettings = onOpenModelSettings,
                            compact = true,
                            heroStyle = true,
                            heroScale = scale,
                            modifier = Modifier.width(modelWidth).height((38f * scale).dp),
                        )
                        Spacer(Modifier.weight(1f))
                        VoiceRecordButton(
                            recording = state.voice.isRecording,
                            enabled = !active && !state.voice.isTranscribing && !state.sending,
                            onStart = onVoiceStart,
                            onStop = onVoiceStop,
                            compact = true,
                            heroStyle = true,
                            modifier = Modifier.size((34f * scale).dp),
                        )
                        Spacer(Modifier.width((10f * scale).dp))
                        Surface(
                            onClick = if (stopButton) onStop else onSend,
                            enabled = sendEnabled,
                            modifier = Modifier.size((44f * scale).dp),
                            shape = CircleShape,
                            color = sendColor,
                            shadowElevation = (2f * scale).dp,
                        ) {
                            Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                                when {
                                    state.sending -> CircularProgressIndicator(
                                        Modifier.size((18f * scale).dp),
                                        strokeWidth = 2.dp,
                                        color = sendContentColor,
                                    )
                                    stopButton -> Icon(
                                        Icons.Rounded.Stop,
                                        stringResource(R.string.stop),
                                        modifier = Modifier.size((20f * scale).dp),
                                        tint = sendContentColor,
                                    )
                                    else -> Icon(
                                        Icons.Rounded.ArrowUpward,
                                        stringResource(R.string.send),
                                        modifier = Modifier.size((21f * scale).dp),
                                        tint = sendContentColor,
                                    )
                                }
                            }
                        }
                    }
                }
            }
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
    onVoiceStart: () -> Unit,
    onVoiceStop: (cancelled: Boolean, maxReached: Boolean) -> Unit,
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
            onVoiceStart = onVoiceStart,
            onVoiceStop = onVoiceStop,
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
            onVoiceStart = onVoiceStart,
            onVoiceStop = onVoiceStop,
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
    var requestedPreset by remember { mutableStateOf<String?>(null) }

    LaunchedEffect(requestedPreset, model.pendingPreset, model.error, model.activePreset) {
        val requested = requestedPreset ?: return@LaunchedEffect
        if (model.pendingPreset == null && model.error == null && model.activePreset == requested) {
            requestedPreset = null
            open = false
        }
    }

    Row(modifier, horizontalArrangement = Arrangement.End) {
        if (compact) {
            val dark = MaterialTheme.colorScheme.background.luminance() < 0.5f
            val controlColor = if (heroStyle) {
                if (dark) Color(0xFF292A2E) else Color.White
            } else {
                MaterialTheme.colorScheme.surface
            }
            val labelColor = if (dark) Color(0xFFF4F4F5) else Color(0xFF171719)
            Surface(
                onClick = { if (options.isEmpty()) onOpenSettings() else open = true },
                enabled = !disabled,
                shape = CircleShape,
                color = controlColor,
                shadowElevation = if (heroStyle) 2.dp else 1.dp,
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
                                .background(Color(0xFFFF5FA2), CircleShape),
                            contentAlignment = Alignment.Center,
                        ) {
                            Icon(
                                Icons.Rounded.SmartToy,
                                contentDescription = null,
                                modifier = Modifier.size((11f * heroScale).dp),
                                tint = Color.White,
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
                                fontSize = (12f * heroScale).coerceAtLeast(10.5f).sp,
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
        AlertDialog(
            onDismissRequest = {
                if (model.pendingPreset == null) open = false
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
                                            open = false
                                        } else {
                                            requestedPreset = preset.name
                                            onChange(preset.name)
                                        }
                                    }
                                },
                                enabled = model.pendingPreset == null,
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
                                shape = RoundedCornerShape(12.dp),
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
                                    open = false
                                    onOpenSettings()
                                },
                                enabled = model.pendingPreset == null,
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
                    onClick = { open = false },
                    enabled = model.pendingPreset == null,
                ) { Text(stringResource(R.string.cancel)) }
            },
        )
    }
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
                val dark = MaterialTheme.colorScheme.background.luminance() < 0.5f
                val controlColor = if (heroStyle) {
                    if (dark) Color(0xFF292A2E) else Color.White
                } else {
                    MaterialTheme.colorScheme.surface
                }
                val accentColor = Color(0xFFFF5A2A)
                val labelColor = when {
                    heroStyle && full -> accentColor
                    heroStyle && dark -> Color(0xFFF4F4F5)
                    heroStyle -> Color(0xFF4C4C4F)
                    else -> MaterialTheme.colorScheme.onSurface
                }
                Surface(
                    onClick = { accessDialogOpen = true },
                    enabled = !disabled,
                    shape = CircleShape,
                    color = controlColor,
                    shadowElevation = if (heroStyle) 2.dp else 1.dp,
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
                                    fontSize = 12.sp,
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
                        shape = RoundedCornerShape(12.dp),
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
        AlertDialog(
            onDismissRequest = { accessDialogOpen = false },
            title = { Text(stringResource(R.string.workspace_access_title)) },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                    Surface(
                        onClick = {
                            onChange(activeScope.withAccessMode(WorkspaceAccessMode.RESTRICTED))
                            accessDialogOpen = false
                        },
                        modifier = Modifier.fillMaxWidth(),
                        color = if (activeScope.accessMode == WorkspaceAccessMode.RESTRICTED) {
                            MaterialTheme.colorScheme.secondaryContainer
                        } else {
                            MaterialTheme.colorScheme.surface
                        },
                        shape = RoundedCornerShape(12.dp),
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
                                onChange(activeScope.withAccessMode(WorkspaceAccessMode.FULL))
                                accessDialogOpen = false
                            }
                        },
                        modifier = Modifier.fillMaxWidth(),
                        enabled = controls?.canUseFullAccess != false,
                        color = if (activeScope.accessMode == WorkspaceAccessMode.FULL) {
                            MaterialTheme.colorScheme.tertiaryContainer
                        } else {
                            MaterialTheme.colorScheme.surface
                        },
                        shape = RoundedCornerShape(12.dp),
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
                TextButton(onClick = { accessDialogOpen = false }) {
                    Text(stringResource(R.string.cancel))
                }
            },
        )
    }
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
                            RoundedCornerShape(8.dp),
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

@Composable
private fun VoiceRecordButton(
    recording: Boolean,
    enabled: Boolean,
    onStart: () -> Unit,
    onStop: (cancelled: Boolean, maxReached: Boolean) -> Unit,
    modifier: Modifier = Modifier,
    compact: Boolean = false,
    heroStyle: Boolean = false,
) {
    val description = stringResource(if (recording) R.string.release_to_transcribe else R.string.hold_to_record)
    val interactionModifier = modifier
        .then(if (heroStyle) Modifier else Modifier.size(if (compact) 30.dp else 48.dp))
        .semantics {
            contentDescription = description
            onClick {
                if (!enabled) return@onClick false
                if (recording) onStop(false, false) else onStart()
                true
            }
        }
        .pointerInput(enabled, recording) {
            if (!enabled) return@pointerInput
            detectTapGestures(
                onPress = {
                    onStart()
                    val released = tryAwaitRelease()
                    onStop(!released, false)
                },
            )
        }

    if (heroStyle) {
        val dark = MaterialTheme.colorScheme.background.luminance() < 0.5f
        Box(
            modifier = interactionModifier.then(
                if (recording) {
                    Modifier.background(MaterialTheme.colorScheme.errorContainer, CircleShape)
                } else {
                    Modifier
                },
            ),
            contentAlignment = Alignment.Center,
        ) {
            Icon(
                Icons.Rounded.Mic,
                description,
                modifier = Modifier.size(15.dp),
                tint = if (dark) Color(0xFFB0B0B3) else Color(0xFF7D7D80),
            )
        }
        return
    }

    Surface(
        shape = CircleShape,
        color = when {
            recording -> MaterialTheme.colorScheme.errorContainer
            compact -> MaterialTheme.colorScheme.surfaceContainerLow
            else -> MaterialTheme.colorScheme.secondaryContainer
        },
        modifier = interactionModifier,
    ) {
        Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
            Icon(Icons.Rounded.Mic, description, Modifier.size(if (compact) 16.dp else 24.dp))
        }
    }
}

@Composable
private fun VoiceStatus(voice: VoiceUiState) {
    when {
        voice.isRecording -> {
            Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    Row(
                        modifier = Modifier.weight(1f).height(24.dp),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(2.dp),
                    ) {
                        voice.waveform.forEach { level ->
                            Box(
                                Modifier
                                    .width(3.dp)
                                    .height((4 + level * 20).dp)
                                    .background(MaterialTheme.colorScheme.primary, CircleShape),
                            )
                        }
                    }
                    Text(formatDuration(voice.durationMs), style = MaterialTheme.typography.labelMedium)
                }
                if (voice.noInputHint) {
                    Text(
                        stringResource(R.string.no_audio_input),
                        color = MaterialTheme.colorScheme.error,
                        style = MaterialTheme.typography.labelMedium,
                    )
                }
            }
        }

        voice.isTranscribing -> Row(
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            CircularProgressIndicator(Modifier.size(18.dp), strokeWidth = 2.dp)
            Text(stringResource(R.string.transcribing_audio), style = MaterialTheme.typography.labelMedium)
        }

        voice.error != null -> Text(
            voiceErrorLabel(voice.error),
            color = MaterialTheme.colorScheme.error,
            style = MaterialTheme.typography.labelMedium,
        )
    }
}

private fun formatDuration(durationMs: Long): String {
    val totalSeconds = durationMs.coerceAtLeast(0) / 1_000
    return "%d:%02d".format(totalSeconds / 60, totalSeconds % 60)
}

@Composable
private fun voiceErrorLabel(error: VoiceRecorderError): String = stringResource(
    when (error) {
        VoiceRecorderError.UNSUPPORTED -> R.string.voice_unsupported
        VoiceRecorderError.PERMISSION -> R.string.voice_permission
        VoiceRecorderError.NOT_CONFIGURED -> R.string.voice_not_configured
        VoiceRecorderError.TOO_LONG -> R.string.voice_too_long
        VoiceRecorderError.TOO_SHORT -> R.string.voice_too_short
        VoiceRecorderError.NO_INPUT -> R.string.no_audio_input
        VoiceRecorderError.NO_DEVICE -> R.string.voice_no_device
        VoiceRecorderError.FAILED -> R.string.voice_failed
    },
)
