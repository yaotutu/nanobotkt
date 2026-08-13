package com.nanobotkt.feature.chat

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Add
import androidx.compose.material.icons.rounded.ArrowUpward
import androidx.compose.material.icons.rounded.AttachFile
import androidx.compose.material.icons.rounded.Image
import androidx.compose.material.icons.rounded.Stop
import androidx.compose.material3.CircularProgressIndicator
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
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.TextRange
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.TextFieldValue
import androidx.compose.ui.unit.dp

/** Chat Composer 文本输入，保留现有光标、IME Send 和最多五行的内部滚动行为。 */
@Composable
internal fun ComposerTextField(
    state: ComposerUiState,
    modifier: Modifier,
    placeholder: String,
    textColor: Color,
    mutedColor: Color,
    onTextChange: (String, Int) -> Unit,
    onSend: () -> Unit,
) {
    val hasDraft =
        state.text.isNotBlank() ||
            state.attachments.isNotEmpty() ||
            !state.quotedContext.isNullOrBlank()

    BasicTextField(
        value =
            TextFieldValue(
                text = state.text,
                selection = TextRange(state.cursorPosition.coerceIn(0, state.text.length)),
            ),
        onValueChange = { value -> onTextChange(value.text, value.selection.end) },
        modifier =
            modifier.heightIn(min = 48.dp, max = 128.dp).semantics {
                contentDescription = placeholder
            },
        enabled = !state.sending,
        textStyle = MaterialTheme.typography.bodyLarge.copy(color = textColor),
        keyboardOptions = KeyboardOptions(imeAction = ImeAction.Send),
        keyboardActions = KeyboardActions(onSend = { if (hasDraft && !state.sending) onSend() }),
        maxLines = 5,
        decorationBox = { innerTextField ->
            Box(
                modifier = Modifier.fillMaxWidth().padding(horizontal = 6.dp, vertical = 10.dp),
                contentAlignment = Alignment.CenterStart,
            ) {
                if (state.text.isEmpty()) {
                    Text(
                        text = placeholder,
                        color = mutedColor,
                        style = MaterialTheme.typography.bodyLarge,
                    )
                }
                innerTextField()
            }
        },
    )
}

/**
 * 输入框左侧的“+”严格限定为附件菜单。
 *
 * 采用锚定 DropdownMenu 而不是多页面 Sheet，使图片/文件保持两项轻量操作；模型、权限和
 * Workspace 不再经过此入口，相关能力由顶部当前会话配置承接。
 */
@Composable
internal fun AttachmentMenuButton(
    enabled: Boolean,
    controlColor: Color,
    onPickImages: () -> Unit,
    onPickFiles: () -> Unit,
) {
    var expanded by remember { mutableStateOf(false) }
    Box {
        Surface(
            onClick = { expanded = true },
            enabled = enabled,
            modifier = Modifier.size(48.dp),
            shape = CircleShape,
            color = controlColor,
        ) {
            Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                Icon(
                    Icons.Rounded.Add,
                    contentDescription = stringResource(R.string.add_attachment),
                    modifier = Modifier.size(20.dp),
                    tint = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
        DropdownMenu(expanded = expanded, onDismissRequest = { expanded = false }) {
            CHAT_ATTACHMENT_ACTIONS.forEach { action ->
                when (action) {
                    AttachmentMenuAction.IMAGES ->
                        DropdownMenuItem(
                            text = { Text(stringResource(R.string.attach_image)) },
                            leadingIcon = { Icon(Icons.Rounded.Image, contentDescription = null) },
                            onClick = {
                                expanded = false
                                onPickImages()
                            },
                        )
                    AttachmentMenuAction.FILES ->
                        DropdownMenuItem(
                            text = { Text(stringResource(R.string.attach_file)) },
                            leadingIcon = {
                                Icon(Icons.Rounded.AttachFile, contentDescription = null)
                            },
                            onClick = {
                                expanded = false
                                onPickFiles()
                            },
                        )
                }
            }
        }
    }
}

/** 输入框右侧只负责发送或停止，发送中使用进度指示并禁止重复点击。 */
@Composable
internal fun ComposerPrimaryActionButton(
    showSendAction: Boolean,
    stopButton: Boolean,
    sendEnabled: Boolean,
    sending: Boolean,
    controlColor: Color,
    sendColor: Color,
    sendContentColor: Color,
    onSend: () -> Unit,
    onStop: () -> Unit,
) {
    val enabled = stopButton || (showSendAction && sendEnabled)
    Surface(
        onClick = {
            when {
                stopButton -> onStop()
                showSendAction -> onSend()
            }
        },
        enabled = enabled,
        modifier = Modifier.size(48.dp),
        shape = CircleShape,
        color = if (showSendAction || stopButton) sendColor else controlColor,
    ) {
        Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
            when {
                sending ->
                    CircularProgressIndicator(
                        Modifier.size(20.dp),
                        strokeWidth = 2.dp,
                        color = sendContentColor,
                    )
                stopButton ->
                    Icon(
                        Icons.Rounded.Stop,
                        contentDescription = stringResource(R.string.stop),
                        tint = sendContentColor,
                    )
                else ->
                    Icon(
                        Icons.Rounded.ArrowUpward,
                        contentDescription = stringResource(R.string.send),
                        tint = sendContentColor,
                    )
            }
        }
    }
}
