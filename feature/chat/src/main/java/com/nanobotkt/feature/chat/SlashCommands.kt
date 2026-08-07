package com.nanobotkt.feature.chat

import com.nanobotkt.core.model.SlashCommand

internal enum class ResolvedSlashCommandLifecycle {
    SIDE_CHANNEL,
    FINALIZE_ACTIVE_TURN,
    STOP_ACTIVE_TURN,
    AGENT_TURN,
}

internal fun SlashCommand.hasSupportedLifecycle(): Boolean = lifecycle in setOf(
    "side_channel",
    "finalize_active_turn",
    "stop_active_turn",
    "agent_turn",
    "agent_turn_with_args",
)

internal fun matchingSlashCommand(
    content: String,
    commands: List<SlashCommand>,
): SlashCommand? {
    val trimmed = content.trimStart()
    val name = trimmed.substringBeforeWhitespace()
    if (!name.startsWith('/')) return null
    val match = commands.firstOrNull { it.command == name } ?: return null
    val args = trimmed.drop(name.length).trim()
    if (args.isNotEmpty() && !match.acceptsArgs) return null
    return match
}

internal fun slashCommandLifecycle(
    content: String,
    commands: List<SlashCommand>,
): ResolvedSlashCommandLifecycle? {
    val match = matchingSlashCommand(content, commands) ?: return null
    return when (match.lifecycle) {
        "side_channel" -> ResolvedSlashCommandLifecycle.SIDE_CHANNEL
        "finalize_active_turn" -> ResolvedSlashCommandLifecycle.FINALIZE_ACTIVE_TURN
        "stop_active_turn" -> ResolvedSlashCommandLifecycle.STOP_ACTIVE_TURN
        "agent_turn" -> ResolvedSlashCommandLifecycle.AGENT_TURN
        "agent_turn_with_args" -> {
            val args = content.trimStart().drop(match.command.length).trim()
            if (args.isEmpty()) ResolvedSlashCommandLifecycle.SIDE_CHANNEL
            else ResolvedSlashCommandLifecycle.AGENT_TURN
        }
        else -> null
    }
}

internal fun ResolvedSlashCommandLifecycle?.isSideChannel(): Boolean = when (this) {
    ResolvedSlashCommandLifecycle.SIDE_CHANNEL,
    ResolvedSlashCommandLifecycle.FINALIZE_ACTIVE_TURN,
    ResolvedSlashCommandLifecycle.STOP_ACTIVE_TURN,
    -> true
    else -> false
}

internal fun slashQuery(content: String): String? {
    if (!content.startsWith('/')) return null
    val token = content.drop(1)
    if (token.any(Char::isWhitespace)) return null
    return token.lowercase()
}

internal fun visibleSlashCommands(
    content: String,
    commands: List<SlashCommand>,
    turnActive: Boolean,
): List<SlashCommand> {
    val query = slashQuery(content) ?: return emptyList()
    val filtered = commands.filter { command ->
        if (!command.hasSupportedLifecycle()) return@filter false
        when {
            query.isEmpty() && command.command == "/restart" -> false
            query.isEmpty() && !turnActive && command.command == "/stop" -> false
            query.isEmpty() -> true
            else -> listOf(command.command, command.title, command.description, command.argHint)
                .joinToString(" ")
                .contains(query, ignoreCase = true)
        }
    }
    val ordered = if (turnActive) {
        filtered.sortedByDescending { it.command == "/stop" }
    } else {
        filtered
    }
    return ordered.take(8)
}

private fun String.substringBeforeWhitespace(): String {
    val index = indexOfFirst(Char::isWhitespace)
    return if (index < 0) this else substring(0, index)
}