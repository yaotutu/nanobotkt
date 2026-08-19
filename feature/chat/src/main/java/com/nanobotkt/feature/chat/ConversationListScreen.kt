package com.nanobotkt.feature.chat

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.ArrowBack
import androidx.compose.material.icons.rounded.Add
import androidx.compose.material.icons.rounded.Archive
import androidx.compose.material.icons.rounded.ChatBubbleOutline
import androidx.compose.material.icons.rounded.Close
import androidx.compose.material.icons.rounded.Delete
import androidx.compose.material.icons.rounded.Edit
import androidx.compose.material.icons.rounded.MoreHoriz
import androidx.compose.material.icons.rounded.PushPin
import androidx.compose.material.icons.rounded.Search
import androidx.compose.material.icons.rounded.Settings
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.ListItem
import androidx.compose.material3.ListItemDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.draw.clip
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.nanobotkt.core.designsystem.NanobotNavigationRow
import com.nanobotkt.core.designsystem.NanobotSectionHeader

/**
 * 会话列表只依赖这个轻量 UI 模型，避免聊天 feature 反向依赖 app 的 Root 状态或 Sidebar UI。
 * 标题覆盖、置顶状态等组合逻辑由 app 组合根完成后再传入页面。
 */
data class ConversationListItem(
    val key: String,
    val title: String,
    val preview: String,
    val pinned: Boolean,
    /** 归档态由客户端已有 Sidebar 快照推导，不会改变服务端数据结构。 */
    val archived: Boolean = false,
    /** Sidebar mutation 未完成时禁用管理入口并显示进度，避免同一行重复提交。 */
    val pending: Boolean = false,
    /** 当前会话有 agent turn 在运行。 */
    val running: Boolean = false,
    /** 非当前会话在上次查看后完成了新活动。 */
    val unread: Boolean = false,
)

/**
 * 兼容旧导航 destination 的独立会话页。
 *
 * 第一阶段保留这个入口，避免影响深链或恢复中的 destination；主聊天流程使用下面的
 * ConversationListSheet。两者共享同一套列表内容与操作回调，不复制任何业务状态。
 */
@Composable
fun ConversationListScreen(
    items: List<ConversationListItem>,
    selectedKey: String?,
    onBack: () -> Unit,
    onSelect: (ConversationListItem) -> Unit,
    onNewTopic: () -> Unit,
    onTogglePinned: (String) -> Unit,
    onRename: (ConversationListItem, String) -> Unit,
    onArchive: (String) -> Unit,
    onDelete: (ConversationListItem) -> Unit,
    modifier: Modifier = Modifier,
) {
    ConversationListContent(
        items = items,
        selectedKey = selectedKey,
        onBack = onBack,
        onSelect = onSelect,
        onNewTopic = onNewTopic,
        onTogglePinned = onTogglePinned,
        onRename = onRename,
        onArchive = onArchive,
        onDelete = onDelete,
        modifier = modifier,
        showHeader = true,
    )
}

