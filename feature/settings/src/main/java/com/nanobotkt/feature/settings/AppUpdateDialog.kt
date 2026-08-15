package com.nanobotkt.feature.settings

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import java.util.Locale

/**
 * App 更新对话框只渲染不可变状态并转发用户事件。
 *
 * Release body 作为普通文本展示，不解析 HTML，也不执行远端内容；这样即使更新日志包含
 * Markdown 或 HTML 标签，也只会按文本呈现，不会扩大远端 Release 对客户端的执行能力。
 */
@Composable
internal fun AppUpdateDialog(
    state: AppUpdateUiState,
    onDismiss: () -> Unit,
    onCheck: () -> Unit,
    onDownload: () -> Unit,
    onInstall: () -> Unit,
    onRetry: () -> Unit,
) {
    val status = state.status
    val busy = status == AppUpdateStatus.Checking ||
        status is AppUpdateStatus.Downloading ||
        status is AppUpdateStatus.Installing

    AlertDialog(
        onDismissRequest = {
            // 检查、下载和安装器启动过程中忽略触摸外部或返回键，避免用户误触后重复任务。
            if (!busy) onDismiss()
        },
        title = {
            Text(
                text = appUpdateDialogTitle(status),
                fontWeight = FontWeight.SemiBold,
            )
        },
        text = {
            LazyColumn(
                modifier = Modifier
                    .fillMaxWidth()
                    .heightIn(max = 480.dp),
                verticalArrangement = Arrangement.spacedBy(12.dp),
            ) {
                item {
                    Text(
                        text = "当前版本：${state.current.versionName}（${state.current.channel.displayName}，versionCode ${state.current.versionCode}）",
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
                when (status) {
                    AppUpdateStatus.Idle -> item {
                        Text("点击检查以获取当前发布渠道的最新版本。")
                    }
                    AppUpdateStatus.Checking -> item {
                        BusyStatus(text = "正在检查更新…")
                    }
                    AppUpdateStatus.UpToDate -> item {
                        Text("当前已是最新版本")
                    }
                    is AppUpdateStatus.UpdateAvailable -> {
                        updateSummaryItems(status.update)
                    }
                    is AppUpdateStatus.Downloading -> {
                        item {
                            Text(
                                text = "正在下载 ${status.update.versionName}（${status.update.channel.displayName}）",
                                style = MaterialTheme.typography.bodyMedium,
                            )
                        }
                        item {
                            DownloadProgress(status.progress)
                        }
                    }
                    is AppUpdateStatus.Downloaded -> {
                        item {
                            Text("安装包已下载完成。点击“安装”后将由系统安装器请求你的确认。")
                        }
                        updateSummaryItems(status.update)
                    }
                    is AppUpdateStatus.Installing -> item {
                        BusyStatus(text = "正在打开系统安装器…")
                    }
                    is AppUpdateStatus.Error -> {
                        item {
                            Text(
                                text = status.message,
                                color = MaterialTheme.colorScheme.error,
                            )
                        }
                        status.update?.let { updateSummaryItems(it) }
                    }
                }
            }
        },
        confirmButton = {
            when (status) {
                AppUpdateStatus.Idle -> TextButton(onClick = onCheck) { Text("检查") }
                is AppUpdateStatus.UpdateAvailable -> TextButton(onClick = onDownload) { Text("下载") }
                is AppUpdateStatus.Downloaded -> TextButton(onClick = onInstall) { Text("安装") }
                is AppUpdateStatus.Error -> TextButton(onClick = onRetry) { Text("重试") }
                AppUpdateStatus.UpToDate -> TextButton(onClick = onDismiss) { Text("完成") }
                AppUpdateStatus.Checking,
                is AppUpdateStatus.Downloading,
                is AppUpdateStatus.Installing,
                -> Unit
            }
        },
        dismissButton = {
            when (status) {
                AppUpdateStatus.Idle,
                is AppUpdateStatus.UpdateAvailable,
                is AppUpdateStatus.Downloaded,
                is AppUpdateStatus.Error,
                -> TextButton(onClick = onDismiss) { Text("关闭") }
                // “已是最新”已经提供“完成”按钮，不再重复渲染语义相同的关闭操作。
                AppUpdateStatus.UpToDate,
                AppUpdateStatus.Checking,
                is AppUpdateStatus.Downloading,
                is AppUpdateStatus.Installing,
                -> Unit
            }
        },
    )
}

/** 在 LazyColumn 作用域内复用版本摘要与纯文本更新日志，避免各终态展示规则漂移。 */
private fun androidx.compose.foundation.lazy.LazyListScope.updateSummaryItems(update: AppUpdateInfo) {
    item {
        Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
            Text(
                text = "新版本：${update.versionName}",
                style = MaterialTheme.typography.titleMedium,
                fontWeight = FontWeight.SemiBold,
            )
            Text(
                text = "版本类型：${update.channel.displayName}",
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
    item { HorizontalDivider() }
    item {
        Text(
            text = "更新日志",
            style = MaterialTheme.typography.titleSmall,
            fontWeight = FontWeight.SemiBold,
        )
    }
    item {
        Text(
            text = update.changelog.trim().ifBlank { "暂无更新日志" },
            style = MaterialTheme.typography.bodyMedium,
        )
    }
}

@Composable
private fun BusyStatus(text: String) {
    Column(
        modifier = Modifier.fillMaxWidth(),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        CircularProgressIndicator()
        Text(text)
    }
}

@Composable
private fun DownloadProgress(progress: AppUpdateProgress) {
    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        val fraction = progress.fraction
        if (fraction == null) {
            LinearProgressIndicator(modifier = Modifier.fillMaxWidth())
        } else {
            LinearProgressIndicator(
                progress = { fraction },
                modifier = Modifier.fillMaxWidth(),
            )
        }
        Text(
            text = if (progress.totalBytes == null) {
                "已下载 ${formatBytes(progress.downloadedBytes)}"
            } else {
                "${formatBytes(progress.downloadedBytes)} / ${formatBytes(progress.totalBytes)}"
            },
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}

private fun appUpdateDialogTitle(status: AppUpdateStatus): String = when (status) {
    AppUpdateStatus.Idle,
    AppUpdateStatus.Checking,
    -> "检查更新"
    AppUpdateStatus.UpToDate -> "已是最新版本"
    is AppUpdateStatus.UpdateAvailable,
    is AppUpdateStatus.Downloading,
    is AppUpdateStatus.Downloaded,
    is AppUpdateStatus.Installing,
    -> "发现新版本"
    is AppUpdateStatus.Error -> "更新失败"
}

/** 仅用于人类可读的下载进度；版本和完整性判断仍使用原始字节数。 */
internal fun formatBytes(value: Long): String {
    val safeValue = value.coerceAtLeast(0L)
    if (safeValue < 1_024L) return "$safeValue B"
    val units = listOf("KB", "MB", "GB")
    var amount = safeValue.toDouble()
    var unitIndex = -1
    while (amount >= 1_024.0 && unitIndex < units.lastIndex) {
        amount /= 1_024.0
        unitIndex += 1
    }
    return String.format(Locale.US, "%.1f %s", amount, units[unitIndex])
}
