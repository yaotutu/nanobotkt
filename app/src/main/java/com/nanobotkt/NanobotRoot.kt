package com.nanobotkt

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.clickable
import androidx.compose.foundation.background
import androidx.compose.foundation.Image
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.ui.graphics.RectangleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Add
import androidx.compose.material.icons.rounded.Archive
import androidx.compose.material.icons.rounded.Unarchive
import androidx.compose.material.icons.rounded.ChatBubbleOutline
import androidx.compose.material.icons.rounded.Delete
import androidx.compose.material.icons.rounded.Edit
import androidx.compose.material.icons.rounded.ExpandLess
import androidx.compose.material.icons.rounded.ExpandMore
import androidx.compose.material.icons.automirrored.rounded.Logout
import androidx.compose.material.icons.rounded.MoreVert
import androidx.compose.material.icons.rounded.MoreHoriz
import androidx.compose.material.icons.rounded.Menu
import androidx.compose.material.icons.rounded.PushPin
import androidx.compose.material.icons.rounded.Refresh
import androidx.compose.material.icons.rounded.Search
import androidx.compose.material.icons.rounded.SmartToy
import androidx.compose.material.icons.rounded.Apps
import androidx.compose.material.icons.rounded.AutoAwesome
import androidx.compose.material.icons.rounded.Schedule
import androidx.compose.material.icons.rounded.Settings
import androidx.compose.material.icons.rounded.Security
import androidx.compose.material.icons.rounded.Workspaces
import androidx.compose.material.icons.rounded.Hub
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
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
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.blur
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.core.view.WindowCompat
import com.nanobotkt.core.designsystem.NanobotTheme
import com.nanobotkt.core.model.ChatSummary
import com.nanobotkt.core.persistence.DensityPreference
import com.nanobotkt.core.persistence.ThemePreference
import com.nanobotkt.core.transport.TransportStatus
import com.nanobotkt.feature.auth.AuthScreen
import com.nanobotkt.feature.auth.AuthState
import com.nanobotkt.feature.chat.ChatScreen
import com.nanobotkt.feature.chat.ChatViewModel
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
        scrimColor = Color.Black.copy(alpha = 0.34f),
        drawerContent = {
            ModalDrawerSheet(
                modifier = Modifier.width(266.dp),
                drawerShape = RectangleShape,
                drawerContainerColor = MaterialTheme.colorScheme.surfaceVariant,
                drawerContentColor = Color(0xFF171717),
            ) {
                SidebarContent(
                    state = sidebar,
                    selectedKey = selectedKey,
                    onClose = { scope.launch { drawerState.close() } },
                    transportStatus = transport.status,
                    onSelect = { session ->
                        appViewModel.selectSession(session.key)
                        scope.launch { drawerState.close() }
                    },
                    onNewTopic = {
                        appViewModel.beginNewTopic()
                        chatViewModel.startNewTopic()
                        scope.launch { drawerState.close() }
                    },
                    onRefresh = sidebarViewModel::refresh,
                    onReconnect = appViewModel::reconnect,
                    onLogout = appViewModel::logout,
                    onTogglePinned = sidebarViewModel::togglePinned,
                    onToggleArchived = sidebarViewModel::toggleArchived,
                    onRename = sidebarViewModel::rename,
                    onDelete = sidebarViewModel::delete,
                    onShowArchived = sidebarViewModel::showArchived,
                    onToggleGroup = sidebarViewModel::toggleGroup,
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
        Box(
            modifier = Modifier
                .fillMaxSize()
                .blur(if (drawerState.isOpen) 4.dp else 0.dp),
        ) {
            when (destination) {
            AppDestination.CHAT -> ChatScreen(
                viewModel = chatViewModel,
                title = selected?.displayTitle(sidebar) ?: stringResource(R.string.new_topic),
                onOpenDrawer = { scope.launch { drawerState.open() } },
                onToggleTheme = appViewModel::toggleTheme,
                onOpenModelSettings = {
                    appViewModel.openSettings(SETTINGS_SECTION_MODELS)
                },
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
    state: SidebarUiState,
    selectedKey: String?,
    transportStatus: TransportStatus,
    onSelect: (ChatSummary) -> Unit,
    onNewTopic: () -> Unit,
    onClose: () -> Unit,
    onRefresh: () -> Unit,
    onReconnect: () -> Unit,
    onLogout: () -> Unit,
    onTogglePinned: (String) -> Unit,
    onToggleArchived: (String) -> Unit,
    onRename: (String, String) -> Unit,
    onDelete: (String) -> Unit,
    onShowArchived: (Boolean) -> Unit,
    onToggleGroup: (String) -> Unit,
    onNavigate: (AppDestination) -> Unit,
) {
    var query by rememberSaveable { mutableStateOf("") }
    var searchActive by rememberSaveable { mutableStateOf(false) }
    var topicsExpanded by rememberSaveable { mutableStateOf(true) }
    var renameTarget by remember { mutableStateOf<ChatSummary?>(null) }
    var deleteTarget by remember { mutableStateOf<ChatSummary?>(null) }
    val archived = state.sidebar.archivedKeys.toSet()
    val filtered = state.sessions.filter { session ->
        (state.sidebar.view.showArchived || session.key !in archived) &&
            (query.isBlank() || session.displayTitle(state).contains(query, ignoreCase = true) || session.preview.contains(query, ignoreCase = true))
    }
    val pinned = filtered.filter { it.key in state.sidebar.pinnedKeys && it.key !in archived }
    val active = filtered.filter { it.key !in state.sidebar.pinnedKeys && it.key !in archived }
    val archivedItems = filtered.filter { it.key in archived }
    // Keep the existing selection callbacks and ordering semantics, but present every
    // conversation as the flat Topics list used by the reference sidebar.
    val orderedTopics = pinned + active
    val totalActiveTopics = orderedTopics.size
    val visibleTopics = if (topicsExpanded || query.isNotBlank()) orderedTopics else orderedTopics.take(5)

    Column(
        modifier = Modifier
            .fillMaxSize()
            .background(MaterialTheme.colorScheme.surfaceVariant),
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                // DrawerSheet 在不同 Material3 版本上的默认 inset 行为并不完全一致，
                // 顶部显式保留状态栏安全区，避免 Logo/关闭按钮被状态栏覆盖。
                .statusBarsPadding()
                .height(56.dp)
                .padding(horizontal = 10.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Image(
                painter = painterResource(R.drawable.nanobot_mark),
                contentDescription = "nanobot",
                modifier = Modifier.size(28.dp),
            )
            Spacer(Modifier.weight(1f))
            IconButton(onClick = onClose, modifier = Modifier.size(36.dp)) {
                Icon(
                    imageVector = Icons.Rounded.Menu,
                    contentDescription = "Close menu",
                    modifier = Modifier.size(17.dp),
                    tint = Color(0xFF777777),
                )
            }
        }

        SidebarNavRow(
            label = stringResource(R.string.new_topic),
            icon = Icons.Rounded.Edit,
            onClick = onNewTopic,
        )
        SidebarNavRow(
            label = "Search",
            icon = Icons.Rounded.Search,
            onClick = {
                searchActive = !searchActive
                if (!searchActive) query = ""
            },
        )
        if (searchActive) {
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 10.dp, vertical = 3.dp)
                    .height(38.dp)
                    .clip(RoundedCornerShape(7.dp))
                    .background(Color(0xFFF3F3F3))
                    .padding(horizontal = 10.dp),
                contentAlignment = Alignment.CenterStart,
            ) {
                BasicTextField(
                    value = query,
                    onValueChange = { query = it },
                    singleLine = true,
                    textStyle = MaterialTheme.typography.bodyMedium.copy(color = Color(0xFF171717), fontSize = 14.sp),
                    modifier = Modifier.fillMaxWidth(),
                    decorationBox = { innerTextField ->
                        if (query.isEmpty()) {
                            Text(
                                text = stringResource(R.string.search_topics),
                                color = Color(0xFF9A9A9A),
                                fontSize = 14.sp,
                            )
                        }
                        innerTextField()
                    },
                )
            }
        }
        SidebarNavRow(label = "Apps", icon = Icons.Rounded.Apps) { onNavigate(AppDestination.APPS) }
        SidebarNavRow(label = "Skills", icon = Icons.Rounded.AutoAwesome) { onNavigate(AppDestination.SKILLS) }
        SidebarNavRow(label = "Automations", icon = Icons.Rounded.Schedule) { onNavigate(AppDestination.AUTOMATIONS) }
        // Pairing 请求需要从常规导航可达，否则用户无法批准或拒绝来自渠道的请求。
        SidebarNavRow(label = "Security & pairing", icon = Icons.Rounded.Security) { onNavigate(AppDestination.SECURITY) }
        SidebarNavRow(
            label = stringResource(R.string.show_archived),
            icon = Icons.Rounded.Archive,
            onClick = { onShowArchived(!state.sidebar.view.showArchived) },
        )

        LazyColumn(
            modifier = Modifier
                .weight(1f)
                .fillMaxWidth(),
            contentPadding = androidx.compose.foundation.layout.PaddingValues(bottom = 8.dp),
        ) {
            item { SidebarSectionHeader(stringResource(R.string.topics)) }

            if (state.loading) {
                item {
                    Box(
                        modifier = Modifier
                            .fillMaxWidth()
                            .height(72.dp),
                        contentAlignment = Alignment.Center,
                    ) {
                        CircularProgressIndicator(
                            modifier = Modifier.size(22.dp),
                            strokeWidth = 2.dp,
                            color = Color(0xFF777777),
                        )
                    }
                }
            } else {
                items(visibleTopics, key = { it.key }) { session ->
                    SessionItem(session, state, selectedKey == session.key, onSelect, onTogglePinned, onToggleArchived, { renameTarget = session }, { deleteTarget = session })
                }
                if (archivedItems.isNotEmpty()) {
                    item { SidebarSectionHeader(stringResource(R.string.archived)) }
                    items(archivedItems, key = { it.key }) { session ->
                        SessionItem(session, state, selectedKey == session.key, onSelect, onTogglePinned, onToggleArchived, { renameTarget = session }, { deleteTarget = session })
                    }
                }
                if (filtered.isEmpty()) {
                    item {
                        Text(
                            text = if (query.isBlank()) stringResource(R.string.no_topics) else stringResource(R.string.no_search_results),
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(horizontal = 10.dp, vertical = 12.dp),
                            color = Color(0xFF8E8E8E),
                            fontSize = 14.sp,
                        )
                    }
                }
                if (totalActiveTopics > 5 && query.isBlank()) {
                    item {
                        Text(
                            text = if (topicsExpanded) "Show less" else "Show more",
                            modifier = Modifier
                                .padding(start = 18.dp, top = 5.dp, bottom = 8.dp)
                                .clickable { topicsExpanded = !topicsExpanded }
                                .padding(vertical = 4.dp),
                            color = Color(0xFFA0A0A0),
                            fontSize = 13.sp,
                        )
                    }
                }
            }
        }

        Row(
            modifier = Modifier
                .fillMaxWidth()
                .navigationBarsPadding()
                .height(70.dp)
                .clickable { onNavigate(AppDestination.SETTINGS) }
                .padding(horizontal = 10.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Icon(
                imageVector = Icons.Rounded.Settings,
                contentDescription = null,
                modifier = Modifier.size(18.dp),
                tint = Color(0xFF343434),
            )
            Text(
                text = "Settings",
                modifier = Modifier.padding(start = 10.dp).weight(1f),
                color = Color(0xFF171717),
                fontSize = 14.sp,
            )
            Box(
                modifier = Modifier
                    .size(28.dp)
                    .clickable(onClick = onReconnect),
                contentAlignment = Alignment.Center,
            ) {
                Box(
                    modifier = Modifier
                        .size(8.dp)
                        .clip(CircleShape)
                        .background(transportStatus.statusColor()),
                )
            }
        }
    }

    renameTarget?.let { target ->
        RenameDialog(
            initial = target.displayTitle(state),
            onDismiss = { renameTarget = null },
            onConfirm = { onRename(target.key, it); renameTarget = null },
        )
    }
    deleteTarget?.let { target ->
        AlertDialog(
            onDismissRequest = { deleteTarget = null },
            title = { Text(stringResource(R.string.delete_topic)) },
            text = { Text(stringResource(R.string.delete_topic_confirmation, target.displayTitle(state))) },
            confirmButton = {
                TextButton(onClick = { onDelete(target.key); deleteTarget = null }) {
                    Text(stringResource(R.string.delete), color = MaterialTheme.colorScheme.error)
                }
            },
            dismissButton = { TextButton(onClick = { deleteTarget = null }) { Text(stringResource(R.string.cancel)) } },
        )
    }
}

@Composable
private fun SidebarNavRow(
    label: String,
    icon: androidx.compose.ui.graphics.vector.ImageVector,
    onClick: () -> Unit,
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .height(50.dp)
            .clickable(onClick = onClick)
            .padding(horizontal = 10.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Icon(
            imageVector = icon,
            contentDescription = null,
            modifier = Modifier.size(18.dp),
            tint = Color(0xFF343434),
        )
        Text(
            text = label,
            modifier = Modifier.padding(start = 10.dp),
            color = Color(0xFF171717),
            fontSize = 14.sp,
            fontWeight = FontWeight.Normal,
        )
    }
}

@Composable
private fun SidebarSectionHeader(title: String) {
    Text(
        text = title,
        modifier = Modifier
            .fillMaxWidth()
            .padding(start = 10.dp, top = 8.dp, bottom = 4.dp),
        color = Color(0xFF9A9A9A),
        fontSize = 13.sp,
        fontWeight = FontWeight.Normal,
    )
}

@Composable
private fun SessionItem(
    session: ChatSummary,
    state: SidebarUiState,
    selected: Boolean,
    onSelect: (ChatSummary) -> Unit,
    onTogglePinned: (String) -> Unit,
    onToggleArchived: (String) -> Unit,
    onRename: () -> Unit,
    onDelete: () -> Unit,
) {
    var menuOpen by remember { mutableStateOf(false) }
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 5.dp)
            .height(34.dp)
            .clip(RoundedCornerShape(6.dp))
            .background(if (selected) Color(0xFFF4F4F4) else Color.Transparent)
            .clickable { onSelect(session) }
            .padding(start = 5.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(
            text = session.displayTitle(state),
            modifier = Modifier.weight(1f),
            color = Color(0xFF1C1C1C),
            fontSize = 14.sp,
            fontWeight = FontWeight.Normal,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
        )
        Box {
            IconButton(
                onClick = { menuOpen = true },
                modifier = Modifier.size(30.dp),
            ) {
                Icon(
                    imageVector = Icons.Rounded.MoreHoriz,
                    contentDescription = stringResource(R.string.topic_actions),
                    modifier = Modifier.size(17.dp),
                    tint = Color(0xFFB8B8B8),
                )
            }
            DropdownMenu(expanded = menuOpen, onDismissRequest = { menuOpen = false }) {
                DropdownMenuItem(
                    text = { Text(if (session.key in state.sidebar.pinnedKeys) stringResource(R.string.unpin) else stringResource(R.string.pin)) },
                    leadingIcon = { Icon(Icons.Rounded.PushPin, contentDescription = null) },
                    onClick = { menuOpen = false; onTogglePinned(session.key) },
                )
                DropdownMenuItem(
                    text = { Text(stringResource(R.string.rename)) },
                    leadingIcon = { Icon(Icons.Rounded.Edit, contentDescription = null) },
                    onClick = { menuOpen = false; onRename() },
                )
                val archived = session.key in state.sidebar.archivedKeys
                DropdownMenuItem(
                    text = { Text(if (archived) stringResource(R.string.unarchive) else stringResource(R.string.archive)) },
                    leadingIcon = { Icon(if (archived) Icons.Rounded.Unarchive else Icons.Rounded.Archive, contentDescription = null) },
                    onClick = { menuOpen = false; onToggleArchived(session.key) },
                )
                DropdownMenuItem(
                    text = { Text(stringResource(R.string.delete), color = MaterialTheme.colorScheme.error) },
                    leadingIcon = { Icon(Icons.Rounded.Delete, contentDescription = null, tint = MaterialTheme.colorScheme.error) },
                    onClick = { menuOpen = false; onDelete() },
                )
            }
        }
    }
}

@Composable
private fun RenameDialog(initial: String, onDismiss: () -> Unit, onConfirm: (String) -> Unit) {
    var value by remember(initial) { mutableStateOf(initial) }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(R.string.rename_topic)) },
        text = { OutlinedTextField(value = value, onValueChange = { value = it }, singleLine = true) },
        confirmButton = { TextButton(onClick = { onConfirm(value.trim()) }, enabled = value.isNotBlank()) { Text(stringResource(R.string.save)) } },
        dismissButton = { TextButton(onClick = onDismiss) { Text(stringResource(R.string.cancel)) } },
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

private fun TransportStatus.statusColor(): Color = when (this) {
    TransportStatus.OPEN -> Color(0xFF2E7D32)
    TransportStatus.CONNECTING, TransportStatus.RECONNECTING -> Color(0xFFF9A825)
    TransportStatus.ERROR -> Color(0xFFC62828)
    TransportStatus.CLOSED, TransportStatus.IDLE -> Color(0xFF757575)
}