/**
 * 聊天页内的会话 Bottom Sheet。
 *
 * 这里只改变 UI 容器和临时展示模式；会话选择、置顶、重命名、归档、删除仍通过父级
 * 传入的回调进入原有 ViewModel，因此不触碰 Gateway API、WebSocket 或服务端协议。
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ConversationListSheet(
    items: List<ConversationListItem>,
    archivedItems: List<ConversationListItem>,
    selectedKey: String?,
    visible: Boolean,
    onDismiss: () -> Unit,
    onSelect: (ConversationListItem) -> Unit,
    onNewTopic: () -> Unit,
    onTogglePinned: (String) -> Unit,
    onRename: (ConversationListItem, String) -> Unit,
    onArchive: (String) -> Unit,
    onDelete: (ConversationListItem) -> Unit,
    onOpenSettings: () -> Unit,
) {
    if (!visible) return

    val sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)
    var archivedMode by rememberSaveable { mutableStateOf(false) }

    ModalBottomSheet(
        onDismissRequest = onDismiss,
        sheetState = sheetState,
        // 使用 ModalBottomSheet 默认的 Material 3 形状、容器色、scrim 与 elevation，
        // 避免会话 Sheet 维护一套与主题升级脱节的局部 token。
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .heightIn(min = 320.dp, max = 680.dp)
                .navigationBarsPadding(),
        ) {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 16.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                if (archivedMode) {
                    IconButton(onClick = { archivedMode = false }) {
                        Icon(
                            Icons.AutoMirrored.Rounded.ArrowBack,
                            contentDescription = stringResource(R.string.conversation_back),
                        )
                    }
                }
                Text(
                    text = stringResource(
                        if (archivedMode) R.string.conversation_archived_title else R.string.conversation_list_title,
                    ),
                    modifier = Modifier.weight(1f),
                    style = MaterialTheme.typography.titleLarge,
                    fontWeight = FontWeight.SemiBold,
                )
                if (!archivedMode) {
                    // 全局设置与新建会话都属于会话导航层的低频操作。它们只在 Sheet
                    // 打开时出现，不占用聊天主页的顶部、时间轴或输入区空间。
                    IconButton(onClick = onOpenSettings) {
                        Icon(
                            Icons.Rounded.Settings,
                            contentDescription = stringResource(R.string.system_settings),
                        )
                    }
                    IconButton(onClick = onNewTopic) {
                        Icon(
                            Icons.Rounded.Add,
                            contentDescription = stringResource(R.string.conversation_new_topic),
                        )
                    }
                }
            }

            ConversationListContent(
                items = if (archivedMode) archivedItems else items,
                selectedKey = selectedKey,
                onBack = onDismiss,
                onSelect = { item ->
                    // 选择回调内部由父级统一关闭 Sheet；这里不重复触发 dismiss，避免
                    // 新建/切换动作与 ModalBottomSheet 的动画状态发生竞争。
                    onSelect(item)
                },
                onNewTopic = onNewTopic,
                onTogglePinned = onTogglePinned,
                onRename = onRename,
                onArchive = onArchive,
                onDelete = onDelete,
                showHeader = false,
                showArchivedEntry = !archivedMode && archivedItems.isNotEmpty(),
                archivedCount = archivedItems.size,
                onShowArchived = { archivedMode = true },
                modifier = Modifier
                    .fillMaxWidth()
                    .weight(1f),
            )
        }
    }
}

@Composable
private fun ConversationListContent(
    items: List<ConversationListItem>,
    selectedKey: String?,
    onBack: () -> Unit,
    onSelect: (ConversationListItem) -> Unit,
    onNewTopic: () -> Unit,
    onTogglePinned: (String) -> Unit,
    onRename: (ConversationListItem, String) -> Unit,
    onArchive: (String) -> Unit,
    onDelete: (ConversationListItem) -> Unit,
    modifier: Modifier = Modifier,
    showHeader: Boolean,
    showArchivedEntry: Boolean = false,
    archivedCount: Int = 0,
    onShowArchived: () -> Unit = {},
) {
    var query by rememberSaveable { mutableStateOf("") }
    // 输入仅属于当前 UI 实例；关闭 Sheet 后不会写入业务状态，也不会改变会话选择。
    var renameTarget by remember { mutableStateOf<ConversationListItem?>(null) }
    var deleteTarget by remember { mutableStateOf<ConversationListItem?>(null) }

    val normalizedQuery = query.trim()
    val filtered = items.filter { item ->
        normalizedQuery.isBlank() ||
            item.title.contains(normalizedQuery, ignoreCase = true) ||
            item.preview.contains(normalizedQuery, ignoreCase = true) ||
            item.key.contains(normalizedQuery, ignoreCase = true)
    }
    val pinned = filtered.filter(ConversationListItem::pinned)
    val recent = filtered.filterNot(ConversationListItem::pinned)

    Column(
        modifier = modifier.background(MaterialTheme.colorScheme.background),
    ) {
        if (showHeader) {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .height(56.dp)
                    .padding(horizontal = 8.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                IconButton(onClick = onBack) {
                    Icon(
                        Icons.AutoMirrored.Rounded.ArrowBack,
                        contentDescription = stringResource(R.string.conversation_back),
                    )
                }
                Text(
                    text = stringResource(R.string.conversation_list_title),
                    modifier = Modifier.weight(1f),
                    style = MaterialTheme.typography.titleLarge,
                    fontWeight = FontWeight.SemiBold,
                )
                IconButton(onClick = onNewTopic) {
                    Icon(
                        Icons.Rounded.Add,
                        contentDescription = stringResource(R.string.conversation_new_topic),
                    )
                }
            }
        }

        OutlinedTextField(
            value = query,
            onValueChange = { query = it },
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 16.dp, vertical = 2.dp),
            singleLine = true,
            placeholder = { Text(stringResource(R.string.conversation_search_hint)) },
            leadingIcon = { Icon(Icons.Rounded.Search, contentDescription = null) },
            trailingIcon = {
                if (query.isNotEmpty()) {
                    IconButton(onClick = { query = "" }) {
                        Icon(
                            Icons.Rounded.Close,
                            contentDescription = stringResource(R.string.clear_search),
                        )
                    }
                }
            },
        )

        LazyColumn(
            modifier = Modifier.fillMaxWidth().weight(1f),
            contentPadding = PaddingValues(bottom = 16.dp),
        ) {
            if (filtered.isEmpty()) {
                item {
                    EmptyConversationResult(
                        hasQuery = normalizedQuery.isNotBlank(),
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(horizontal = 24.dp, vertical = 56.dp),
                    )
                }
            } else {
                if (pinned.isNotEmpty()) {
                    item { ConversationSectionHeader(stringResource(R.string.conversation_pinned_section)) }
                    items(pinned, key = ConversationListItem::key) { item ->
                        ConversationRow(
                            item = item,
                            selected = item.key == selectedKey,
                            onClick = { onSelect(item) },
                            onTogglePinned = { onTogglePinned(item.key) },
                            onRename = { renameTarget = item },
                            onArchive = { onArchive(item.key) },
                            onDelete = { deleteTarget = item },
                        )
                    }
                }
                if (recent.isNotEmpty()) {
                    item {
                        ConversationSectionHeader(
                            text = stringResource(R.string.conversation_recent_section),
                            modifier = Modifier.padding(top = if (pinned.isNotEmpty()) 12.dp else 0.dp),
                        )
                    }
                    items(recent, key = ConversationListItem::key) { item ->
                        ConversationRow(
                            item = item,
                            selected = item.key == selectedKey,
                            onClick = { onSelect(item) },
                            onTogglePinned = { onTogglePinned(item.key) },
                            onRename = { renameTarget = item },
                            onArchive = { onArchive(item.key) },
                            onDelete = { deleteTarget = item },
                        )
                    }
                }
            }
            if (showArchivedEntry) {
                item { ArchivedConversationsEntry(archivedCount, onShowArchived) }
            }
        }
    }

    renameTarget?.let { item ->
        RenameConversationDialog(
            initial = item.title,
            onDismiss = { renameTarget = null },
            onConfirm = { title ->
                renameTarget = null
                onRename(item, title)
            },
        )
    }
    deleteTarget?.let { item ->
        DeleteConversationDialog(
            title = item.title,
            onDismiss = { deleteTarget = null },
            onConfirm = {
                deleteTarget = null
                onDelete(item)
            },
        )
    }
}

@Composable
private fun ArchivedConversationsEntry(
    count: Int,
    onClick: () -> Unit,
) {
    // 归档入口是会话导航的一部分，复用统一平面导航行；数量作为低强调尾部元数据。
    NanobotNavigationRow(
        headline = stringResource(R.string.conversation_archived_title),
        supportingText = count.toString(),
        modifier = Modifier.padding(horizontal = 8.dp),
        onClick = onClick,
        leadingContent = {
            Icon(
                Icons.Rounded.Archive,
                contentDescription = null,
                tint = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        },
    )
}

@Composable
private fun ConversationSectionHeader(
    text: String,
    modifier: Modifier = Modifier,
) {
    NanobotSectionHeader(text = text, modifier = modifier)
}

@Composable
private fun ConversationRow(
    item: ConversationListItem,
    selected: Boolean,
    onClick: () -> Unit,
    onTogglePinned: () -> Unit,
    onRename: () -> Unit,
    onArchive: () -> Unit,
    onDelete: () -> Unit,
) {
    var menuOpen by remember { mutableStateOf(false) }
    var deferredMenuAction by remember { mutableStateOf<(() -> Unit)?>(null) }

    LaunchedEffect(menuOpen, deferredMenuAction) {
        val action = deferredMenuAction ?: return@LaunchedEffect
        if (menuOpen) return@LaunchedEffect
        // 该 Effect 只会在 menuOpen=false 已经完成重组后启动；此时 Popup 已从当前 composition
        // 退出，再移动 LazyColumn 中的 item 不会留下继续拦截触摸事件的旧窗口层。
        deferredMenuAction = null
        action()
    }

    /**
     * 会话是典型的“标题 + 摘要 + leading icon + trailing action”列表语义，直接使用 Material 3
     * ListItem。选中态使用 secondaryContainer，让状态层、文字对比和动态配色由主题统一管理；
     * 尾部更多按钮仍单独消费点击，避免打开管理菜单时误触会话切换。
     */
    ListItem(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 8.dp, vertical = 2.dp)
            .clip(MaterialTheme.shapes.medium)
            .clickable(onClick = onClick),
        colors = ListItemDefaults.colors(
            containerColor = if (selected) {
                MaterialTheme.colorScheme.secondaryContainer
            } else {
                MaterialTheme.colorScheme.surface
            },
        ),
        headlineContent = {
            Text(
                text = item.title,
                style = MaterialTheme.typography.bodyLarge,
                fontWeight = if (selected) FontWeight.SemiBold else FontWeight.Normal,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
        },
        supportingContent =
            if (item.preview.isBlank()) {
                null
            } else {
                {
                    Text(
                        text = item.preview,
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                }
            },
        leadingContent = {
            Icon(
                imageVector =
                    if (item.pinned) {
                        Icons.Rounded.PushPin
                    } else {
                        Icons.Rounded.ChatBubbleOutline
                    },
                contentDescription = null,
                tint =
                    if (item.pinned) {
                        MaterialTheme.colorScheme.primary
                    } else {
                        MaterialTheme.colorScheme.onSurfaceVariant
                    },
            )
        },
        trailingContent = {
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(4.dp),
            ) {
                when {
                    item.pending || item.running -> CircularProgressIndicator(
                        modifier = Modifier.size(18.dp),
                        strokeWidth = 2.dp,
                    )
                    item.unread -> Box(
                        modifier =
                            Modifier.size(9.dp)
                                // 未读点属于需要用户继续处理的 Active 元数据，统一使用 primary，
                                // 不把 tertiary 装饰色解释为业务状态。
                                .background(MaterialTheme.colorScheme.primary, CircleShape),
                    )
                }
                ConversationActionsMenu(
                    item = item,
                    expanded = menuOpen,
                    enabled = !item.pending,
                    onExpand = { menuOpen = true },
                    onDismiss = { menuOpen = false },
                    onAction = { action ->
                        // 先记录动作并关闭 Popup；真正的 mutation 由上面的 LaunchedEffect 在关闭重组
                        // 提交后执行，避免置顶导致行跨分组移动时 Popup 与列表同时销毁/重建。
                        deferredMenuAction = action
                        menuOpen = false
                    },
                    onTogglePinned = onTogglePinned,
                    onRename = onRename,
                    onArchive = onArchive,
                    onDelete = onDelete,
                )
            }
        },
    )
    HorizontalDivider(
        modifier = Modifier.padding(start = 64.dp, end = 16.dp),
        color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.55f),
    )
}

