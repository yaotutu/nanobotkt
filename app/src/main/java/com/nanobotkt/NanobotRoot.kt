package com.nanobotkt

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Add
import androidx.compose.material.icons.automirrored.rounded.Logout
import androidx.compose.material.icons.rounded.Menu
import androidx.compose.material.icons.rounded.Refresh
import androidx.compose.material.icons.rounded.SmartToy
import androidx.compose.material.icons.rounded.Apps
import androidx.compose.material.icons.rounded.AutoAwesome
import androidx.compose.material.icons.rounded.Schedule
import androidx.compose.material.icons.rounded.Settings
import androidx.compose.material.icons.rounded.Security
import androidx.compose.material.icons.rounded.Hub
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.ListItem
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalDrawerSheet
import androidx.compose.material3.ModalNavigationDrawer
import androidx.compose.material3.NavigationDrawerItem
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.core.view.WindowCompat
import com.nanobotkt.core.designsystem.NanobotTheme
import com.nanobotkt.core.designsystem.NanobotThemeDefaults
import com.nanobotkt.core.model.ChatSummary
import com.nanobotkt.core.persistence.DensityPreference
import com.nanobotkt.core.persistence.ThemePreference
import com.nanobotkt.core.transport.TransportStatus
import com.nanobotkt.feature.auth.AuthScreen
import com.nanobotkt.feature.auth.AuthState
import com.nanobotkt.feature.chat.ChatScreen
import com.nanobotkt.feature.chat.ChatViewModel
import com.nanobotkt.feature.chat.ConversationListItem
import com.nanobotkt.feature.chat.ConversationListScreen
import com.nanobotkt.feature.sidebar.SidebarUiState
import com.nanobotkt.feature.sidebar.SidebarViewModel
import com.nanobotkt.feature.apps.AppsScreen
import com.nanobotkt.feature.automations.AutomationsScreen
import com.nanobotkt.feature.channels.ChannelsScreen
import com.nanobotkt.feature.security.SecurityScreen
import com.nanobotkt.feature.settings.SETTINGS_SECTION_MODELS
import com.nanobotkt.feature.settings.SETTINGS_SECTION_OVERVIEW
import com.nanobotkt.feature.settings.SettingsScreen
import com.nanobotkt.feature.skills.SkillsScreen
import com.nanobotkt.feature.workspaces.ui.WorkspacesScreen
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
                is AuthState.Authentication -> AuthScreen(state, appViewModel::authenticate)
                is AuthState.Unreachable -> UnreachableScreen(state.message, appViewModel::retry)
                is AuthState.Ready -> ReadyRoot(state.sessionEpoch, state.tokenGeneration, appViewModel)
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
private fun UnreachableScreen(message: String, onRetry: () -> Unit) {
    Box(Modifier.fillMaxSize().padding(24.dp), contentAlignment = Alignment.Center) {
        Column(horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(16.dp)) {
            Icon(Icons.Rounded.SmartToy, contentDescription = null, Modifier.size(56.dp), tint = MaterialTheme.colorScheme.error)
            Text(stringResource(R.string.gateway_unreachable), style = MaterialTheme.typography.headlineSmall)
            Text(message, color = MaterialTheme.colorScheme.onSurfaceVariant)
            Button(onClick = onRetry) {
                Icon(Icons.Rounded.Refresh, contentDescription = null)
                Text(stringResource(R.string.retry), Modifier.padding(start = 8.dp))
            }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun ReadyRoot(
    sessionEpoch: Long,
    tokenGeneration: Long,
    appViewModel: AppViewModel,
    sidebarViewModel: SidebarViewModel = hiltViewModel(),
    chatViewModel: ChatViewModel = hiltViewModel(),
) {
    val sidebar by sidebarViewModel.state.collectAsStateWithLifecycle()
    val transport by appViewModel.transportState.collectAsStateWithLifecycle()
    val drawerState = androidx.compose.material3.rememberDrawerState(androidx.compose.material3.DrawerValue.Closed)
    val scope = rememberCoroutineScope()
    val rootUiState by appViewModel.rootUiState.collectAsStateWithLifecycle()
    val selectedKey = rootUiState.selectedKey
    val destination = rootUiState.destination
    val draftingNewTopic = rootUiState.draftingNewTopic

    BackHandler(enabled = destination != AppDestination.CHAT) {
        appViewModel.navigate(AppDestination.CHAT)
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

    ModalNavigationDrawer(
        drawerState = drawerState,
        scrimColor = MaterialTheme.colorScheme.scrim.copy(alpha = 0.32f),
        drawerContent = {
            ModalDrawerSheet(
                modifier = Modifier
                    .fillMaxWidth(0.9f)
                    .widthIn(max = 360.dp),
                // Drawer 使用 surface 作为容器，选中项再通过 Material 3 的 container role
                // 提供层级；这样浅色和深色不会再依赖旧版黑白灰背景。
                drawerContainerColor = MaterialTheme.colorScheme.surface,
                drawerContentColor = MaterialTheme.colorScheme.onSurface,
            ) {
                SidebarContent(
                    destination = destination,
                    onClose = { scope.launch { drawerState.close() } },
                    transportStatus = transport.status,
                    onNewTopic = {
                        appViewModel.beginNewTopic()
                        chatViewModel.startNewTopic()
                        scope.launch { drawerState.close() }
                    },
                    onRefresh = sidebarViewModel::refresh,
                    onReconnect = appViewModel::reconnect,
                    onLogout = appViewModel::logout,
                    onNavigate = { target ->
                        if (target == AppDestination.SETTINGS) {
                            appViewModel.openSettings(SETTINGS_SECTION_OVERVIEW)
                        } else {
                            appViewModel.navigate(target)
                        }
                        scope.launch { drawerState.close() }
                    },
                )
            }
        },
    ) {
        Box(modifier = Modifier.fillMaxSize()) {
            when (destination) {
            AppDestination.CHAT -> ChatScreen(
                viewModel = chatViewModel,
                title = selected?.displayTitle(sidebar) ?: stringResource(R.string.new_topic),
                onOpenDrawer = { scope.launch { drawerState.open() } },
                // 会话入口只打开 ChatScreen 内的 Sheet；保留参数是为了兼容旧页面调用方，
                // 但此处不再切换 AppDestination，避免聊天消息树被销毁重建。
                onOpenConversationList = {},
                conversationItems = conversationItems,
                archivedConversationItems = archivedConversationItems,
                selectedConversationKey = selectedKey,
                onSelectConversation = { item ->
                    appViewModel.selectSession(item.key)
                },
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
                    appViewModel.openSettings(SETTINGS_SECTION_MODELS)
                },
                // Chat feature 只接收 TransportStatus 这一最小只读边界，用于顶部状态展示；
                // WebSocket 重连与生命周期仍由 AppViewModel/Transport 管理，避免 UI 产生第二状态源。
                transportStatus = transport.status,
                onSessionCreated = { key ->
                    if (selectedKey != key) {
                        // Keep the draft guard active until the refreshed sidebar actually contains
                        // the newly-created session. Otherwise the selection reconciliation can fall
                        // back to the first old topic while creation is still propagating.
                        appViewModel.updateSessionSelection(SessionSelection(key, draftingNewTopic))
                        sidebarViewModel.refresh()
                    }
                },
            )
            AppDestination.CONVERSATIONS -> ConversationListScreen(
                items = conversationItems,
                selectedKey = selectedKey,
                onBack = { appViewModel.navigate(AppDestination.CHAT) },
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
            AppDestination.WORKSPACES -> WorkspacesScreen(onBack = { appViewModel.navigate(AppDestination.CHAT) })
            AppDestination.APPS -> AppsScreen(onBack = { appViewModel.navigate(AppDestination.CHAT) })
            AppDestination.SKILLS -> SkillsScreen(onBack = { appViewModel.navigate(AppDestination.CHAT) })
            AppDestination.AUTOMATIONS -> AutomationsScreen(onBack = { appViewModel.navigate(AppDestination.CHAT) })
            AppDestination.CHANNELS -> ChannelsScreen(onBack = { appViewModel.navigate(AppDestination.CHAT) })
            AppDestination.SECURITY -> SecurityScreen(onBack = { appViewModel.navigate(AppDestination.CHAT) })
            AppDestination.SETTINGS -> SettingsScreen(
                onBack = { appViewModel.navigate(AppDestination.CHAT) },
                onOpenChannels = { appViewModel.navigate(AppDestination.CHANNELS) },
                onLogout = appViewModel::logout,
                initialSection = rootUiState.settingsSection,
                onSectionChange = appViewModel::setSettingsSection,
                refreshKey = tokenGeneration,
            )
            }
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


@Composable
private fun SidebarContent(
    destination: AppDestination,
    transportStatus: TransportStatus,
    onNewTopic: () -> Unit,
    onClose: () -> Unit,
    onRefresh: () -> Unit,
    onReconnect: () -> Unit,
    onLogout: () -> Unit,
    onNavigate: (AppDestination) -> Unit,
) {
    val spacing = NanobotThemeDefaults.spacing
    val colors = MaterialTheme.colorScheme

    Surface(
        modifier = Modifier.fillMaxSize(),
        color = colors.surface,
        contentColor = colors.onSurface,
    ) {
        Column(modifier = Modifier.fillMaxSize()) {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    // DrawerSheet 默认 inset 在不同 Material3 版本上略有差异，显式保留状态栏
                    // 安全区，避免 Logo 和关闭按钮落入系统栏；不改变 Drawer 的业务行为。
                    .statusBarsPadding()
                    .padding(horizontal = spacing.md, vertical = spacing.xs),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                // 使用 Material 图标作为 Drawer 品牌入口，避免把旧版 Nanobot 图形和品牌色
                // 带回新的视觉基线；它只表达“助手”语义，不参与任何业务状态。
                Icon(
                    imageVector = Icons.Rounded.SmartToy,
                    contentDescription = "nanobot",
                    modifier = Modifier.size(32.dp),
                    tint = colors.primary,
                )
                Spacer(Modifier.weight(1f))
                IconButton(onClick = onClose) {
                    Icon(
                        imageVector = Icons.Rounded.Menu,
                        contentDescription = "Close menu",
                    )
                }
            }

            FilledTonalButton(
                onClick = onNewTopic,
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = spacing.md, vertical = spacing.xs),
            ) {
                Icon(Icons.Rounded.Add, contentDescription = null)
                Text(
                    text = stringResource(R.string.new_topic),
                    modifier = Modifier.padding(start = spacing.xs),
                )
            }

            // Drawer 只保留应用级导航，不再渲染会话列表、会话搜索或会话操作菜单。
            // 会话切换与归档等业务入口仍由独立 ConversationList 页面承载，因此这里只移除
            // 侧边栏中的呈现模块，不改变任何 ViewModel、Repository 或会话状态转换。
            LazyColumn(
                modifier = Modifier
                    .weight(1f)
                    .fillMaxWidth(),
                contentPadding = androidx.compose.foundation.layout.PaddingValues(bottom = spacing.xs),
            ) {
                item {
                    SidebarNavRow(label = "Apps", icon = Icons.Rounded.Apps, selected = destination == AppDestination.APPS) {
                        onNavigate(AppDestination.APPS)
                    }
                }
                item {
                    SidebarNavRow(label = "Skills", icon = Icons.Rounded.AutoAwesome, selected = destination == AppDestination.SKILLS) {
                        onNavigate(AppDestination.SKILLS)
                    }
                }
                item {
                    SidebarNavRow(label = "Automations", icon = Icons.Rounded.Schedule, selected = destination == AppDestination.AUTOMATIONS) {
                        onNavigate(AppDestination.AUTOMATIONS)
                    }
                }
                item {
                    // Pairing 请求仍从常规导航可达；selected 只反映当前目的地，不改变请求处理逻辑。
                    SidebarNavRow(
                        label = "Security & pairing",
                        icon = Icons.Rounded.Security,
                        selected = destination == AppDestination.SECURITY,
                    ) { onNavigate(AppDestination.SECURITY) }
                }
            }

            HorizontalDivider(color = colors.outlineVariant)
            ListItem(
                headlineContent = { Text("Settings") },
                supportingContent = {
                    Text(
                        text = transportStatus.displayName(),
                        color = colors.onSurfaceVariant,
                    )
                },
                leadingContent = { Icon(Icons.Rounded.Settings, contentDescription = null) },
                trailingContent = {
                    Row {
                        IconButton(onClick = onRefresh) {
                            Icon(
                                imageVector = Icons.Rounded.Refresh,
                                contentDescription = "Refresh",
                            )
                        }
                        IconButton(onClick = onReconnect) {
                            Icon(
                                imageVector = Icons.Rounded.Hub,
                                contentDescription = stringResource(R.string.reconnect),
                                tint = transportStatus.statusColor(),
                            )
                        }
                    }
                },
                modifier = Modifier
                    .fillMaxWidth()
                    .clickable { onNavigate(AppDestination.SETTINGS) }
                    .navigationBarsPadding(),
                colors = androidx.compose.material3.ListItemDefaults.colors(
                    containerColor = colors.surface,
                ),
            )
            NavigationDrawerItem(
                label = { Text(stringResource(R.string.logout)) },
                selected = false,
                onClick = onLogout,
                icon = { Icon(Icons.AutoMirrored.Rounded.Logout, contentDescription = null) },
                modifier = Modifier.padding(horizontal = spacing.sm, vertical = spacing.xxs),
            )
        }
    }
}

@Composable
private fun SidebarNavRow(
    label: String,
    icon: androidx.compose.ui.graphics.vector.ImageVector,
    selected: Boolean = false,
    onClick: () -> Unit,
) {
    val spacing = NanobotThemeDefaults.spacing
    NavigationDrawerItem(
        label = { Text(label) },
        selected = selected,
        onClick = onClick,
        icon = { Icon(imageVector = icon, contentDescription = null) },
        modifier = Modifier.padding(horizontal = spacing.sm, vertical = spacing.xxs),
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

@Composable
private fun TransportStatus.statusColor(): Color = when (this) {
    // 连接状态使用语义角色而不是固定绿/黄/红，保证 Light/Dark 下都能维持对比度。
    TransportStatus.OPEN -> MaterialTheme.colorScheme.primary
    TransportStatus.CONNECTING, TransportStatus.RECONNECTING -> MaterialTheme.colorScheme.secondary
    TransportStatus.ERROR -> MaterialTheme.colorScheme.error
    TransportStatus.CLOSED, TransportStatus.IDLE -> MaterialTheme.colorScheme.onSurfaceVariant
}
