package com.nanobotkt.feature.chat

import android.content.ClipData
import android.content.ClipboardManager
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.CheckBox
import androidx.compose.material.icons.rounded.CheckBoxOutlineBlank
import androidx.compose.material.icons.rounded.Close
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.LinkAnnotation
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextDecoration
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import coil.compose.AsyncImage

/** 受控的 Markdown 块模型；只覆盖聊天正文已确认的首阶段能力，不执行 HTML 或脚本。 */
internal sealed interface MarkdownBlock {
    data class Heading(val level: Int, val text: String) : MarkdownBlock
    data class Paragraph(val text: String) : MarkdownBlock
    data class Quote(val text: String) : MarkdownBlock
    data class ListItem(
        val text: String,
        val orderedIndex: Int? = null,
        val checked: Boolean? = null,
    ) : MarkdownBlock
    data class Code(val language: String?, val content: String, val closed: Boolean) : MarkdownBlock
    data class Table(val headers: List<String>, val rows: List<List<String>>) : MarkdownBlock
    data class Image(val alt: String, val url: String) : MarkdownBlock
    data object Divider : MarkdownBlock
}

/**
 * 行级解析器故意保持纯函数：流式正文可能停在未闭合代码围栏、链接或强调符号中，解析失败时必须
 * 把原文降级成普通文本，而不是抛异常或让整个 Assistant 正文空白。
 */
internal fun parseMarkdownBlocks(markdown: String): List<MarkdownBlock> {
    if (markdown.isBlank()) return emptyList()
    val lines = markdown.replace("\r\n", "\n").replace('\r', '\n').split('\n')
    val result = mutableListOf<MarkdownBlock>()
    var index = 0

    while (index < lines.size) {
        val line = lines[index]
        if (line.isBlank()) {
            index += 1
            continue
        }

        val fence = Regex("^\\s*```\\s*([A-Za-z0-9_+.#-]*)\\s*$").matchEntire(line)
        if (fence != null) {
            val language = fence.groupValues[1].ifBlank { null }
            val code = mutableListOf<String>()
            index += 1
            var closed = false
            while (index < lines.size) {
                if (Regex("^\\s*```\\s*$").matches(lines[index])) {
                    closed = true
                    index += 1
                    break
                }
                code += lines[index]
                index += 1
            }
            result += MarkdownBlock.Code(language, code.joinToString("\n"), closed)
            continue
        }

        val image = Regex("^\\s*!\\[([^]]*)]\\(([^)\\s]+)(?:\\s+\"[^\"]*\")?\\)\\s*$").matchEntire(line)
        if (image != null) {
            result += MarkdownBlock.Image(image.groupValues[1], image.groupValues[2])
            index += 1
            continue
        }

        val heading = Regex("^(#{1,6})\\s+(.+)$").matchEntire(line)
        if (heading != null) {
            result += MarkdownBlock.Heading(heading.groupValues[1].length, heading.groupValues[2].trim())
            index += 1
            continue
        }

        if (Regex("^\\s*(?:---+|___+|\\*\\*\\*+)\\s*$").matches(line)) {
            result += MarkdownBlock.Divider
            index += 1
            continue
        }

        if (line.trimStart().startsWith(">")) {
            val quoteLines = mutableListOf<String>()
            while (index < lines.size && lines[index].trimStart().startsWith(">")) {
                quoteLines += lines[index].trimStart().removePrefix(">").removePrefix(" ")
                index += 1
            }
            result += MarkdownBlock.Quote(quoteLines.joinToString("\n"))
            continue
        }

        if (index + 1 < lines.size && isTableSeparator(lines[index + 1]) && line.contains('|')) {
            val headers = splitTableRow(line)
            val rows = mutableListOf<List<String>>()
            index += 2
            while (index < lines.size && lines[index].contains('|') && lines[index].isNotBlank()) {
                rows += splitTableRow(lines[index])
                index += 1
            }
            result += MarkdownBlock.Table(headers, rows)
            continue
        }

        val list = parseListLine(line)
        if (list != null) {
            result += list
            index += 1
            continue
        }

        val paragraph = mutableListOf(line)
        index += 1
        while (index < lines.size && lines[index].isNotBlank() && !startsMarkdownBlock(lines, index)) {
            paragraph += lines[index]
            index += 1
        }
        result += MarkdownBlock.Paragraph(paragraph.joinToString("\n"))
    }
    return result
}

