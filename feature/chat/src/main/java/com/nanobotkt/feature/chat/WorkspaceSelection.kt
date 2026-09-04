package com.nanobotkt.feature.chat

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Check
import androidx.compose.material.icons.rounded.ExpandMore
import androidx.compose.material.icons.rounded.Folder
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.nanobotkt.core.model.WorkspaceScope
import com.nanobotkt.core.model.normalizeWorkspacePath
import com.nanobotkt.core.model.normalized
import com.nanobotkt.core.model.projectNameFromPath

/**
 * 新主题发送前可供选择的 Workspace。
 *
 * [identity] 始终来自规范化后的完整绝对路径；[displayName] 只是展示文案，不能参与去重、
 * 选中状态或新会话创建，避免同名目录把两个不同 Workspace 错误合并。
 */
data class WorkspaceOption(
    val scope: WorkspaceScope,
    val displayName: String,
    val identity: String,
)

/**
 * 从用户已有会话携带的 Workspace 范围生成稳定的候选项。
 *
 * 先按完整路径去重，再按最后一级目录名判断展示歧义：目录名唯一时保持紧凑，只展示最后一级；
 * 同名组则全部展示完整路径。列表最后按展示名和路径排序，避免 Sidebar 排序方式变化时菜单跳动。
 */
fun buildWorkspaceOptions(scopes: Iterable<WorkspaceScope?>): List<WorkspaceOption> {
    val uniqueScopes = scopes
        .filterNotNull()
        .filter { scope -> scope.projectPath.isNotBlank() }
        .map { scope ->
            // 选择结果会直接进入新会话请求；这里提前把分隔符和末尾斜杠规范化，确保
            // UI 展示用的 identity 与底层实际保存、传输的 projectPath 完全一致。
            scope.normalized().copy(
                projectPath = normalizeWorkspacePath(scope.projectPath.trim()),
            )
        }
        .distinctBy(::workspaceIdentity)
    val leafNameCounts = uniqueScopes
        .groupingBy(::workspaceLeafName)
        .eachCount()

    return uniqueScopes
        .map { scope ->
            val identity = workspaceIdentity(scope)
            val leafName = workspaceLeafName(scope)
            WorkspaceOption(
                scope = scope,
                displayName = if (leafNameCounts.getValue(leafName) > 1) identity else leafName,
                identity = identity,
            )
        }
        .sortedWith(
            compareBy(String.CASE_INSENSITIVE_ORDER, WorkspaceOption::displayName)
                .thenBy(WorkspaceOption::identity)
        )
}

/**
 * 解析输入框上方当前 Workspace 的展示名称。
 *
 * 当前默认 Workspace 可能尚未出现在任何历史会话中，因此它不应被偷偷加入可选列表；但展示时
 * 仍要和已有候选一起参与重名判断，避免两个同名目录都被压缩成无法区分的最后一级名称。
 */
fun currentWorkspaceDisplayName(
    currentScope: WorkspaceScope,
    options: List<WorkspaceOption>,
): String {
    val currentIdentity = workspaceIdentity(currentScope)
    return workspaceDisplayNames(currentScope, options)[currentIdentity] ?: currentIdentity
}

/**
 * 生成选择器本次渲染使用的展示项。
 *
 * 可点击候选仍严格来自已有会话的 [options]；这里只把当前默认 Workspace 临时加入“重名计算”，
 * 让当前项和候选项在目录名冲突时都显示完整路径。这样不会把尚无历史会话的默认目录偷偷变成
 * 新候选，同时也不会出现当前项显示完整路径、菜单中的同名候选却仍只显示目录名的不一致。
 */
internal fun workspaceDisplayNames(
    currentScope: WorkspaceScope,
    options: List<WorkspaceOption>,
): Map<String, String> =
    buildWorkspaceOptions(options.map(WorkspaceOption::scope) + currentScope)
        .associate { option -> option.identity to option.displayName }

