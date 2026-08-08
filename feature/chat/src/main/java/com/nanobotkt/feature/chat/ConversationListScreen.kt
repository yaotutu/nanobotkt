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
import androidx.compose.material3.AlertDialog
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
)

/**
 * 聊天页的二级会话页面。
 *
 * 这里刻意不使用底部导航或 Bottom Sheet：聊天页仍然是唯一主页面，会话管理只是从顶部
 * 进入的独立任务流。列表页只负责呈现交互和转发事件，实际的会话修改仍由 app 组合根
 * 连接到 SidebarViewModel，避免在 UI 层复制一份后端状态。
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
    var query by rememberSaveable { mutableStateOf("") }
    // 对话框状态只保留当前操作的会话，不把临时输入写回业务状态；确认后才提交到
    // SidebarViewModel，这样取消或返回时不会产生半成品标题。
    var renameTarget by remember { mutableStateOf<ConversationListItem?>(null) }
    var deleteTarget by remember { mutableStateOf<ConversationListItem?>(null) }

    val normalizedQuery = query.trim()
    // 搜索只作用于当前已经由组合根筛选好的会话；这样归档、权限和会话选择规则仍由
    // 原 Sidebar 状态链路负责，不在这个页面复制一套业务状态。
    val filtered = items.filter { item ->
        normalizedQuery.isBlank() ||
            item.title.contains(normalizedQuery, ignoreCase = true) ||
            item.preview.contains(normalizedQuery, ignoreCase = true) ||
            item.key.contains(normalizedQuery, ignoreCase = true)
    }
    val pinned = filtered.filter(ConversationListItem::pinned)
    val recent = filtered.filterNot(ConversationListItem::pinned)

    Column(
        modifier = modifier
            .fillMaxSize()
            .background(MaterialTheme.colorScheme.background)
            .statusBarsPadding()
            .navigationBarsPadding(),
    ) {
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

        OutlinedTextField(
            value = query,
            onValueChange = { query = it },
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 16.dp, vertical = 4.dp),
            singleLine = true,
            placeholder = { Text(stringResource(R.string.conversation_search_hint)) },
            leadingIcon = {
                Icon(
                    Icons.Rounded.Search,
                    contentDescription = null,
                )
            },
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
            modifier = Modifier.fillMaxSize(),
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
                    item {
                        ConversationSectionHeader(stringResource(R.string.conversation_pinned_section))
                    }
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
                text = { Text(stringResource(R.string.archive)) },
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