private fun startsMarkdownBlock(lines: List<String>, index: Int): Boolean {
    val line = lines[index]
    return Regex("^\\s*```").containsMatchIn(line) ||
        Regex("^(#{1,6})\\s+").containsMatchIn(line) ||
        line.trimStart().startsWith(">") ||
        parseListLine(line) != null ||
        Regex("^\\s*!\\[[^]]*]\\([^)]+\\)\\s*$").matches(line) ||
        Regex("^\\s*(?:---+|___+|\\*\\*\\*+)\\s*$").matches(line) ||
        (index + 1 < lines.size && line.contains('|') && isTableSeparator(lines[index + 1]))
}

private fun parseListLine(line: String): MarkdownBlock.ListItem? {
    val match = Regex("^\\s*(?:(\\d+)\\.|[-+*])\\s+(.+)$").matchEntire(line) ?: return null
    var text = match.groupValues[2]
    val task = Regex("^\\[([ xX])]\\s*(.*)$").matchEntire(text)
    val checked = task?.groupValues?.get(1)?.equals("x", ignoreCase = true)
    if (task != null) text = task.groupValues[2]
    return MarkdownBlock.ListItem(
        text = text,
        orderedIndex = match.groupValues[1].toIntOrNull(),
        checked = if (task == null) null else checked,
    )
}

private fun isTableSeparator(line: String): Boolean {
    val cells = splitTableRow(line)
    return cells.isNotEmpty() && cells.all { Regex("^:?-{3,}:?$").matches(it.trim()) }
}

private fun splitTableRow(line: String): List<String> =
    line.trim().removePrefix("|").removeSuffix("|").split('|').map(String::trim)

/** Assistant 正文的文档式渲染入口。 */
@Composable
internal fun MarkdownDocument(
    markdown: String,
    resolveUrl: (String) -> String,
    modifier: Modifier = Modifier,
) {
    val blocks = remember(markdown) { parseMarkdownBlocks(markdown) }
    Column(modifier = modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(10.dp)) {
        blocks.forEachIndexed { index, block ->
            when (block) {
                is MarkdownBlock.Heading -> MarkdownHeading(block)
                is MarkdownBlock.Paragraph -> MarkdownText(block.text)
                is MarkdownBlock.Quote -> MarkdownQuote(block.text)
                is MarkdownBlock.ListItem -> MarkdownListItem(block)
                is MarkdownBlock.Code -> MarkdownCodeBlock(block)
                is MarkdownBlock.Table -> MarkdownTable(block)
                is MarkdownBlock.Image -> MarkdownImage(block, resolveUrl(block.url))
                MarkdownBlock.Divider ->
                    androidx.compose.material3.HorizontalDivider(
                        color = MaterialTheme.colorScheme.outlineVariant,
                        modifier = Modifier.padding(vertical = 4.dp),
                    )
            }
        }
    }
}

@Composable
private fun MarkdownHeading(block: MarkdownBlock.Heading) {
    val style =
        when (block.level) {
            1 -> MaterialTheme.typography.headlineMedium
            2 -> MaterialTheme.typography.headlineSmall
            3 -> MaterialTheme.typography.titleLarge
            else -> MaterialTheme.typography.titleMedium
        }
    Text(
        text = inlineMarkdown(block.text),
        color = MaterialTheme.colorScheme.onSurface,
        style = style,
        fontWeight = FontWeight.SemiBold,
    )
}

@Composable
private fun MarkdownText(text: String) {
    Text(
        text = inlineMarkdown(text),
        color = MaterialTheme.colorScheme.onSurface,
        style = MaterialTheme.typography.bodyLarge,
    )
}

@Composable
private fun MarkdownQuote(text: String) {
    Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
        Box(
            modifier =
                Modifier.width(3.dp)
                    .heightIn(min = 28.dp)
                    .background(MaterialTheme.colorScheme.primary, RoundedCornerShape(2.dp))
        )
        Text(
            text = inlineMarkdown(text),
            modifier = Modifier.weight(1f),
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            style = MaterialTheme.typography.bodyLarge,
            fontStyle = FontStyle.Italic,
        )
    }
}

@Composable
private fun MarkdownListItem(block: MarkdownBlock.ListItem) {
    Row(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.Top) {
        when {
            block.checked != null ->
                Icon(
                    imageVector = if (block.checked) Icons.Rounded.CheckBox else Icons.Rounded.CheckBoxOutlineBlank,
                    contentDescription = null,
                    modifier = Modifier.size(20.dp),
                    tint = MaterialTheme.colorScheme.primary,
                )
            block.orderedIndex != null ->
                Text(
                    text = "${block.orderedIndex}.",
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    style = MaterialTheme.typography.bodyLarge,
                )
            else ->
                Text(
                    text = "•",
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    style = MaterialTheme.typography.bodyLarge,
                )
        }
        Text(
            text = inlineMarkdown(block.text),
            modifier = Modifier.weight(1f),
            color = MaterialTheme.colorScheme.onSurface,
            style = MaterialTheme.typography.bodyLarge,
        )
    }
}

