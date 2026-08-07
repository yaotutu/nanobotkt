package com.nanobotkt.feature.chat

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.asPaddingValues
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBars
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBars
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Close
import androidx.compose.material.icons.rounded.Search
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.OutlinedTextFieldDefaults
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import com.nanobotkt.core.model.UiMessage
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * Right-side drawer modal for navigating between user prompts.
 * Mirrors the RN PromptNavigator component: slides in from the right
 * (92% width, max 384dp), search box filters by label + preview,
 * tapping a prompt closes the sheet and scrolls to the message.
 */
@Composable
fun PromptNavigatorSheet(
    messages: List<UiMessage>,
    visible: Boolean,
    onClose: () -> Unit,
    onJumpToPrompt: (String) -> Unit,
) {
    if (!visible) return

    var query by remember { mutableStateOf("") }

    // Reset search when sheet opens
    LaunchedEffect(visible) {
        if (visible) query = ""
    }

    val allPrompts = remember(messages) { extractPromptAnchors(messages) }
    val filtered = remember(allPrompts, query) { filterPrompts(allPrompts, query) }

    // Safe-area insets — mirrors RN useSafeAreaInsets() with min padding
    val statusBarHeight: Dp = WindowInsets.statusBars.asPaddingValues()
        .calculateTopPadding()
    val navBarHeight: Dp = WindowInsets.navigationBars.asPaddingValues()
        .calculateBottomPadding()

    Dialog(
        onDismissRequest = onClose,
        properties = DialogProperties(
            usePlatformDefaultWidth = false,
            decorFitsSystemWindows = false,
        ),
    ) {
        Box(modifier = Modifier.fillMaxSize()) {
            // Scrim — tap to dismiss
            Box(
                modifier = Modifier
                    .fillMaxSize()
                    .background(MaterialTheme.colorScheme.scrim.copy(alpha = 0.28f))
                    .clickable(onClick = onClose),
            )

            // Sheet — right-aligned
            Surface(
                modifier = Modifier
                    .fillMaxHeight()
                    .widthIn(max = 384.dp)
                    .fillMaxWidth(0.92f)
                    .align(Alignment.CenterEnd),
                shape = RoundedCornerShape(topStart = 20.dp, bottomStart = 20.dp),
                tonalElevation = 3.dp,
                shadowElevation = 12.dp,
            ) {
                Column(
                    modifier = Modifier
                        .fillMaxSize()
                        .padding(top = statusBarHeight.coerceAtLeast(16.dp))
                        .padding(bottom = navBarHeight.coerceAtLeast(12.dp)),
                ) {
                    // Header
                    Column(
                        modifier = Modifier.padding(
                            start = 18.dp,
                            end = 8.dp,
                            top = 0.dp,
                            bottom = 12.dp,
                        ),
                    ) {
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            Text(
                                text = stringResource(R.string.prompt_navigator_title),
                                style = MaterialTheme.typography.titleMedium,
                                modifier = Modifier.weight(1f),
                            )
                            IconButton(onClick = {
                                query = ""
                                onClose()
                            }) {
                                Icon(
                                    Icons.Rounded.Close,
                                    stringResource(R.string.cancel),
                                )
                            }
                        }

                        Spacer(Modifier.height(8.dp))

                        OutlinedTextField(
                            value = query,
                            onValueChange = { query = it },
                            modifier = Modifier.fillMaxWidth(),
                            placeholder = {
                                Text(stringResource(R.string.prompt_navigator_search))
                            },
                            leadingIcon = {
                                Icon(
                                    Icons.Rounded.Search,
                                    contentDescription = null,
                                    modifier = Modifier.size(18.dp),
                                )
                            },
                            trailingIcon = {
                                if (query.isNotEmpty()) {
                                    IconButton(onClick = { query = "" }) {
                                        Icon(
                                            Icons.Rounded.Close,
                                            stringResource(R.string.cancel),
                                            Modifier.size(16.dp),
                                        )
                                    }
                                }
                            },
                            singleLine = true,
                            shape = RoundedCornerShape(24.dp),
                            colors = OutlinedTextFieldDefaults.colors(
                                focusedBorderColor = MaterialTheme.colorScheme.outline,
                                unfocusedBorderColor = MaterialTheme.colorScheme.outlineVariant,
                            ),
                        )
                    }

                    HorizontalDivider()

                    // List
                    if (filtered.isEmpty()) {
                        Box(
                            modifier = Modifier
                                .fillMaxSize()
                                .padding(24.dp),
                            contentAlignment = Alignment.Center,
                        ) {
                            Text(
                                text = stringResource(R.string.prompt_navigator_no_results),
                                style = MaterialTheme.typography.bodyMedium,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                        }
                    } else {
                        LazyColumn(
                            modifier = Modifier.fillMaxSize(),
                            contentPadding = PaddingValues(8.dp),
                        ) {
                            items(filtered, key = { it.stableId }) { item ->
                                Surface(
                                    onClick = {
                                        query = ""
                                        onClose()
                                        onJumpToPrompt(item.messageId)
                                    },
                                    shape = RoundedCornerShape(14.dp),
                                    color = MaterialTheme.colorScheme.surface,
                                    modifier = Modifier
                                        .fillMaxWidth()
                                        .padding(horizontal = 4.dp, vertical = 2.dp),
                                ) {
                                    Column(modifier = Modifier.padding(12.dp)) {
                                        Text(
                                            text = item.preview,
                                            maxLines = 4,
                                            overflow = TextOverflow.Ellipsis,
                                            style = MaterialTheme.typography.bodyMedium,
                                        )
                                        if (item.createdAt > 0) {
                                            Text(
                                                text = formatPromptTimestamp(item.createdAt),
                                                modifier = Modifier.padding(top = 5.dp),
                                                style = MaterialTheme.typography.labelSmall,
                                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                                            )
                                        }
                                    }
                                }
                            }
                        }
                    }
                }
            }
        }
    }
}

private val promptTimestampFormat =
    SimpleDateFormat("yyyy-MM-dd HH:mm", Locale.getDefault())

private fun formatPromptTimestamp(epochMs: Long): String =
    promptTimestampFormat.format(Date(epochMs))
