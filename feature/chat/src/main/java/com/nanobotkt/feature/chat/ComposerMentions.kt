package com.nanobotkt.feature.chat

import com.nanobotkt.core.model.CliAppInfo
import com.nanobotkt.core.model.McpPresetInfo
import com.nanobotkt.core.model.SkillSummary
import com.nanobotkt.core.model.UiCliAppAttachment
import com.nanobotkt.core.model.UiMcpPresetAttachment

private const val MAX_MENTION_CANDIDATES = 8

data class CapabilityMentionQuery(
    val query: String,
    val start: Int,
    val end: Int,
)

sealed interface CapabilityMentionCandidate {
    val name: String

    data class Cli(
        override val name: String,
        val app: CliAppInfo,
    ) : CapabilityMentionCandidate

    data class Mcp(
        override val name: String,
        val preset: McpPresetInfo,
    ) : CapabilityMentionCandidate
}

data class CapabilityMentionPayloads(
    val cliApps: List<UiCliAppAttachment> = emptyList(),
    val mcpPresets: List<UiMcpPresetAttachment> = emptyList(),
)

data class SkillMentionQuery(
    val query: String,
    val start: Int,
    val end: Int,
)

data class SkillMentionCandidate(
    val command: String,
    val recent: Boolean,
    val skill: SkillSummary,
)

data class MentionInsertion(
    val value: String,
    val cursor: Int,
)

private val capabilityQueryRegex = Regex("(?:^|\\s)@([a-z0-9_-]*)$", RegexOption.IGNORE_CASE)
private val activeCapabilityRegex = Regex("(^|[\\s(\\[{])@([a-z0-9_-]+)\\b", RegexOption.IGNORE_CASE)
private val skillQueryRegex = Regex("\\$([a-z0-9_-]*)$", RegexOption.IGNORE_CASE)

fun capabilityMentionQuery(value: String, cursorPosition: Int): CapabilityMentionQuery? {
    val caret = cursorPosition.coerceIn(0, value.length)
    val match = capabilityQueryRegex.find(value.substring(0, caret)) ?: return null
    val query = match.groupValues[1].lowercase()
    return CapabilityMentionQuery(
        query = query,
        start = caret - query.length - 1,
        end = caret,
    )
}

fun capabilityMentionCandidates(
    query: CapabilityMentionQuery?,
    cliApps: List<CliAppInfo>,
    mcpPresets: List<McpPresetInfo>,
): List<CapabilityMentionCandidate> {
    if (query == null) return emptyList()
    val cliCandidates = cliApps.asSequence()
        .filter(CliAppInfo::installed)
        .filter { app ->
            listOf(app.name, app.displayName, app.category, app.description, app.entryPoint)
                .joinToString(" ")
                .contains(query.query, ignoreCase = true)
        }
        .map { CapabilityMentionCandidate.Cli(it.name, it) }
    val mcpCandidates = mcpPresets.asSequence()
        .filter { it.installed && it.configured }
        .filter { preset ->
            listOf(preset.name, preset.displayName, preset.category, preset.description, preset.transport)
                .joinToString(" ")
                .contains(query.query, ignoreCase = true)
        }
        .map { CapabilityMentionCandidate.Mcp(it.name, it) }
    return (cliCandidates + mcpCandidates).take(MAX_MENTION_CANDIDATES).toList()
}

fun insertCapabilityMention(
    value: String,
    query: CapabilityMentionQuery,
    candidate: CapabilityMentionCandidate,
): MentionInsertion {
    val suffix = value.substring(query.end)
    val mention = "@${candidate.name}${if (suffix.startsWith(' ')) "" else " "}"
    return MentionInsertion(
        value = value.substring(0, query.start) + mention + suffix,
        cursor = query.start + mention.length,
    )
}