@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun MarkdownCodeBlock(block: MarkdownBlock.Code) {
    val context = LocalContext.current
    val clipboard = remember(context) { context.getSystemService(ClipboardManager::class.java) }
    val lines = remember(block.content) { block.content.lines() }
    var expanded by rememberSaveable(block.content) { mutableStateOf(lines.size <= 18) }
    var menuOpen by rememberSaveable(block.content) { mutableStateOf(false) }
    var detailOpen by rememberSaveable(block.content) { mutableStateOf(false) }
    val visibleContent = if (expanded) block.content else lines.take(18).joinToString("\n")

    Box {
        Surface(
            modifier =
                Modifier.fillMaxWidth().combinedClickable(
                    onClick = {},
                    onLongClick = { menuOpen = true },
                ),
            color = MaterialTheme.colorScheme.surfaceContainer,
            shape = MaterialTheme.shapes.medium,
        ) {
            Column {
                Row(
                    modifier = Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 8.dp),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Text(
                        text = block.language ?: stringResource(R.string.markdown_code),
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        style = MaterialTheme.typography.labelMedium,
                    )
                    if (!block.closed) {
                        Text(
                            text = stringResource(R.string.markdown_streaming_code),
                            color = MaterialTheme.colorScheme.primary,
                            style = MaterialTheme.typography.labelSmall,
                        )
                    }
                }
                Text(
                    text = visibleContent,
                    modifier =
                        Modifier.fillMaxWidth()
                            .horizontalScroll(rememberScrollState())
                            .padding(horizontal = 12.dp, vertical = 10.dp),
                    color = MaterialTheme.colorScheme.onSurface,
                    style = MaterialTheme.typography.bodyMedium,
                    fontFamily = FontFamily.Monospace,
                )
                if (lines.size > 18) {
                    TextButton(onClick = { expanded = !expanded }) {
                        Text(
                            if (expanded) stringResource(R.string.collapse)
                            else stringResource(R.string.expand)
                        )
                    }
                }
            }
        }
        MessageFloatingActionMenu(
            expanded = menuOpen,
            actions = listOf(MessageAction.COPY, MessageAction.VIEW),
            placeBelow = false,
            onDismiss = { menuOpen = false },
            onAction = { action ->
                menuOpen = false
                when (action) {
                    MessageAction.COPY ->
                        runCatching {
                            clipboard?.setPrimaryClip(ClipData.newPlainText("code", block.content))
                        }
                    MessageAction.VIEW -> detailOpen = true
                    else -> Unit
                }
            },
        )
    }

    if (detailOpen) {
        AlertDialog(
            onDismissRequest = { detailOpen = false },
            title = { Text(block.language ?: stringResource(R.string.markdown_code)) },
            text = {
                Text(
                    text = block.content,
                    modifier = Modifier.horizontalScroll(rememberScrollState()),
                    fontFamily = FontFamily.Monospace,
                )
            },
            confirmButton = {
                TextButton(onClick = { detailOpen = false }) { Text(stringResource(R.string.close)) }
            },
        )
    }
}

@Composable
private fun MarkdownTable(block: MarkdownBlock.Table) {
    val rows = listOf(block.headers) + block.rows
    Surface(
        modifier = Modifier.fillMaxWidth(),
        color = MaterialTheme.colorScheme.surfaceContainerLow,
        shape = MaterialTheme.shapes.medium,
    ) {
        Column(modifier = Modifier.horizontalScroll(rememberScrollState()).padding(8.dp)) {
            rows.forEachIndexed { rowIndex, row ->
                Row {
                    row.forEach { cell ->
                        Text(
                            text = inlineMarkdown(cell),
                            modifier = Modifier.widthIn(min = 112.dp, max = 220.dp).padding(8.dp),
                            color = MaterialTheme.colorScheme.onSurface,
                            style = MaterialTheme.typography.bodySmall,
                            fontWeight = if (rowIndex == 0) FontWeight.SemiBold else FontWeight.Normal,
                        )
                    }
                }
                if (rowIndex == 0) {
                    androidx.compose.material3.HorizontalDivider(
                        color = MaterialTheme.colorScheme.outlineVariant
                    )
                }
            }
        }
    }
}

