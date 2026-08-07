package com.nanobotkt.feature.chat

internal const val MAX_QUOTED_CONTEXT_CHARS = 4_000
private const val QUOTED_CONTEXT_MARKER = "> [!QUOTE]"

internal data class ParsedUserMessageQuote(
    val quotedContext: String?,
    val content: String,
)

internal fun normalizeQuotedContext(value: String?): String = value.orEmpty()
    .replace("\r\n", "\n")
    .replace('\r', '\n')
    .trim()
    .take(MAX_QUOTED_CONTEXT_CHARS)

internal fun formatQuotedUserMessage(content: String, quotedContext: String?): String {
    val body = content.trim()
    val quote = normalizeQuotedContext(quotedContext)
    if (quote.isEmpty() || body.startsWith('/')) return body

    val blockquote = quote.lineSequence().joinToString("\n") { line ->
        if (line.isEmpty()) ">" else "> $line"
    }
    val quotedMessage = "$QUOTED_CONTEXT_MARKER\n$blockquote"
    return if (body.isEmpty()) quotedMessage else "$quotedMessage\n\n$body"
}

internal fun parseQuotedUserMessage(content: String): ParsedUserMessageQuote {
    val normalized = content.replace("\r\n", "\n").replace('\r', '\n')
    val prefix = "$QUOTED_CONTEXT_MARKER\n"
    if (!normalized.startsWith(prefix)) return ParsedUserMessageQuote(null, content)

    val quoteStart = prefix.length
    val separatorIndex = normalized.indexOf("\n\n", startIndex = quoteStart)
    val quoteBlock = if (separatorIndex < 0) normalized.substring(quoteStart)
    else normalized.substring(quoteStart, separatorIndex)
    val quoteLines = quoteBlock.split('\n')
    if (quoteLines.isEmpty() || quoteLines.any { it != ">" && !it.startsWith("> ") }) {
        return ParsedUserMessageQuote(null, content)
    }
    val quote = quoteLines.joinToString("\n") { if (it == ">") "" else it.drop(2) }.trim()
    if (quote.isEmpty()) return ParsedUserMessageQuote(null, content)
    return ParsedUserMessageQuote(
        quotedContext = quote,
        content = if (separatorIndex < 0) "" else normalized.substring(separatorIndex + 2),
    )
}