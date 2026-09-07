package com.nanobotkt

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.layout.size
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Refresh
import androidx.compose.material.icons.rounded.SmartToy
import androidx.compose.material3.Button
import androidx.compose.material3.DrawerValue
import androidx.compose.material3.ModalDrawerSheet
import androidx.compose.material3.ModalNavigationDrawer
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.SnackbarDuration
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.SnackbarResult
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.rememberDrawerState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.core.view.WindowCompat
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import com.nanobotkt.core.designsystem.NanobotTheme
import com.nanobotkt.core.model.ChatSummary
import com.nanobotkt.core.model.displayName
import com.nanobotkt.feature.chat.buildWorkspaceOptions
import com.nanobotkt.core.model.SidebarSortMode
import com.nanobotkt.core.persistence.DensityPreference
import com.nanobotkt.core.persistence.ThemePreference
import com.nanobotkt.core.transport.TransportStatus
import com.nanobotkt.feature.apps.AppsScreen
import com.nanobotkt.feature.auth.AuthScreen
import com.nanobotkt.feature.auth.AuthState
import com.nanobotkt.feature.auth.GatewayConfigurationError
import com.nanobotkt.feature.auth.GatewayConnectionState
import com.nanobotkt.feature.auth.gatewayConfigurationErrorMessage
import com.nanobotkt.feature.automations.AutomationsScreen
import com.nanobotkt.feature.channels.ChannelsScreen
import com.nanobotkt.feature.chat.ChatScreen
import com.nanobotkt.feature.chat.ChatViewModel
import com.nanobotkt.feature.chat.ConversationListItem
import com.nanobotkt.feature.chat.ConversationListScreen
import com.nanobotkt.feature.chat.ConversationListDrawerContent
import com.nanobotkt.feature.security.SecurityScreen
import com.nanobotkt.feature.settings.SETTINGS_SECTION_MODELS
import com.nanobotkt.feature.settings.SETTINGS_SECTION_OVERVIEW
import com.nanobotkt.feature.settings.SettingsScreen
import com.nanobotkt.feature.sidebar.SidebarUiState
import com.nanobotkt.feature.sidebar.SidebarViewModel
import com.nanobotkt.feature.skills.SkillsScreen
import com.nanobotkt.feature.workspaces.ui.WorkspacesScreen
import java.util.Locale
import kotlinx.coroutines.launch


@Composable
fun NanobotRoot(appViewModel: AppViewModel) {
    val authState by appViewModel.authState.collectAsStateWithLifecycle()
    val preferences by appViewModel.preferences.collectAsStateWithLifecycle()
    val darkTheme = when (preferences.theme) {
        ThemePreference.SYSTEM -> androidx.compose.foundation.isSystemInDarkTheme()
        ThemePreference.DARK -> true
        ThemePreference.LIGHT -> false
    }
    val view = LocalView.current
    // 保持系统状态栏和导航栏可见。应用已经启用了 edge-to-edge，真正需要做的是让
    // 内容避开系统栏，而不是把系统栏隐藏后再依赖固定 dp 偏移模拟安全区域。
    SideEffect {
        val window = (view.context as? android.app.Activity)?.window ?: return@SideEffect
        WindowCompat.getInsetsController(window, view).apply {
            isAppearanceLightStatusBars = !darkTheme
            isAppearanceLightNavigationBars = !darkTheme
        }
    }
    NanobotTheme(
        darkTheme = darkTheme,
        compact = preferences.density == DensityPreference.COMPACT,
    ) {
        Surface(Modifier.fillMaxSize()) {
            when (val state = authState) {
                is AuthState.Booting -> LoadingScreen()
                is AuthState.Configuration -> AuthScreen(state, appViewModel::connectGateway)
                is AuthState.Unreachable -> UnreachableScreen(
                    error = state.error,
                    serverUrl = state.serverUrl,
                    onRetry = appViewModel::retry,
                    onLoginAgain = appViewModel::editGatewayConfiguration,
                )
                is AuthState.Ready -> ReadyRoot(state, appViewModel)
            }
        }
    }
}