fun activeCapabilityMentionPayloads(
    value: String,
    cliApps: List<CliAppInfo>,
    mcpPresets: List<McpPresetInfo>,
): CapabilityMentionPayloads {
    val cliByName = cliApps
        .filter(CliAppInfo::installed)
        .associateBy { it.name.lowercase() }
    val mcpByName = mcpPresets
        .filter { it.installed && it.configured }
        .associateBy { it.name.lowercase() }
    val cliMentions = mutableListOf<UiCliAppAttachment>()
    val mcpMentions = mutableListOf<UiMcpPresetAttachment>()
    val seenCli = mutableSetOf<String>()
    val seenMcp = mutableSetOf<String>()

    activeCapabilityRegex.findAll(value).forEach { match ->
        val key = match.groupValues[2].lowercase()
        val app = cliByName[key]
        if (app != null && seenCli.add(key)) {
            cliMentions += app.toAttachment()
            return@forEach
        }
        val preset = mcpByName[key]
        if (preset != null && seenMcp.add(key)) {
            mcpMentions += preset.toAttachment()
        }
    }
    return CapabilityMentionPayloads(cliMentions, mcpMentions)
}

fun skillMentionQuery(value: String, cursorPosition: Int): SkillMentionQuery? {
    val caret = cursorPosition.coerceIn(0, value.length)
    val match = skillQueryRegex.find(value.substring(0, caret)) ?: return null
    return SkillMentionQuery(
        query = match.groupValues[1].lowercase(),
        start = match.range.first,
        end = caret,
    )
}

fun skillMentionCandidates(
    query: SkillMentionQuery?,
    skills: List<SkillSummary>,
    recentCommands: List<String>,
): List<SkillMentionCandidate> {
    if (query == null) return emptyList()
    return skills.asSequence()
        .filter(SkillSummary::available)
        .mapIndexedNotNull { sourceIndex, skill ->
            val rank = skillMatchRank(skill, query.query) ?: return@mapIndexedNotNull null
            RankedSkillMention(
                command = "\$${skill.name}",
                matchRank = rank,
                sourceIndex = sourceIndex,
                skill = skill,
            )
        }
        .sortedWith { left, right ->
            val rankComparison = left.matchRank.compareTo(right.matchRank)
            if (rankComparison != 0) {
                rankComparison
            } else if (query.query.isNotEmpty()) {
                left.sourceIndex.compareTo(right.sourceIndex)
            } else {
                compareRecent(left, right, recentCommands)
            }
        }
        .take(MAX_MENTION_CANDIDATES)
        .map { ranked ->
            SkillMentionCandidate(
                command = ranked.command,
                recent = ranked.command in recentCommands,
                skill = ranked.skill,
            )
        }
        .toList()
}

fun insertSkillMention(
    value: String,
    query: SkillMentionQuery,
    candidate: SkillMentionCandidate,
): MentionInsertion {
    val suffix = value.substring(query.end)
    val inserted = candidate.command + if (suffix.startsWith(' ')) "" else " "
    return MentionInsertion(
        value = value.substring(0, query.start) + inserted + suffix,
        cursor = query.start + inserted.length,
    )
}

private data class RankedSkillMention(
    val command: String,
    val matchRank: Int,
    val sourceIndex: Int,
    val skill: SkillSummary,
)

private fun skillMatchRank(skill: SkillSummary, query: String): Int? {
    if (query.isEmpty()) return 0
    val name = skill.name.lowercase()
    return when {
        name == query -> 0
        name.startsWith(query) -> 1
        query in name -> 2
        query in skill.description.lowercase() -> 3
        else -> null
    }
}

private fun compareRecent(
    left: RankedSkillMention,
    right: RankedSkillMention,
    recentCommands: List<String>,
): Int {
    val leftRecent = recentCommands.indexOf(left.command)
    val rightRecent = recentCommands.indexOf(right.command)
    return when {
        leftRecent == -1 && rightRecent == -1 -> left.sourceIndex.compareTo(right.sourceIndex)
        leftRecent == -1 -> 1
        rightRecent == -1 -> -1
        else -> leftRecent.compareTo(rightRecent)
    }
}

private fun CliAppInfo.toAttachment() = UiCliAppAttachment(
    name = name,
    displayName = displayName,
    category = category,
    entryPoint = entryPoint,
    logoUrl = logoUrl,
    brandColor = brandColor,
)

private fun McpPresetInfo.toAttachment() = UiMcpPresetAttachment(
    name = name,
    displayName = displayName,
    category = category,
    transport = transport,
    status = status,
    configured = configured,
    logoUrl = logoUrl,
    brandColor = brandColor,
)