@Composable
private fun ConversationActionsMenu(
    item: ConversationListItem,
    expanded: Boolean,
    enabled: Boolean,
    onExpand: () -> Unit,
    onDismiss: () -> Unit,
    onAction: (() -> Unit) -> Unit,
    onTogglePinned: () -> Unit,
    onRename: () -> Unit,
    onArchive: () -> Unit,
    onDelete: () -> Unit,
) {
    Box {
        IconButton(
            onClick = onExpand,
            enabled = enabled,
            modifier = Modifier.size(40.dp),
        ) {
            Icon(
                Icons.Rounded.MoreHoriz,
                contentDescription = stringResource(R.string.topic_actions),
                tint = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        // 菜单使用 Material 3 默认形状、容器色与 elevation，和应用主题保持同步。
        DropdownMenu(
            expanded = expanded,
            onDismissRequest = onDismiss,
        ) {
            DropdownMenuItem(
                text = {
                    Text(
                        stringResource(
                            if (item.pinned) R.string.unpin else R.string.pin,
                        ),
                    )
                },
                leadingIcon = { Icon(Icons.Rounded.PushPin, contentDescription = null) },
                enabled = enabled,
                onClick = { onAction(onTogglePinned) },
            )
            DropdownMenuItem(
                text = { Text(stringResource(R.string.rename)) },
                leadingIcon = { Icon(Icons.Rounded.Edit, contentDescription = null) },
                enabled = enabled,
                onClick = { onAction(onRename) },
            )
            DropdownMenuItem(
                text = {
                    Text(
                        stringResource(
                            if (item.archived) R.string.conversation_unarchive else R.string.archive,
                        ),
                    )
                },
                leadingIcon = { Icon(Icons.Rounded.Archive, contentDescription = null) },
                enabled = enabled,
                onClick = { onAction(onArchive) },
            )
            DropdownMenuItem(
                text = {
                    Text(
                        text = stringResource(R.string.delete),
                        color = MaterialTheme.colorScheme.error,
                    )
                },
                leadingIcon = {
                    Icon(
                        Icons.Rounded.Delete,
                        contentDescription = null,
                        tint = MaterialTheme.colorScheme.error,
                    )
                },
                enabled = enabled,
                onClick = { onAction(onDelete) },
            )
        }
    }
}

@Composable
private fun RenameConversationDialog(
    initial: String,
    onDismiss: () -> Unit,
    onConfirm: (String) -> Unit,
) {
    var value by remember(initial) { mutableStateOf(initial) }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(R.string.rename_topic)) },
        text = {
            OutlinedTextField(
                value = value,
                onValueChange = { value = it },
                singleLine = true,
            )
        },
        confirmButton = {
            TextButton(
                onClick = { onConfirm(value.trim()) },
                enabled = value.isNotBlank(),
            ) {
                Text(stringResource(R.string.save))
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) {
                Text(stringResource(R.string.cancel))
            }
        },
    )
}

@Composable
private fun DeleteConversationDialog(
    title: String,
    onDismiss: () -> Unit,
    onConfirm: () -> Unit,
) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(R.string.delete_topic)) },
        text = {
            Text(stringResource(R.string.delete_topic_confirmation, title))
        },
        confirmButton = {
            TextButton(onClick = onConfirm) {
                Text(
                    text = stringResource(R.string.delete),
                    color = MaterialTheme.colorScheme.error,
                )
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) {
                Text(stringResource(R.string.cancel))
            }
        },
    )
}

@Composable
private fun EmptyConversationResult(
    hasQuery: Boolean,
    modifier: Modifier = Modifier,
) {
    Column(
        modifier = modifier,
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        Icon(
            Icons.Rounded.ChatBubbleOutline,
            contentDescription = null,
            modifier = Modifier.size(32.dp),
            tint = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Text(
            text = stringResource(
                if (hasQuery) R.string.conversation_no_results else R.string.conversation_empty,
            ),
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}