/** 身份比较只做客户端可安全完成的字符串规范化，不猜测服务端软链接或路径大小写语义。 */
private fun workspaceIdentity(scope: WorkspaceScope): String =
    normalizeWorkspacePath(scope.projectPath.trim())

/** 重名判断严格基于路径最后一级，不使用可能被服务端自定义的 projectName。 */
private fun workspaceLeafName(scope: WorkspaceScope): String =
    projectNameFromPath(scope.projectPath).trim().ifEmpty { workspaceIdentity(scope) }

/**
 * 新主题输入框上方的 Workspace 选择器。
 *
 * 默认只展示当前 Workspace，不主动弹窗打断新建流程；只有候选中确实存在其他绝对路径时才开放
 * 点击。菜单锚定在当前 Workspace 行上，避免像独立对话框一样与用户正在编辑的输入区脱节。
 */
@Composable
internal fun NewTopicWorkspaceSelector(
    currentScope: WorkspaceScope,
    options: List<WorkspaceOption>,
    enabled: Boolean,
    onSelect: (WorkspaceScope) -> Unit,
    modifier: Modifier = Modifier,
) {
    val currentIdentity = workspaceIdentity(currentScope)
    val hasAlternative = options.any { option -> option.identity != currentIdentity }
    val selectable = enabled && hasAlternative
    val displayNames = remember(currentScope, options) {
        workspaceDisplayNames(currentScope, options)
    }
    val displayName = displayNames[currentIdentity] ?: currentIdentity
    var expanded by remember(currentIdentity, options) { mutableStateOf(false) }

    Box(modifier = modifier.fillMaxWidth()) {
        Surface(
            onClick = { expanded = true },
            enabled = selectable,
            modifier = Modifier.fillMaxWidth().testTag(NEW_TOPIC_WORKSPACE_SELECTOR_TEST_TAG),
            shape = MaterialTheme.shapes.large,
            color = MaterialTheme.colorScheme.surfaceContainerLow,
        ) {
            Row(
                modifier = Modifier.padding(horizontal = 12.dp, vertical = 8.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Icon(
                    imageVector = Icons.Rounded.Folder,
                    contentDescription = null,
                    modifier = Modifier.size(18.dp),
                    tint = MaterialTheme.colorScheme.primary,
                )
                Text(
                    text = stringResource(R.string.current_workspace, displayName),
                    modifier = Modifier.weight(1f).padding(horizontal = 8.dp),
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis,
                    style = MaterialTheme.typography.labelLarge,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                if (selectable) {
                    Icon(
                        imageVector = Icons.Rounded.ExpandMore,
                        contentDescription = stringResource(R.string.change_workspace),
                        modifier = Modifier.size(20.dp),
                        tint = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
        }

        DropdownMenu(
            expanded = expanded && selectable,
            onDismissRequest = { expanded = false },
            modifier = Modifier.fillMaxWidth().heightIn(max = 320.dp),
        ) {
            options.forEach { option ->
                val selected = option.identity == currentIdentity
                DropdownMenuItem(
                    text = {
                        Text(
                            text = displayNames[option.identity] ?: option.displayName,
                            maxLines = 2,
                            overflow = TextOverflow.Ellipsis,
                        )
                    },
                    leadingIcon = {
                        Icon(
                            imageVector = Icons.Rounded.Folder,
                            contentDescription = null,
                            tint = MaterialTheme.colorScheme.primary,
                        )
                    },
                    trailingIcon = {
                        if (selected) {
                            Icon(
                                imageVector = Icons.Rounded.Check,
                                contentDescription = stringResource(R.string.workspace_selected),
                            )
                        }
                    },
                    onClick = {
                        expanded = false
                        if (!selected) onSelect(option.scope)
                    },
                )
            }
        }
    }
}

/** 供 Compose 仪器测试定位新主题 Workspace 行，不把菜单 Popup 误当作输入框内容。 */
internal const val NEW_TOPIC_WORKSPACE_SELECTOR_TEST_TAG = "new_topic_workspace_selector"