@Composable
private fun MarkdownImage(block: MarkdownBlock.Image, resolvedUrl: String) {
    var previewOpen by rememberSaveable(resolvedUrl) { mutableStateOf(false) }
    var failed by remember(resolvedUrl) { mutableStateOf(false) }
    Surface(
        modifier = Modifier.fillMaxWidth().heightIn(max = 360.dp).clip(MaterialTheme.shapes.medium),
        color = MaterialTheme.colorScheme.surfaceVariant,
        onClick = { if (!failed) previewOpen = true },
    ) {
        if (failed) {
            Box(modifier = Modifier.fillMaxWidth().heightIn(min = 120.dp), contentAlignment = Alignment.Center) {
                Text(
                    text = block.alt.ifBlank { stringResource(R.string.media_image_load_failed) },
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        } else {
            AsyncImage(
                model = resolvedUrl,
                contentDescription = block.alt,
                modifier = Modifier.fillMaxWidth(),
                contentScale = ContentScale.FillWidth,
                onError = { failed = true },
            )
        }
    }
    if (previewOpen) {
        Dialog(
            onDismissRequest = { previewOpen = false },
            properties = DialogProperties(usePlatformDefaultWidth = false),
        ) {
            Box(modifier = Modifier.fillMaxSize().background(Color.Black)) {
                AsyncImage(
                    model = resolvedUrl,
                    contentDescription = block.alt,
                    modifier = Modifier.fillMaxSize().padding(16.dp),
                    contentScale = ContentScale.Fit,
                )
                IconButton(
                    onClick = { previewOpen = false },
                    modifier = Modifier.align(Alignment.TopEnd).padding(12.dp),
                ) {
                    Icon(Icons.Rounded.Close, contentDescription = stringResource(R.string.close), tint = Color.White)
                }
            }
        }
    }
}

/**
 * 小型行内解析器。每个规则只有在找到成对结束标记时才应用样式；流式阶段的未闭合标记原样显示，
 * 因而不会因半个 `**`、反引号或链接而闪烁。HTML 始终只是普通文本。
 */
@Composable
private fun inlineMarkdown(text: String): AnnotatedString {
    val primary = MaterialTheme.colorScheme.primary
    val codeBackground = MaterialTheme.colorScheme.surfaceContainerHighest
    return remember(text, primary, codeBackground) {
        buildAnnotatedString {
            var cursor = 0
            while (cursor < text.length) {
                val token = nextInlineToken(text, cursor)
                if (token == null) {
                    append(text.substring(cursor))
                    break
                }
                if (token.start > cursor) append(text.substring(cursor, token.start))
                when (token.kind) {
                    InlineKind.BOLD -> pushStyle(SpanStyle(fontWeight = FontWeight.Bold))
                    InlineKind.ITALIC -> pushStyle(SpanStyle(fontStyle = FontStyle.Italic))
                    InlineKind.STRIKE -> pushStyle(SpanStyle(textDecoration = TextDecoration.LineThrough))
                    InlineKind.CODE ->
                        pushStyle(
                            SpanStyle(
                                fontFamily = FontFamily.Monospace,
                                background = codeBackground,
                            )
                        )
                    InlineKind.LINK -> Unit
                }
                if (token.kind == InlineKind.LINK && token.url != null) {
                    pushLink(LinkAnnotation.Url(token.url))
                    pushStyle(SpanStyle(color = primary, textDecoration = TextDecoration.Underline))
                    append(token.content)
                    pop()
                    pop()
                } else {
                    append(token.content)
                    pop()
                }
                cursor = token.end
            }
        }
    }
}

private enum class InlineKind { BOLD, ITALIC, STRIKE, CODE, LINK }
private data class InlineToken(
    val start: Int,
    val end: Int,
    val content: String,
    val kind: InlineKind,
    val url: String? = null,
)

private fun nextInlineToken(text: String, from: Int): InlineToken? {
    val candidates = mutableListOf<InlineToken>()
    fun paired(marker: String, kind: InlineKind) {
        val start = text.indexOf(marker, from)
        if (start < 0) return
        val endMarker = text.indexOf(marker, start + marker.length)
        if (endMarker <= start + marker.length) return
        candidates +=
            InlineToken(
                start = start,
                end = endMarker + marker.length,
                content = text.substring(start + marker.length, endMarker),
                kind = kind,
            )
    }
    paired("**", InlineKind.BOLD)
    paired("~~", InlineKind.STRIKE)
    paired("`", InlineKind.CODE)
    paired("*", InlineKind.ITALIC)

    val link = Regex("\\[([^]]+)]\\(([^)\\s]+)\\)").find(text, from)
    if (link != null) {
        candidates +=
            InlineToken(
                start = link.range.first,
                end = link.range.last + 1,
                content = link.groupValues[1],
                kind = InlineKind.LINK,
                url = link.groupValues[2],
            )
    }
    return candidates.minByOrNull(InlineToken::start)
}
