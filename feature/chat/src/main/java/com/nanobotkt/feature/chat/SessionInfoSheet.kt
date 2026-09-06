package com.nanobotkt.feature.chat

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.KeyboardArrowRight
import androidx.compose.material.icons.rounded.Check
import androidx.compose.material.icons.rounded.Checklist
import androidx.compose.material.icons.rounded.Close
import androidx.compose.material.icons.rounded.Edit
import androidx.compose.material.icons.rounded.Folder
import androidx.compose.material.icons.rounded.Link
import androidx.compose.material.icons.rounded.SmartToy
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.ListItem
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.nanobotkt.core.designsystem.NanobotThemeDefaults
import com.nanobotkt.core.model.SessionAutomationJob
import com.nanobotkt.core.transport.TransportStatus

/**
 * 当前会话的统一二级信息面板。
 *
 * 顶部栏不再常驻展示 Workspace，也不再提供三个点菜单；会话名称、Workspace、模型、连接状态及
 * 低频会话操作都集中在这里。面板只负责输入校验和入口编排，真正的重命名、模型切换与权限修改
 * 仍通过外层既有 ViewModel/Repository 链路执行，避免在 UI 中建立第二份业务状态。
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SessionInfoSheet(
    title: String,
    sessionKey: String?,
    workspaceName: String?,
    modelName: String,
    transportStatus: TransportStatus,
    hasPromptNavigator: Boolean,
    hasWorkspaceAccess: Boolean,
    renamePending: Boolean,
    renameFailed: Boolean,
    loadJobs: suspend (String) -> List<SessionAutomationJob>,
    visible: Boolean,
    onRename: (String) -> Unit,
    onClearRenameError: () -> Unit,
    onOpenPromptNavigator: () -> Unit,
    onOpenModel: () -> Unit,
    onOpenWorkspaceAccess: () -> Unit,
    onClose: () -> Unit,
) {
    if (!visible) return

    val sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)
    val canRename = sessionKey != null
    var editingTitle by rememberSaveable(sessionKey) { mutableStateOf(false) }
    var titleDraft by remember(sessionKey) { mutableStateOf(title) }
    var submittedTitle by remember(sessionKey) { mutableStateOf<String?>(null) }
    var showRenameError by remember(sessionKey) { mutableStateOf(false) }

    LaunchedEffect(title, editingTitle, submittedTitle) {
        // 外层标题可能因 Sidebar 刷新或其他入口重命名而变化；仅在没有本地编辑/提交时同步，
        // 防止异步刷新覆盖用户尚未保存的输入。
        if (!editingTitle && submittedTitle == null) titleDraft = title
    }
    LaunchedEffect(title, renamePending, renameFailed, submittedTitle) {
        val submitted = submittedTitle ?: return@LaunchedEffect
        if (renamePending) return@LaunchedEffect
        when {
            title.trim() == submitted -> {
                // Sidebar 先乐观更新、再等待服务端确认；只有 pending 结束且标题仍为目标值时，
                // 才把本地编辑态视为成功，确保顶部标题和 Sidebar 使用同一份已确认状态。
                submittedTitle = null
                editingTitle = false
                titleDraft = title
                showRenameError = false
            }
            renameFailed -> {
                // Repository 失败后会回滚乐观标题。保留编辑框和用户输入，只解除提交锁并显示
                // 可理解的本地错误，用户无需重新输入即可再次保存。
                submittedTitle = null
                showRenameError = true
            }
        }
    }

    ModalBottomSheet(
        onDismissRequest = onClose,
        sheetState = sheetState,
        shape = MaterialTheme.shapes.extraLarge,
        containerColor = MaterialTheme.colorScheme.surface,
        tonalElevation = 1.dp,
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = NanobotThemeDefaults.spacing.md),
        ) {
            SessionTitleEditor(
                title = title,
                value = titleDraft,
                editing = editingTitle,
                enabled = canRename && submittedTitle == null && !renamePending,
                submitting = submittedTitle != null || renamePending,
                showError = showRenameError,
                onValueChange = { value ->
                    titleDraft = value
                    showRenameError = false
                    onClearRenameError()
                },
                onStartEditing = {
                    titleDraft = title
                    editingTitle = true
                    showRenameError = false
                    onClearRenameError()
                },
                onCancel = {
                    titleDraft = title
                    editingTitle = false
                    submittedTitle = null
                    showRenameError = false
                    onClearRenameError()
                },
                onSave = {
                    val normalized = titleDraft.trim()
                    when {
                        normalized.isEmpty() || normalized == title.trim() -> editingTitle = false
                        else -> {
                            showRenameError = false
                            onClearRenameError()
                            submittedTitle = normalized
                            onRename(normalized)
                        }
                    }
                },
                onClose = onClose,
            )

            Spacer(Modifier.height(NanobotThemeDefaults.spacing.sm))
            HorizontalDivider()

            SessionDetailRow(
                icon = Icons.Rounded.Folder,
                label = stringResource(R.string.session_info_workspace),
                value = workspaceName ?: stringResource(R.string.session_info_not_available),
                enabled = hasWorkspaceAccess,
                onClick = onOpenWorkspaceAccess,
            )
            SessionDetailRow(
                icon = Icons.Rounded.SmartToy,
                label = stringResource(R.string.session_info_model),
                value = modelName,
                enabled = true,
                onClick = onOpenModel,
            )
            SessionDetailRow(
                icon = Icons.Rounded.Link,
                label = stringResource(R.string.session_info_connection),
                value = connectionStatusLabel(transportStatus),
            )
            if (hasPromptNavigator) {
                SessionDetailRow(
                    icon = Icons.Rounded.Checklist,
                    label = stringResource(R.string.prompt_navigator_title),
                    value = stringResource(R.string.prompt_navigator_open),
                    enabled = true,
                    onClick = onOpenPromptNavigator,
                )
            }

            if (sessionKey != null) {
                Spacer(Modifier.height(NanobotThemeDefaults.spacing.sm))
                HorizontalDivider()
                Spacer(Modifier.height(NanobotThemeDefaults.spacing.md))
                SessionAutomationList(
                    sessionKey = sessionKey,
                    loadJobs = loadJobs,
                    visible = visible,
                )
            }

            Spacer(Modifier.height(NanobotThemeDefaults.spacing.lg))
        }
    }
}

/** 标题编辑器在网络提交期间锁定输入，并用进度指示替代保存按钮，避免重复 mutation。 */
@Composable
private fun SessionTitleEditor(
    title: String,
    value: String,
    editing: Boolean,
    enabled: Boolean,
    submitting: Boolean,
    showError: Boolean,
    onValueChange: (String) -> Unit,
    onStartEditing: () -> Unit,
    onCancel: () -> Unit,
    onSave: () -> Unit,
    onClose: () -> Unit,
) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(modifier = Modifier.weight(1f)) {
            Text(
                text = stringResource(R.string.session_info_label),
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            if (editing) {
                OutlinedTextField(
                    value = value,
                    onValueChange = onValueChange,
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(top = NanobotThemeDefaults.spacing.xs),
                    enabled = !submitting,
                    singleLine = true,
                    isError = showError,
                    supportingText =
                        if (showError) {
                            { Text(stringResource(R.string.session_info_rename_failed)) }
                        } else {
                            null
                        },
                    trailingIcon = {
                        Row(horizontalArrangement = Arrangement.spacedBy(2.dp)) {
                            if (submitting) {
                                CircularProgressIndicator(
                                    modifier = Modifier
                                        .padding(12.dp)
                                        .size(20.dp),
                                    strokeWidth = 2.dp,
                                )
                            } else {
                                IconButton(onClick = onCancel) {
                                    Icon(Icons.Rounded.Close, stringResource(R.string.cancel))
                                }
                                IconButton(
                                    enabled = enabled && value.isNotBlank() && value.trim() != title.trim(),
                                    onClick = onSave,
                                ) {
                                    Icon(Icons.Rounded.Check, stringResource(R.string.save))
                                }
                            }
                        }
                    },
                )
            } else {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(
                        text = title.ifBlank { stringResource(R.string.session_info_untitled) },
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                        style = MaterialTheme.typography.titleMedium,
                        modifier = Modifier.weight(1f).padding(top = NanobotThemeDefaults.spacing.xxs),
                    )
                    if (enabled) {
                        IconButton(onClick = onStartEditing) {
                            Icon(Icons.Rounded.Edit, stringResource(R.string.rename_topic))
                        }
                    }
                }
            }
        }
        IconButton(onClick = onClose) {
            Icon(Icons.Rounded.Close, stringResource(R.string.close))
        }
    }
}

/**
 * 会话详情行复用标准 ListItem。可操作行显示箭头并消费点击；纯展示行没有伪按钮语义，
 * 让 TalkBack 用户能够区分“查看连接状态”和“进入模型/Workspace 配置”。
 */
@Composable
private fun SessionDetailRow(
    icon: ImageVector,
    label: String,
    value: String,
    enabled: Boolean = false,
    onClick: () -> Unit = {},
) {
    ListItem(
        modifier = Modifier
            .fillMaxWidth()
            .then(if (enabled) Modifier.clickable(onClick = onClick) else Modifier),
        headlineContent = { Text(label) },
        supportingContent = {
            Text(
                text = value,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
        },
        leadingContent = {
            Icon(
                imageVector = icon,
                contentDescription = null,
                tint = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        },
        trailingContent =
            if (enabled) {
                {
                    Icon(
                        Icons.AutoMirrored.Rounded.KeyboardArrowRight,
                        contentDescription = null,
                    )
                }
            } else {
                null
            },
    )
}
