package com.nanobotkt

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Refresh
import androidx.compose.material.icons.rounded.SmartToy
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
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
import com.nanobotkt.core.persistence.DensityPreference
import com.nanobotkt.core.persistence.ThemePreference
import com.nanobotkt.core.transport.TransportStatus
import com.nanobotkt.feature.apps.AppsScreen
import com.nanobotkt.feature.auth.AuthScreen
import com.nanobotkt.feature.auth.AuthState
import com.nanobotkt.feature.auth.GatewayConfigurationError
import com.nanobotkt.feature.auth.gatewayConfigurationErrorMessage
import com.nanobotkt.feature.automations.AutomationsScreen
import com.nanobotkt.feature.channels.ChannelsScreen
import com.nanobotkt.feature.chat.ChatScreen
import com.nanobotkt.feature.chat.ChatViewModel
import com.nanobotkt.feature.chat.ConversationListItem
import com.nanobotkt.feature.chat.ConversationListScreen
import com.nanobotkt.feature.security.SecurityScreen
import com.nanobotkt.feature.settings.SETTINGS_SECTION_MODELS
import com.nanobotkt.feature.settings.SettingsScreen
import com.nanobotkt.feature.sidebar.SidebarUiState
import com.nanobotkt.feature.sidebar.SidebarViewModel
import com.nanobotkt.feature.skills.SkillsScreen
import com.nanobotkt.feature.workspaces.ui.WorkspacesScreen


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
                    onReconfigure = appViewModel::editGatewayConfiguration,
                )
                is AuthState.Ready -> ReadyRoot(state.sessionEpoch, appViewModel)
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
private fun UnreachableScreen(
    error: GatewayConfigurationError,
    serverUrl: String,
    onRetry: () -> Unit,
    onReconfigure: () -> Unit,
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
            // 和“重新配置”两个出口；用户不再被困在只允许换密码的旧流程。
            Text(serverUrl, color = MaterialTheme.colorScheme.onSurfaceVariant)
            Text(
                gatewayConfigurationErrorMessage(error),
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Button(onClick = onRetry) {
                Icon(Icons.Rounded.Refresh, contentDescription = null)
                Text(stringResource(R.string.retry), Modifier.padding(start = 8.dp))
            }
            OutlinedButton(onClick = onReconfigure) {
                Text(stringResource(R.string.reconfigure_gateway))
            }
        }
    }
}

@Composable
private fun ReadyRoot(
    sessionEpoch: Long,
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

    LaunchedEffect(sessionEpoch) { sidebarViewModel.refresh() }
    val visibleSessions = remember(sidebar.sessions, sidebar.sidebar) {
        sidebar.sessions.filter { session ->
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
    // Sheet 需要同时拿到 active/archived 两种前端展示集合。两者都来自同一份 Sidebar
    // 快照，归档只是客户端过滤，不改变服务端返回的数据或会话选择算法。
    val conversationItems = remember(sidebar.sessions, sidebar.sidebar) {
        sidebar.sessions
            .filter { it.key !in sidebar.sidebar.archivedKeys }
            .map { session ->
                ConversationListItem(
                    key = session.key,
                    title = session.displayTitle(sidebar),
                    preview = session.preview,
                    pinned = session.key in sidebar.sidebar.pinnedKeys,
                    archived = false,
                )
            }
    }
    val archivedConversationItems = remember(sidebar.sessions, sidebar.sidebar) {
        sidebar.sessions
            .filter { it.key in sidebar.sidebar.archivedKeys }
            .map { session ->
                ConversationListItem(
                    key = session.key,
                    title = session.displayTitle(sidebar),
                    preview = session.preview,
                    pinned = session.key in sidebar.sidebar.pinnedKeys,
                    archived = true,
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
            // 否则 Root 虽然没有 selectedKey，聊天页仍会持有已删除的 chatId。
            sidebar.loaded && selectedKey == null && !draftingNewTopic -> chatViewModel.startNewTopic()
        }
    }

    // 移除 ModalNavigationDrawer 后，Root 直接渲染当前目的地。所有原抽屉入口都由
    // Settings Home 通过回调交给 app 组合根打开，Settings feature 不依赖兄弟 feature。
    Box(modifier = Modifier.fillMaxSize()) {
        when (destination) {
            AppDestination.CHAT -> ChatScreen(
                viewModel = chatViewModel,
                title = selected?.displayTitle(sidebar) ?: stringResource(R.string.new_topic),
                onOpenSettings = appViewModel::openSettings,
                conversationItems = conversationItems,
                archivedConversationItems = archivedConversationItems,
                selectedConversationKey = selectedKey,
                onSelectConversation = { item -> appViewModel.selectSession(item.key) },
                onNewConversation = {
                    appViewModel.beginNewTopic()
                    chatViewModel.startNewTopic()
                },
                onToggleConversationPinned = sidebarViewModel::togglePinned,
                onRenameConversation = { item, title -> sidebarViewModel.rename(item.key, title) },
                onArchiveConversation = sidebarViewModel::toggleArchived,
                onDeleteConversation = { item -> sidebarViewModel.delete(item.key) },
                onToggleTheme = appViewModel::toggleTheme,
                onOpenModelSettings = {
                    // 模型快捷入口来源是 Chat，因此返回时不经过 Settings Home。
                    appViewModel.openSettings(SETTINGS_SECTION_MODELS)
                },
                // Chat feature 只接收 TransportStatus 这一最小只读边界，用于顶部状态展示；
                // WebSocket 重连与生命周期仍由 AppViewModel/Transport 管理，避免 UI 产生第二状态源。
                transportStatus = transport.status,
                onSessionCreated = { key ->
                    if (selectedKey != key) {
                        // 新会话出现在 Sidebar 前继续保留 drafting guard，避免传播窗口内
                        // 被第一条旧会话抢占；只在刷新后由 reconcileSessionSelection 清除。
                        appViewModel.updateSessionSelection(SessionSelection(key, draftingNewTopic))
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
                onNewTopic = {
                    appViewModel.beginNewTopic()
                    chatViewModel.startNewTopic()
                    appViewModel.navigate(AppDestination.CHAT)
                },
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
