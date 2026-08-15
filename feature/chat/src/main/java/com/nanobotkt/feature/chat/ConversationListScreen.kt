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
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
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
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp

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
        shape = RoundedCornerShape(topStart = 28.dp, topEnd = 28.dp),
        containerColor = MaterialTheme.colorScheme.surface,
        scrimColor = MaterialTheme.colorScheme.scrim.copy(alpha = 0.48f),
        tonalElevation = 2.dp,
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .heightIn(max = 680.dp)
                .navigationBarsPadding(),
        ) {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 20.dp),
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
                            contentDescription = stringResource(R.string.open_settings),
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
                    .weight(1f, fill = false),
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
                .padding(horizontal = 16.dp, vertical = 4.dp),
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
            modifier = Modifier.fillMaxWidth().weight(1f, fill = false),
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
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clickable(onClick = onClick)
            .padding(horizontal = 20.dp, vertical = 14.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Icon(Icons.Rounded.Archive, contentDescription = null, tint = MaterialTheme.colorScheme.onSurfaceVariant)
        Column(Modifier.padding(start = 12.dp)) {
            Text(stringResource(R.string.conversation_archived_title), style = MaterialTheme.typography.bodyLarge, fontWeight = FontWeight.Medium)
            Text(count.toString(), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
    }
}

@Composable
private fun ConversationSectionHeader(
    text: String,
    modifier: Modifier = Modifier,
) {
    Text(
        text = text,
        modifier = modifier.padding(horizontal = 20.dp, vertical = 10.dp),
        style = MaterialTheme.typography.labelLarge,
        color = MaterialTheme.colorScheme.primary,
    )
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

    // 选中态不用大面积圆角卡片，改成窄色条 + 字重变化；这样列表仍然保持连续，
    // 视觉焦点也不会抢走聊天内容的优先级。右侧更多按钮单独消费点击事件，不会误触
    // 会话切换，适合在小屏幕上完成低频的管理操作。
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clickable(onClick = onClick)
            .padding(horizontal = 16.dp, vertical = 10.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Spacer(
            modifier = Modifier
                .width(3.dp)
                .height(42.dp)
                .background(
                    if (selected) MaterialTheme.colorScheme.primary else Color.Transparent,
                ),
        )
        Spacer(Modifier.width(10.dp))
        Icon(
            imageVector = if (item.pinned) Icons.Rounded.PushPin else Icons.Rounded.ChatBubbleOutline,
            contentDescription = null,
            modifier = Modifier.size(18.dp),
            tint = if (item.pinned) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Spacer(Modifier.width(10.dp))
        Column(modifier = Modifier.weight(1f)) {
            Text(
                text = item.title,
                style = MaterialTheme.typography.bodyLarge,
                fontWeight = if (selected) FontWeight.SemiBold else FontWeight.Normal,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            if (item.preview.isNotBlank()) {
                Text(
                    text = item.preview,
                    modifier = Modifier.padding(top = 5.dp),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis,
                )
            }
        }
        ConversationActionsMenu(
            item = item,
            expanded = menuOpen,
            onExpand = { menuOpen = true },
            onDismiss = { menuOpen = false },
            onTogglePinned = onTogglePinned,
            onRename = onRename,
            onArchive = onArchive,
            onDelete = onDelete,
        )
    }
    HorizontalDivider(
        modifier = Modifier.padding(horizontal = 24.dp),
        color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.55f),
    )
}

@Composable
private fun ConversationActionsMenu(
    item: ConversationListItem,
    expanded: Boolean,
    onExpand: () -> Unit,
    onDismiss: () -> Unit,
    onTogglePinned: () -> Unit,
    onRename: () -> Unit,
    onArchive: () -> Unit,
    onDelete: () -> Unit,
) {
    Box {
        IconButton(
            onClick = onExpand,
            modifier = Modifier.size(40.dp),
        ) {
            Icon(
                Icons.Rounded.MoreHoriz,
                contentDescription = stringResource(R.string.topic_actions),
                tint = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        DropdownMenu(
            expanded = expanded,
            onDismissRequest = onDismiss,
            // 管理菜单是功能性浮层，不使用默认的大圆角高亮容器，避免列表页重新变成
            // 一块突兀的“卡片”；菜单只保留轻微圆角和普通 surface 色。
            shape = RoundedCornerShape(8.dp),
            containerColor = MaterialTheme.colorScheme.surface,
            tonalElevation = 1.dp,
            shadowElevation = 4.dp,
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
                onClick = {
                    onDismiss()
                    onTogglePinned()
                },
            )
            DropdownMenuItem(
                text = { Text(stringResource(R.string.rename)) },
                leadingIcon = { Icon(Icons.Rounded.Edit, contentDescription = null) },
                onClick = {
                    onDismiss()
                    onRename()
                },
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
                onClick = {
                    onDismiss()
                    onArchive()
                },
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
                onClick = {
                    onDismiss()
                    onDelete()
                },
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
