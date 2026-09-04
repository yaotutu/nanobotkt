package com.nanobotkt.feature.settings

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.res.stringResource
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
    onForceLatestDev: () -> Unit,
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
                        text = stringResource(
                            R.string.settings_current_version_detail,
                            state.current.versionName,
                            state.current.channel.displayName,
                            state.current.versionCode,
                        ),
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
                when (status) {
                    AppUpdateStatus.Idle -> item {
                        Text(stringResource(R.string.settings_update_check_instruction))
                    }
                    AppUpdateStatus.Checking -> item {
                        BusyStatus(text = stringResource(R.string.settings_checking_update_ellipsis))
                    }
                    AppUpdateStatus.UpToDate -> item {
                        Text(stringResource(R.string.settings_currently_up_to_date))
                    }
                    is AppUpdateStatus.UpdateAvailable -> {
                        updateSummaryItems(status.update)
                    }
                    is AppUpdateStatus.Downloading -> {
                        item {
                            Text(
                                text = stringResource(
                                    R.string.settings_downloading_version,
                                    status.update.versionName,
                                    status.update.channel.displayName,
                                ),
                                style = MaterialTheme.typography.bodyMedium,
                            )
                        }
                        item {
                            DownloadProgress(status.progress)
                        }
                    }
                    is AppUpdateStatus.Downloaded -> {
                        item {
                            Text(stringResource(R.string.settings_download_complete_message))
                        }
                        updateSummaryItems(status.update)
                    }
                    is AppUpdateStatus.Installing -> item {
                        BusyStatus(text = stringResource(R.string.settings_opening_installer_ellipsis))
                    }
                    is AppUpdateStatus.Error -> {
                        item {
                            Text(
                                text = status.message,
                                color = MaterialTheme.colorScheme.error,
                            )
                        }
                        if (status.canForceLatestDev) {
                            item {
                                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                                    Text(
                                        text = stringResource(R.string.settings_update_force_dev_explanation),
                                        style = MaterialTheme.typography.bodyMedium,
                                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                                    )
                                    // 强制更新是用户在 429 后的显式选择；使用完整宽度按钮，避免与“重试/关闭”
                                    // 挤在 AlertDialog 底部并降低触摸目标可辨识度。
                                    Button(
                                        onClick = onForceLatestDev,
                                        modifier = Modifier.fillMaxWidth(),
                                    ) {
                                        Text(stringResource(R.string.settings_update_force_dev_button))
                                    }
                                }
                            }
                        }
                        status.update?.let { updateSummaryItems(it) }
                    }
                }
            }
        },
        confirmButton = {
            when (status) {
                AppUpdateStatus.Idle -> TextButton(onClick = onCheck) { Text(stringResource(R.string.settings_check)) }
                is AppUpdateStatus.UpdateAvailable -> TextButton(onClick = onDownload) { Text(stringResource(R.string.settings_download)) }
                is AppUpdateStatus.Downloaded -> TextButton(onClick = onInstall) { Text(stringResource(R.string.settings_install)) }
                is AppUpdateStatus.Error -> TextButton(onClick = onRetry) { Text(stringResource(R.string.settings_retry)) }
                AppUpdateStatus.UpToDate -> TextButton(onClick = onDismiss) { Text(stringResource(R.string.settings_done)) }
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
                -> TextButton(onClick = onDismiss) { Text(stringResource(R.string.settings_close)) }
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
                text = stringResource(R.string.settings_new_version, update.versionName),
                style = MaterialTheme.typography.titleMedium,
                fontWeight = FontWeight.SemiBold,
            )
            Text(
                text = stringResource(R.string.settings_version_channel, update.channel.displayName),
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
    item { HorizontalDivider() }
    item {
        Text(
            text = stringResource(R.string.settings_release_notes),
            style = MaterialTheme.typography.titleSmall,
            fontWeight = FontWeight.SemiBold,
        )
    }
    item {
        Text(
            text = update.changelog.trim().ifBlank { stringResource(R.string.settings_no_release_notes) },
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
                stringResource(R.string.settings_downloaded_bytes, formatBytes(progress.downloadedBytes))
            } else {
                stringResource(
                R.string.settings_downloaded_bytes_total,
                formatBytes(progress.downloadedBytes),
                formatBytes(progress.totalBytes),
            )
            },
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}

@Composable
private fun appUpdateDialogTitle(status: AppUpdateStatus): String = when (status) {
    AppUpdateStatus.Idle,
    AppUpdateStatus.Checking,
    -> stringResource(R.string.settings_check_updates)
    AppUpdateStatus.UpToDate -> stringResource(R.string.settings_up_to_date_title)
    is AppUpdateStatus.UpdateAvailable,
    is AppUpdateStatus.Downloading,
    is AppUpdateStatus.Downloaded,
    is AppUpdateStatus.Installing,
    -> stringResource(R.string.settings_new_version_title)
    is AppUpdateStatus.Error -> stringResource(R.string.settings_update_failed_title)
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
