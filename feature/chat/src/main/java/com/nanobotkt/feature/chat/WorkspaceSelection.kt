package com.nanobotkt.feature.chat

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Folder
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.nanobotkt.core.model.WorkspaceScope
import com.nanobotkt.core.model.normalizeWorkspacePath
import com.nanobotkt.core.model.normalized
import com.nanobotkt.core.model.projectNameFromPath

/**
 * 新建会话时可供选择的 Workspace。
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
 * 同名组则全部展示完整路径。列表最后按展示名和路径排序，避免 Sidebar 排序方式变化时弹窗跳动。
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

/** 身份比较只做客户端可安全完成的字符串规范化，不猜测服务端软链接或路径大小写语义。 */
private fun workspaceIdentity(scope: WorkspaceScope): String =
    normalizeWorkspacePath(scope.projectPath.trim())

/** 重名判断严格基于路径最后一级，不使用可能被服务端自定义的 projectName。 */
private fun workspaceLeafName(scope: WorkspaceScope): String =
    projectNameFromPath(scope.projectPath).trim().ifEmpty { workspaceIdentity(scope) }

/**
 * 多 Workspace 场景下的新会话选择对话框。
 *
 * 这里仅返回完整 [WorkspaceScope]，真正的新主题状态切换和后续 WebSocket `new_chat` 仍由
 * app 组合根及 ChatViewModel 负责，避免 Composable 直接持有网络或会话生命周期。
 */
@Composable
fun WorkspaceSelectionDialog(
    options: List<WorkspaceOption>,
    onSelect: (WorkspaceScope) -> Unit,
    onDismiss: () -> Unit,
) {
    // 不预选第一项，要求用户明确点选 Workspace，避免连续点击确认时误用排序后的首项。
    var selectedIdentity by remember(options) { mutableStateOf<String?>(null) }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(R.string.workspace_select_title)) },
        text = {
            LazyColumn(modifier = Modifier.heightIn(max = 360.dp)) {
                items(options, key = WorkspaceOption::identity) { option ->
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .clickable { selectedIdentity = option.identity }
                            .padding(vertical = 4.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        RadioButton(
                            selected = selectedIdentity == option.identity,
                            onClick = { selectedIdentity = option.identity },
                        )
                        Icon(
                            imageVector = Icons.Rounded.Folder,
                            contentDescription = null,
                            modifier = Modifier.padding(end = 8.dp).size(20.dp),
                            tint = MaterialTheme.colorScheme.primary,
                        )
                        Text(
                            text = option.displayName,
                            modifier = Modifier.weight(1f),
                            maxLines = 2,
                            overflow = TextOverflow.Ellipsis,
                        )
                    }
                }
            }
        },
        confirmButton = {
            TextButton(
                onClick = {
                    options.firstOrNull { option -> option.identity == selectedIdentity }
                        ?.scope
                        ?.let(onSelect)
                },
                enabled = selectedIdentity != null,
            ) {
                Text(stringResource(R.string.confirm))
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) {
                Text(stringResource(R.string.cancel))
            }
        },
    )
}
