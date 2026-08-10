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
import androidx.compose.material.icons.automirrored.rounded.Send
import androidx.compose.material.icons.rounded.Add
import androidx.compose.material.icons.rounded.ArrowUpward
import androidx.compose.material.icons.rounded.Stop
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
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
import com.nanobotkt.core.model.WorkspaceScope
import com.nanobotkt.core.model.WorkspacesPayload
import kotlinx.coroutines.launch

/** Composer 文本输入框与快捷操作按钮，保持输入法和附件行为集中。 */
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
            Box(modifier = Modifier.fillMaxWidth().padding(horizontal = 4.dp, vertical = 4.dp)) {
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
 * 加号和发送共用一个位置，避免低频入口长期占据输入区空间。 只有当前没有可发送草稿时才打开二级菜单；附件或引用本身也是可发送 payload，
 * 因此即使文字为空，只要已经选中了附件/引用，也必须保留发送能力。
 *
 * 输入区右侧的动作入口。
 *
 * 这里刻意把“更多”做成单一 BottomSheet，而不是 DropdownMenu：模型和权限都是低频配置， 但它们仍需要完整的选择内容。点击模型或权限时只切换当前 Sheet
 * 的内容页，不创建第二个 Dialog 或导航页面，避免出现“弹层里面再套弹层”以及两个窗口同时抢焦点。
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun ComposerActionButton(
    showSendAction: Boolean,
    stopButton: Boolean,
    sendEnabled: Boolean,
    sending: Boolean,
    voiceRecording: Boolean,
    voiceTranscribing: Boolean,
    controlColor: Color,
    sendColor: Color,
    sendContentColor: Color,
    workspaceScope: WorkspaceScope?,
    workspaces: WorkspacesPayload?,
    model: ChatModelSelection,
    active: Boolean,
    onWorkspaceChange: (WorkspaceScope) -> Unit,
    onModelChange: (String) -> Unit,
    onOpenModelSettings: () -> Unit,
    onPickImages: () -> Unit,
    onPickFiles: () -> Unit,
    onSend: () -> Unit,
    onStop: () -> Unit,
) {
    var moreSheetOpen by remember { mutableStateOf(false) }
    var moreSheetPage by remember { mutableStateOf(ComposerMorePage.Root) }
    val sheetState =
        androidx.compose.material3.rememberModalBottomSheetState(skipPartiallyExpanded = true)
    val coroutineScope = rememberCoroutineScope()
    val activeScope = workspaceScope ?: workspaces?.defaultScope

    /**
     * 关闭“更多”面板，并在动画真正结束后执行后续动作。 图片和文件会把动作交给系统选择器；模型设置则会离开当前面板进入 Settings。 统一从这里收口，可以避免系统选择器或
     * Settings 与 BottomSheet 同时挂在窗口上。
     */
    fun dismissSheetThen(action: () -> Unit) {
        coroutineScope.launch {
            sheetState.hide()
            moreSheetOpen = false
            moreSheetPage = ComposerMorePage.Root
            action()
        }
    }

    /**
     * 模型和权限不再关闭 Sheet 后打开第二个 Dialog，而是在同一个 Sheet 内切换内容页。
     * 这样用户始终知道自己仍在“+”菜单中，返回也只需回到一级菜单，不会产生弹层套弹层的错觉。
     */
    fun showMorePage(page: ComposerMorePage) {
        moreSheetPage = page
    }

    val enabled =
        when {
            stopButton -> true
            showSendAction -> sendEnabled
            else -> !voiceRecording && !voiceTranscribing && !sending
        }
    Box {
        Surface(
            onClick = {
                when {
                    stopButton -> onStop()
                    showSendAction -> onSend()
                    else -> {
                        moreSheetPage = ComposerMorePage.Root
                        moreSheetOpen = true
                    }
                }
            },
            enabled = enabled,
            modifier = Modifier.size(48.dp),
            shape = CircleShape,
            color = if (showSendAction || stopButton) sendColor else controlColor,
            tonalElevation = 2.dp,
            shadowElevation = 0.dp,
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
                    showSendAction ->
                        Icon(
                            Icons.Rounded.ArrowUpward,
                            contentDescription = stringResource(R.string.send),
                            tint = sendContentColor,
                        )
                    else ->
                        Icon(
                            Icons.Rounded.Add,
                            contentDescription = stringResource(R.string.composer_more),
                            modifier = Modifier.size(20.dp),
                            tint = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                }
            }
        }

        if (moreSheetOpen) {
            ModalBottomSheet(
                onDismissRequest = {
                    moreSheetOpen = false
                    moreSheetPage = ComposerMorePage.Root
                },
                sheetState = sheetState,
            ) {
                ComposerMoreSheet(
                    page = moreSheetPage,
                    model = model,
                    activeScope = activeScope,
                    controls = workspaces?.controls,
                    modelEnabled = model.enabled && !sending,
                    accessEnabled = activeScope != null && !active,
                    disabled = sending || active,
                    onAction = { action ->
                        when (action) {
                            ComposerMoreAction.Images -> dismissSheetThen(onPickImages)
                            ComposerMoreAction.Files -> dismissSheetThen(onPickFiles)
                            ComposerMoreAction.Model -> showMorePage(ComposerMorePage.Model)
                            ComposerMoreAction.Access -> showMorePage(ComposerMorePage.Access)
                        }
                    },
                    onModelChange = onModelChange,
                    onOpenModelSettings = { dismissSheetThen(onOpenModelSettings) },
                    onWorkspaceChange = onWorkspaceChange,
                    onDismissSheet = { dismissSheetThen {} },
                    onBack = { moreSheetPage = ComposerMorePage.Root },
                )
            }
        }
    }
}
