package com.nanobotkt.feature.chat

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Close
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.Text
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import com.nanobotkt.core.designsystem.NanobotThemeDefaults
import com.nanobotkt.core.model.SessionAutomationJob
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp

/**
 * Bottom-sheet modal showing session details.
 * Mirrors the RN SessionInfoModal:
 * - Bottom sheet with drag handle.
 * - Eyebrow "Session" + title (or "Untitled topic" fallback).
 * - Divider + SessionAutomationList (loading / error / empty / job rows).
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SessionInfoSheet(
    title: String,
    sessionKey: String?,
    loadJobs: suspend (String) -> List<SessionAutomationJob>,
    visible: Boolean,
    onClose: () -> Unit,
) {
    if (!visible) return

    val sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)

    ModalBottomSheet(
        onDismissRequest = onClose,
        sheetState = sheetState,
        // BottomSheet 直接复用设计系统的 extraLarge 形状，Light/Dark 只切换色彩角色。
        shape = MaterialTheme.shapes.extraLarge,
        containerColor = MaterialTheme.colorScheme.surface,
        tonalElevation = 1.dp,
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = NanobotThemeDefaults.spacing.md),
        ) {
            // Title row
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
                    Text(
                        text = title.ifBlank {
                            stringResource(R.string.session_info_untitled)
                        },
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                        style = MaterialTheme.typography.titleMedium,
                        modifier = Modifier.padding(top = NanobotThemeDefaults.spacing.xxs),
                    )
                }
                IconButton(onClick = onClose) {
                    Icon(
                        Icons.Rounded.Close,
                        stringResource(R.string.session_info_title),
                    )
                }
            }

            Spacer(Modifier.height(NanobotThemeDefaults.spacing.sm))
            HorizontalDivider()
            Spacer(Modifier.height(NanobotThemeDefaults.spacing.sm))

            SessionAutomationList(
                sessionKey = sessionKey,
                loadJobs = loadJobs,
                visible = visible,
            )

            Spacer(Modifier.height(NanobotThemeDefaults.spacing.lg))
        }
    }
}