@Composable
private fun LoadingScreen() {
    Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
        Column(horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(16.dp)) {
            Icon(Icons.Rounded.SmartToy, contentDescription = null, Modifier.size(56.dp), tint = MaterialTheme.colorScheme.primary)
            CircularProgressIndicator()
            Text(stringResource(R.string.connecting_gateway), color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
    }
}

@Composable
internal fun UnreachableScreen(
    error: GatewayConfigurationError,
    serverUrl: String,
    onRetry: () -> Unit,
    onLoginAgain: () -> Unit,
) {
    Box(Modifier.fillMaxSize().padding(24.dp), contentAlignment = Alignment.Center) {
        Column(
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(16.dp),
        ) {
            Icon(
                Icons.Rounded.SmartToy,
                contentDescription = null,
                modifier = Modifier.size(56.dp),
                tint = MaterialTheme.colorScheme.error,
            )
            Text(stringResource(R.string.gateway_unreachable), style = MaterialTheme.typography.headlineSmall)
            // 临时故障保留完整配置，因此明确展示当前重试目标，并同时提供“重试当前”
            // 和“重新登录”两个出口；地址或引导密钥错误时，用户不必对同一个错误无限重试。
            Text(serverUrl, color = MaterialTheme.colorScheme.onSurfaceVariant)
            Text(
                gatewayConfigurationErrorMessage(error),
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            // 两个恢复动作并排呈现：左侧主按钮继续使用当前地址重试，右侧次按钮进入
            // 完整登录配置。固定使用 weight 而不是固定宽度，窄屏与横屏都能平分可用空间。
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(12.dp),
            ) {
                Button(
                    modifier = Modifier.weight(1f),
                    onClick = onRetry,
                ) {
                    Icon(Icons.Rounded.Refresh, contentDescription = null)
                    Text(stringResource(R.string.retry), Modifier.padding(start = 8.dp))
                }
                OutlinedButton(
                    modifier = Modifier.weight(1f),
                    onClick = onLoginAgain,
                ) {
                    Text(stringResource(R.string.login_again))
                }
            }
        }
    }
}

@Composable
private fun ReadyRoot(
    authState: AuthState.Ready,
    appViewModel: AppViewModel,
    sidebarViewModel: SidebarViewModel = hiltViewModel(),
    chatViewModel: ChatViewModel = hiltViewModel(),
) {
    val sidebar by sidebarViewModel.state.collectAsStateWithLifecycle()
    val transport by appViewModel.transportState.collectAsStateWithLifecycle()
    val rootUiState by appViewModel.rootUiState.collectAsStateWithLifecycle()
    val gatewayReconfiguration by appViewModel.gatewayReconfiguration.collectAsStateWithLifecycle()
    val selectedKey = rootUiState.selectedKey
    val destination = rootUiState.destination
    val draftingNewTopic = rootUiState.draftingNewTopic
    val lifecycleOwner = LocalLifecycleOwner.current
    val sidebarSnackbar = remember { SnackbarHostState() }
    val gatewaySnackbar = remember { SnackbarHostState() }
    val gatewayErrorMessage = authState.error?.let { gatewayConfigurationErrorMessage(it) }
        ?: stringResource(R.string.gateway_unreachable)
    val retryLabel = stringResource(R.string.retry)

    // Drawer 是纯 UI 状态；不会写入 SavedStateHandle，也不会影响会话选择链路。
    val drawerState = rememberDrawerState(DrawerValue.Closed)
    val drawerScope = rememberCoroutineScope()
    val openDrawer: () -> Unit = { drawerScope.launch { drawerState.open() } }
    val closeDrawer: () -> Unit = { drawerScope.launch { drawerState.close() } }

    DisposableEffect(lifecycleOwner, chatViewModel) {
        val observer = LifecycleEventObserver { _, event ->
            if (event == Lifecycle.Event.ON_STOP) {
                // ReadyRoot 不会因锁屏自动离开 Composition，Activity-scoped ChatViewModel 也不会
                // onCleared；必须把 STOP 明确传入 Chat，确保后台不继续持有麦克风。
                chatViewModel.onAppBackgrounded()
            }
        }
        lifecycleOwner.lifecycle.addObserver(observer)
        onDispose { lifecycleOwner.lifecycle.removeObserver(observer) }
    }

    // Root 只在离开聊天页时接管系统返回。具体返回目标由 SavedStateHandle 中持久化的
    // returnDestination 决定，保证 Settings 子页和进程恢复后的返回层级保持一致。
    BackHandler(enabled = destination != AppDestination.CHAT) {
        appViewModel.navigateBack()
    }

    LaunchedEffect(authState.connection, gatewayErrorMessage, retryLabel) {
        // 离线只影响云端同步能力，不替换 Root 或清空缓存。使用独立 SnackbarHost 提供明确
        // 的重试入口，避免与 Sidebar 业务错误互相阻塞；连接恢复后立即撤下离线提示。
        gatewaySnackbar.currentSnackbarData?.dismiss()
        if (authState.connection == GatewayConnectionState.OFFLINE) {
            val result = gatewaySnackbar.showSnackbar(
                message = gatewayErrorMessage,
                actionLabel = retryLabel,
                duration = SnackbarDuration.Indefinite,
            )
            if (result == SnackbarResult.ActionPerformed) appViewModel.retry()
        }
    }

    LaunchedEffect(sidebar.error) {
        val error = sidebar.error ?: return@LaunchedEffect
        sidebarSnackbar.showSnackbar(error)
        sidebarViewModel.clearError()
    }
    val sortedSessions = remember(sidebar.sessions, sidebar.sidebar) {
        sortSidebarSessions(sidebar.sessions, sidebar)
    }
    val visibleSessions = remember(sortedSessions, sidebar.sidebar) {
        sortedSessions.filter { session ->
            sidebar.sidebar.view.showArchived || session.key !in sidebar.sidebar.archivedKeys
        }
    }
    LaunchedEffect(visibleSessions, selectedKey, draftingNewTopic, sidebar.loaded) {
        val reconciled = reconcileSessionSelection(
            visibleKeys = visibleSessions.map { it.key },
            selectedKey = selectedKey,
            draftingNewTopic = draftingNewTopic,
            sidebarLoaded = sidebar.loaded,
        )
        if (selectedKey != reconciled.selectedKey || draftingNewTopic != reconciled.draftingNewTopic) {
            appViewModel.updateSessionSelection(reconciled)
        }
    }
    val selected = visibleSessions.firstOrNull { it.key == selectedKey }
    // 新会话的候选 Workspace 只来自用户已经拥有的会话元数据；同一路径在多个会话中出现时
    // 只保留一个选项，避免把会话数量误当成 Workspace 数量。
    val workspaceOptions = remember(sortedSessions) {
        buildWorkspaceOptions(sortedSessions.map(ChatSummary::workspaceScope))
    }
    val requestNewConversation: () -> Unit = {
        // 新建动作只负责进入空白主题，Workspace 选择延后到 Composer，避免默认 Workspace
        // 已经满足需求时还要先打断用户一次。Composer 会在首条消息发送前捕获最终选择。
        appViewModel.beginNewTopic()
        chatViewModel.startNewTopic()
        // 兼容旧的 Conversations destination：无论从哪个会话入口发起，都直接回到聊天页。
        appViewModel.navigate(AppDestination.CHAT)
    }
    LaunchedEffect(selected?.chatId) {
        // Sidebar 自己维护全局活动状态；Root 只把当前选择作为最小边界传入，选中即读。
        sidebarViewModel.markRead(selected?.chatId)
    }
    // Sheet 需要同时拿到 active/archived 两种前端展示集合。两者都来自同一份 Sidebar
    // 快照，归档只是客户端过滤，不改变服务端返回的数据或会话选择算法。
    val conversationItems = remember(sortedSessions, sidebar) {
        sortedSessions
            .filter { it.key !in sidebar.sidebar.archivedKeys }
            .map { session ->
                ConversationListItem(
                    key = session.key,
                    title = session.displayTitle(sidebar),
                    preview = session.preview,
                    workspaceName = session.workspaceScope?.displayName(),
                    pinned = session.key in sidebar.sidebar.pinnedKeys,
                    archived = false,
                    pending = session.key in sidebar.pendingKeys,
                    running = session.chatId in sidebar.runningChatIds,
                    unread = session.chatId in sidebar.unreadChatIds,
                )
            }
    }
    val archivedConversationItems = remember(sortedSessions, sidebar) {
        sortedSessions
            .filter { it.key in sidebar.sidebar.archivedKeys }
            .map { session ->
                ConversationListItem(
                    key = session.key,
                    title = session.displayTitle(sidebar),
                    preview = session.preview,
                    workspaceName = session.workspaceScope?.displayName(),
                    pinned = session.key in sidebar.sidebar.pinnedKeys,
                    archived = true,
                    pending = session.key in sidebar.pendingKeys,
                    running = session.chatId in sidebar.runningChatIds,
                    unread = session.chatId in sidebar.unreadChatIds,
                )
            }
    }
    LaunchedEffect(
        selected?.key,
        selected?.modelPreset,
        selected?.workspaceScope,
        selectedKey,
        draftingNewTopic,
        sidebar.loaded,
    ) {
        when {
            selected != null -> chatViewModel.open(
                selected.key,
                selected.chatId,
                selected.workspaceScope,
                selected.modelPreset,
            )
            // 删除最后一个已加载会话后，必须把 ChatRepository 也切回新主题，
            // 否则 Root 虽然没有 selectedKey，聊天页仍会持有已删除的 chatId。该分支也会处理
            // 启动时的空选择恢复，因此只能恢复 new-topic 草稿，不能按“用户主动新建”清空输入。
            sidebar.loaded && selectedKey == null && !draftingNewTopic -> chatViewModel.restoreNewTopic()
        }
    }

    // 根节点只负责组装左侧 Drawer 和当前目的地；Drawer 的会话数据仍来自同一份
    // Sidebar 快照，导航本身不创建第二套会话状态。
    ModalNavigationDrawer(
        drawerState = drawerState,
        gesturesEnabled = drawerState.isOpen,
        scrimColor = MaterialTheme.colorScheme.scrim.copy(alpha = 0.32f),
        drawerContent = {
            ModalDrawerSheet(
                modifier = Modifier
                    .fillMaxWidth(0.9f)
                    .widthIn(max = 360.dp),
                drawerContainerColor = MaterialTheme.colorScheme.surface,
                drawerContentColor = MaterialTheme.colorScheme.onSurface,
            ) {
                ConversationListDrawerContent(
                    items = conversationItems,
                    selectedKey = selectedKey,
                    onSelect = { item ->
                        closeDrawer()
                        appViewModel.selectSession(item.key)
                    },
                    onNewTopic = {
                        closeDrawer()
                        requestNewConversation()
                    },
                    onTogglePinned = sidebarViewModel::togglePinned,
                    onRename = { item, title ->
                        sidebarViewModel.rename(item.key, title)
                    },
                    onArchive = sidebarViewModel::toggleArchived,
                    onDelete = { item -> sidebarViewModel.delete(item.key) },
                )
            }
        },
    ) {
        Box(modifier = Modifier.fillMaxSize()) {
            when (destination) {
                AppDestination.CHAT -> ChatScreen(
                    viewModel = chatViewModel,
                    title = selected?.displayTitle(sidebar) ?: stringResource(R.string.new_topic),
                    onOpenDrawer = openDrawer,
                    conversationItems = conversationItems,
                    archivedConversationItems = archivedConversationItems,
                    selectedConversationKey = selectedKey,
                    onSelectConversation = { item ->
                        appViewModel.selectSession(item.key)
                    },
                    onNewConversation = requestNewConversation,
                    onToggleConversationPinned = sidebarViewModel::togglePinned,
                    onRenameConversation = { item, title ->
                        sidebarViewModel.rename(item.key, title)
                    },
                    conversationMutationError = sidebar.error,
                    onClearConversationMutationError = sidebarViewModel::clearError,
                    onArchiveConversation = sidebarViewModel::toggleArchived,
                    onDeleteConversation = { item ->
                        sidebarViewModel.delete(item.key)
                    },
                    onToggleTheme = appViewModel::toggleTheme,
                    onOpenSettings = {
                        // 顶部右侧入口是应用级全局设置，始终从 Settings 总览开始，
                        // 不携带当前会话的模型或 Workspace 局部上下文。
                        appViewModel.openSettings(SETTINGS_SECTION_OVERVIEW)
                    },
                    onOpenModelSettings = {
                        // 模型快捷入口来源是 Chat，因此返回时不经过 Settings Home。
                        appViewModel.openSettings(SETTINGS_SECTION_MODELS)
                    },
                    // Chat feature 只接收 TransportStatus 这一最小只读边界，用于顶部状态展示；
                    // WebSocket 重连与生命周期仍由 AppViewModel/Transport 管理，避免 UI 产生第二状态源。
                    transportStatus = transport.status,
                    workspaceOptions = workspaceOptions,
                    onSessionCreated = { key ->
                        if (selectedKey != key) {
                            // 新会话出现在 Sidebar 前继续保留 drafting guard，避免传播窗口内
                            // 被第一条旧会话抢占；只在刷新后由 reconcileSessionSelection 清除。
                            appViewModel.updateSessionSelection(
                                SessionSelection(key, draftingNewTopic),
                            )
                            sidebarViewModel.refresh()
                        }
                    },
                )
            // 该目的地仅用于兼容旧 SavedState；新的会话列表入口已经是 Chat 内 Bottom Sheet。
            AppDestination.CONVERSATIONS -> ConversationListScreen(
                items = conversationItems,
                selectedKey = selectedKey,
                onBack = appViewModel::navigateBack,
                onSelect = { item ->
                    appViewModel.selectSession(item.key)
                    appViewModel.navigate(AppDestination.CHAT)
                },
                onNewTopic = requestNewConversation,
                onTogglePinned = sidebarViewModel::togglePinned,
                onRename = { item, title -> sidebarViewModel.rename(item.key, title) },
                onArchive = sidebarViewModel::toggleArchived,
                onDelete = { item -> sidebarViewModel.delete(item.key) },
            )
            AppDestination.WORKSPACES -> WorkspacesScreen(onBack = appViewModel::navigateBack)
            AppDestination.APPS -> AppsScreen(onBack = appViewModel::navigateBack)
            AppDestination.SKILLS -> SkillsScreen(onBack = appViewModel::navigateBack)
            AppDestination.AUTOMATIONS -> AutomationsScreen(onBack = appViewModel::navigateBack)
            AppDestination.CHANNELS -> ChannelsScreen(onBack = appViewModel::navigateBack)
            AppDestination.SECURITY -> SecurityScreen(onBack = appViewModel::navigateBack)
            AppDestination.SETTINGS -> SettingsScreen(
                onBack = appViewModel::navigateBack,
                onOpenApps = { appViewModel.openSettingsChild(AppDestination.APPS) },
                onOpenSkills = { appViewModel.openSettingsChild(AppDestination.SKILLS) },
                onOpenAutomations = { appViewModel.openSettingsChild(AppDestination.AUTOMATIONS) },
                onOpenChannels = { appViewModel.openSettingsChild(AppDestination.CHANNELS) },
                onOpenWorkspaces = { appViewModel.openSettingsChild(AppDestination.WORKSPACES) },
                onOpenSecurityAndPairing = { appViewModel.openSettingsChild(AppDestination.SECURITY) },
                onLogout = appViewModel::logout,
                onReconnect = appViewModel::reconnect,
                onReconfigureGateway = appViewModel::reconfigureGateway,
                gatewayReconfigurationInProgress = gatewayReconfiguration.submitting,
                gatewayReconfigurationError = gatewayReconfiguration.error?.let { error ->
                    gatewayConfigurationErrorMessage(error)
                },
                gatewayReconfigurationSuccessGeneration = gatewayReconfiguration.successGeneration,
                connectionStatus = transport.status.displayName(),
                // 展示认证与网络层实际使用的客户端入口，禁止误用服务端内部监听地址。
                gatewayEndpoint = appViewModel.gatewayServerUrl,
                initialSection = rootUiState.settingsSection,
                onOpenSection = appViewModel::openSettingsSection,
                onSectionChange = appViewModel::setSettingsSection,
            )
        }
        SnackbarHost(
            hostState = gatewaySnackbar,
            modifier = Modifier.align(Alignment.TopCenter).padding(16.dp),
        )
        SnackbarHost(
            hostState = sidebarSnackbar,
            modifier = Modifier.align(Alignment.BottomCenter).padding(16.dp),
        )
    }
    }
}

internal data class SessionSelection(
    val selectedKey: String?,
    val draftingNewTopic: Boolean,
)

internal fun reconcileSessionSelection(
    visibleKeys: List<String>,
    selectedKey: String?,
    draftingNewTopic: Boolean,
    sidebarLoaded: Boolean = false,
): SessionSelection {
    if (draftingNewTopic) {
        val createdSessionVisible = selectedKey != null && selectedKey in visibleKeys
        return SessionSelection(
            selectedKey = selectedKey,
            draftingNewTopic = !createdSessionVisible,
        )
    }

    // A restored selection arrives before the first Sidebar refresh completes. Preserve it
    // while the list is empty so the subsequent loaded list can validate it instead of
    // prematurely falling back to the first topic.
    if (!sidebarLoaded && visibleKeys.isEmpty() && selectedKey != null) {
        return SessionSelection(selectedKey = selectedKey, draftingNewTopic = false)
    }

    val validSelection = selectedKey?.takeIf { it in visibleKeys }
    return SessionSelection(
        selectedKey = validSelection ?: visibleKeys.firstOrNull(),
        draftingNewTopic = false,
    )
}


/**
 * 按服务端 Sidebar view 的显式排序模式生成稳定顺序。
 *
 * 时间字段是 Gateway 返回的 ISO-8601 字符串，同一格式下可直接按字典序比较；缺失值统一排在末尾，
 * 再以标题和 key 作为稳定 tie-breaker，避免刷新时相同时间的行随机跳动。
 */
internal fun sortSidebarSessions(
    sessions: List<ChatSummary>,
    state: SidebarUiState,
): List<ChatSummary> {
    val titleOf: (ChatSummary) -> String = { session ->
        session.displayTitle(state).lowercase(Locale.ROOT)
    }
    val stableTitleComparator = compareBy<ChatSummary>(titleOf).thenBy(ChatSummary::key)
    val primary = when (state.sidebar.view.sort) {
        SidebarSortMode.UPDATED_DESC -> compareByDescending<ChatSummary> {
            it.updatedAt ?: it.createdAt ?: ""
        }
        SidebarSortMode.CREATED_DESC -> compareByDescending<ChatSummary> { it.createdAt ?: "" }
        SidebarSortMode.TITLE_ASC -> stableTitleComparator
    }
    return if (state.sidebar.view.sort == SidebarSortMode.TITLE_ASC) {
        sessions.sortedWith(primary)
    } else {
        sessions.sortedWith(primary.then(stableTitleComparator))
    }
}

private fun ChatSummary.displayTitle(state: SidebarUiState): String =
    state.sidebar.titleOverrides[key] ?: title?.takeIf(String::isNotBlank) ?: preview.takeIf(String::isNotBlank) ?: chatId

private fun TransportStatus.displayName(): String = when (this) {
    TransportStatus.OPEN -> "Connected"
    TransportStatus.CONNECTING -> "Connecting"
    TransportStatus.RECONNECTING -> "Reconnecting"
    TransportStatus.ERROR -> "Connection error"
    TransportStatus.CLOSED -> "Disconnected"
    TransportStatus.IDLE -> "Idle"
}
