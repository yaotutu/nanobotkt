package com.nanobotkt.feature.chat

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
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
import androidx.compose.material.icons.rounded.AttachFile
import androidx.compose.material.icons.rounded.ChatBubbleOutline
import androidx.compose.material.icons.rounded.Image
import androidx.compose.material.icons.rounded.Stop
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.TextRange
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.TextFieldValue
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.IntRect
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Popup
import androidx.compose.ui.window.PopupPositionProvider
import androidx.compose.ui.window.PopupProperties

/** Chat Composer 文本输入，保留现有光标、IME Send 和最多五行的内部滚动行为。 */
@Composable
internal fun ComposerTextField(
    state: ComposerUiState,
    modifier: Modifier,
    placeholder: String,
    textColor: Color,
    mutedColor: Color,
    sendAllowed: Boolean,
    onTextChange: (String, Int) -> Unit,
    onSend: () -> Unit,
) {
    val hasDraft =
        state.text.isNotBlank() ||
            state.attachments.isNotEmpty() ||
            !state.quotedContext.isNullOrBlank()

    // TextFieldValue 除了可见文本和光标，还携带由输入法拥有的 composition 区间。搜狗等输入法
    // 即使输入英文，也会用 composition 维护当前单词的联想、纠错和候选状态。如果每次重组都只
    // 根据 ViewModel 中的 String 重建 TextFieldValue，composition 会被置空；Compose 会把这
    // 解释为“接受并结束当前组合文本”，从而导致输入连接反复同步，表现为快速输入时偶发丢字。
    // 因此编辑期间必须由输入框本地立即保存输入法返回的完整值，业务层继续只持有可持久化的
    // 文本和光标，避免把短生命周期的 IME 状态放进 ViewModel。
    var localValue by remember {
        mutableStateOf(
            TextFieldValue(
                text = state.text,
                selection = TextRange(state.cursorPosition.coerceIn(0, state.text.length)),
            )
        )
    }
    val fieldValue =
        reconcileComposerFieldValue(
            localValue = localValue,
            externalText = state.text,
            externalCursorPosition = state.cursorPosition,
        )

    // 外部动作（发送后清空、选择 Slash Command、插入 Mention、语音转写或切换会话）可能直接
    // 修改 ViewModel 文本。把协调后的值回存到本地，确保下一次输入事件基于最新编辑缓冲区；
    // 普通按键的外部状态只是本地值的回声，此时协调函数会原样返回 localValue，不会清除
    // composition 或折叠输入法维护的选择范围。
    SideEffect {
        if (localValue != fieldValue) localValue = fieldValue
    }

    BasicTextField(
        value = fieldValue,
        onValueChange = { value ->
            // 必须先同步保存完整 TextFieldValue，再把业务需要的字段上报。这样即使 ViewModel
            // 更新触发整页重组，下一帧仍能把同一个 composition 返回给输入法。
            localValue = value
            onTextChange(value.text, value.selection.end)
        },
        modifier =
            modifier.heightIn(min = 48.dp, max = 128.dp).semantics {
                contentDescription = placeholder
            },
        enabled = !state.sending,
        textStyle = MaterialTheme.typography.bodyLarge.copy(color = textColor),
        keyboardOptions = KeyboardOptions(imeAction = ImeAction.Send),
        keyboardActions =
            KeyboardActions(
                onSend = {
                    // Active turn 期间输入法仍可编辑 Draft，但不得绕过 Stop-only 产品约束发消息。
                    if (sendAllowed && hasDraft && !state.sending) onSend()
                }
            ),
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
 * 协调输入框本地编辑状态与 ViewModel 的业务状态。
 *
 * 文本和光标都一致时，外部状态只是 [BasicTextField] 回调的回声，必须返回原对象以保留 IME
 * composition 以及完整 selection。只有外部确实改变了文本或光标时，才创建新的编辑值并主动
 * 结束旧 composition；此类变化来自发送清空、命令插入或会话切换，继续沿用旧组合区间反而会
 * 让输入法把后续字符写入已经失效的文本位置。
 */
internal fun reconcileComposerFieldValue(
    localValue: TextFieldValue,
    externalText: String,
    externalCursorPosition: Int,
): TextFieldValue {
    val externalCursor = externalCursorPosition.coerceIn(0, externalText.length)
    return if (
        localValue.text == externalText && localValue.selection.end == externalCursor
    ) {
        localValue
    } else {
        TextFieldValue(
            text = externalText,
            selection = TextRange(externalCursor),
        )
    }
}

/**
 * 输入框外侧的会话导航按钮。
 *
 * 该按钮与输入胶囊保持独立的 Surface 和点击语义：它切换的是当前聊天上下文，不会被误解为
 * 当前消息的附件或发送动作。即使输入框扩展为多行，父级 Row 也会让它固定贴在底部，保持单手
 * 操作时的空间记忆；48dp 的外层触控区域满足 Android 无障碍最小目标尺寸。
 */
@Composable
internal fun ConversationListButton(onClick: () -> Unit) {
    Surface(
        onClick = onClick,
        modifier = Modifier.size(48.dp),
        shape = CircleShape,
        color = MaterialTheme.colorScheme.surfaceContainerHigh,
        border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.48f)),
        tonalElevation = 1.dp,
        shadowElevation = 0.dp,
    ) {
        Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
            Icon(
                imageVector = Icons.Rounded.ChatBubbleOutline,
                contentDescription = stringResource(R.string.open_conversation_list),
                modifier = Modifier.size(21.dp),
                tint = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}

/**
 * 输入框左侧的“+”严格限定为附件菜单。
 *
 * 采用自定义定位的 Popup，而不是直接使用 DropdownMenu 的默认窗口兜底策略。默认策略在底部
 * Composer 与输入法联动时可能把菜单回退到窗口顶部；这里仍保留 Popup 的焦点管理、返回键和
 * 点击外部关闭能力，只替换定位规则，让菜单始终贴近触发按钮。
 */
@Composable
internal fun AttachmentMenuButton(
    enabled: Boolean,
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
            // “+”只是输入胶囊内的次级入口，不再绘制独立圆底，避免和发送按钮争夺层级。
            color = Color.Transparent,
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

        if (expanded) {
            val density = LocalDensity.current
            val positionProvider =
                remember(density) {
                    AttachmentMenuPositionProvider(
                        edgeMarginPx = with(density) { 8.dp.roundToPx() },
                        anchorGapPx = with(density) { 4.dp.roundToPx() },
                    )
                }
            Popup(
                popupPositionProvider = positionProvider,
                onDismissRequest = { expanded = false },
                // focusable=true 使返回键和点击 Popup 外部都能触发 dismiss，行为与原菜单一致。
                properties = PopupProperties(focusable = true),
            ) {
                Surface(
                    shape = MaterialTheme.shapes.large,
                    color = MaterialTheme.colorScheme.surfaceContainer,
                    tonalElevation = 3.dp,
                    shadowElevation = 6.dp,
                ) {
                    Column {
                        DropdownMenuItem(
                            text = { Text(stringResource(R.string.attach_image)) },
                            leadingIcon = {
                                Icon(Icons.Rounded.Image, contentDescription = null)
                            },
                            onClick = {
                                expanded = false
                                onPickImages()
                            },
                        )
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
}

/**
 * 将附件菜单固定到“+”按钮的近邻区域，并对窗口边界做最小限度保护。
 *
 * Popup 的 anchorBounds 是包裹触发按钮的 Box 边界。优先尝试按钮上方 4dp，符合底部输入区的
 * 操作习惯；如果上方空间不足则改放到按钮下方，最后才在窗口内裁切。这样键盘显示/隐藏、
 * 横竖屏和不同密度下都不会把菜单放到与触发按钮无关的窗口角落。
 */
internal data class AttachmentMenuPositionProvider(
    val edgeMarginPx: Int,
    val anchorGapPx: Int,
) : PopupPositionProvider {
    override fun calculatePosition(
        anchorBounds: IntRect,
        windowSize: IntSize,
        layoutDirection: LayoutDirection,
        popupContentSize: IntSize,
    ): IntOffset {
        val preferredX =
            if (layoutDirection == LayoutDirection.Ltr) {
                anchorBounds.left
            } else {
                anchorBounds.right - popupContentSize.width
            }
        val maxX =
            (windowSize.width - edgeMarginPx - popupContentSize.width)
                .coerceAtLeast(edgeMarginPx)
        val x = preferredX.coerceIn(edgeMarginPx, maxX)

        val aboveY = anchorBounds.top - popupContentSize.height - anchorGapPx
        val belowY = anchorBounds.bottom + anchorGapPx
        val maxY =
            (windowSize.height - edgeMarginPx - popupContentSize.height)
                .coerceAtLeast(edgeMarginPx)
        val y =
            when {
                aboveY >= edgeMarginPx -> aboveY
                belowY <= maxY -> belowY
                else -> aboveY.coerceIn(edgeMarginPx, maxY)
            }
        return IntOffset(x = x, y = y)
    }
}

/** 输入框右侧只负责发送或停止，发送中使用进度指示并禁止重复点击。 */
@Composable
internal fun ComposerPrimaryActionButton(
    showSendAction: Boolean,
    stopButton: Boolean,
    sendEnabled: Boolean,
    sending: Boolean,
    stopping: Boolean = false,
    controlColor: Color,
    sendColor: Color,
    sendContentColor: Color,
    onSend: () -> Unit,
    onStop: () -> Unit,
) {
    // 停止请求一旦进入 pending，按钮立即禁用并显示进度，防止连续点击产生多条 `/stop`。
    val enabled = (stopButton && !stopping) || (showSendAction && sendEnabled)
    val actionDescription = stringResource(if (stopButton) R.string.stop else R.string.send)
    Surface(
        onClick = {
            when {
                stopButton -> onStop()
                showSendAction -> onSend()
            }
        },
        enabled = enabled,
        // 描述放在始终存在的点击容器上；停止图标替换为 spinner 后，无障碍节点仍保留
        // “停止”语义和 disabled 状态，测试与读屏都能感知请求已经进入 pending。
        modifier = Modifier.size(48.dp).semantics { contentDescription = actionDescription },
        shape = CircleShape,
        // 外层透明 Surface 保留完整 48dp 点击与涟漪区域，内部视觉圆只占 40dp，
        // 因而输入胶囊更轻巧，同时不会牺牲无障碍触控尺寸。
        color = Color.Transparent,
    ) {
        Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
            Surface(
                modifier = Modifier.size(40.dp),
                shape = CircleShape,
                color = if (showSendAction || stopButton) sendColor else controlColor,
            ) {
                Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                    when {
                        sending || stopping ->
                            CircularProgressIndicator(
                                Modifier.size(18.dp),
                                strokeWidth = 2.dp,
                                color = sendContentColor,
                            )
                        stopButton ->
                            Icon(
                                Icons.Rounded.Stop,
                                contentDescription = null,
                                modifier = Modifier.size(20.dp),
                                tint = sendContentColor,
                            )
                        else ->
                            // 纸飞机和“回到底部”的下箭头具有不同轮廓，降低两个右侧动作的导航歧义。
                            Icon(
                                Icons.AutoMirrored.Rounded.Send,
                                contentDescription = null,
                                modifier = Modifier.size(20.dp),
                                tint = sendContentColor,
                            )
                    }
                }
            }
        }
    }
}
