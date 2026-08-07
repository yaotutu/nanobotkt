package com.nanobotkt.feature.chat

import com.nanobotkt.core.model.CliAppInfo
import com.nanobotkt.core.model.McpPresetInfo
import com.nanobotkt.core.model.SkillSummary
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class ComposerMentionsTest {
    @Test
    fun `capability query requires start or whitespace and follows cursor`() {
        assertEquals(CapabilityMentionQuery("tool", 6, 11), capabilityMentionQuery("hello @tool later", 11))
        assertNull(capabilityMentionQuery("hello@tool", 10))
        assertEquals(CapabilityMentionQuery("", 6, 7), capabilityMentionQuery("hello @", 99))
    }

    @Test
    fun `capability candidates filter availability search metadata and keep cli first`() {
        val query = CapabilityMentionQuery("search", 0, 7)
        val candidates = capabilityMentionCandidates(
            query = query,
            cliApps = listOf(
                cli("first", installed = true, description = "Search files"),
                cli("missing", installed = false, description = "Search files"),
            ),
            mcpPresets = listOf(
                mcp("second", installed = true, configured = true, transport = "search-stream"),
                mcp("not-configured", installed = true, configured = false, description = "Search"),
            ),
        )

        assertEquals(listOf("first", "second"), candidates.map(CapabilityMentionCandidate::name))
        assertTrue(candidates[0] is CapabilityMentionCandidate.Cli)
        assertTrue(candidates[1] is CapabilityMentionCandidate.Mcp)
    }

    @Test
    fun `capability candidates are capped at eight`() {
        val candidates = capabilityMentionCandidates(
            CapabilityMentionQuery("", 0, 1),
            cliApps = (1..9).map { cli("app-$it") },
            mcpPresets = listOf(mcp("preset")),
        )

        assertEquals(8, candidates.size)
        assertTrue(candidates.all { it is CapabilityMentionCandidate.Cli })
    }

    @Test
    fun `capability insertion replaces at cursor without duplicate space`() {
        val candidate = CapabilityMentionCandidate.Cli("rg", cli("rg"))
        assertEquals(
            MentionInsertion("use @rg now", 7),
            insertCapabilityMention("use @r now", CapabilityMentionQuery("r", 4, 6), candidate),
        )
        assertEquals(
            MentionInsertion("use @rg later", 7),
            insertCapabilityMention("use @r later", CapabilityMentionQuery("r", 4, 6), candidate),
        )
    }

    @Test
    fun `active payloads match boundaries case insensitively and deduplicate per capability type`() {
        val payloads = activeCapabilityMentionPayloads(
            value = "@Shared and (@MCP) plus x@ignored and @shared",
            cliApps = listOf(cli("shared", displayName = "Shared CLI")),
            mcpPresets = listOf(
                mcp("shared", displayName = "Shared MCP"),
                mcp("mcp", displayName = "MCP preset"),
                mcp("ignored", installed = false),
            ),
        )

        assertEquals(listOf("shared"), payloads.cliApps.map { it.name })
        assertEquals("Shared CLI", payloads.cliApps.single().displayName)
        assertEquals(listOf("mcp", "shared"), payloads.mcpPresets.map { it.name })
        assertTrue(payloads.mcpPresets.all { it.configured == true })
    }

    @Test
    fun `skill query follows cursor and supports compact tokens`() {
        assertEquals(SkillMentionQuery("rev", 4, 8), skillMentionQuery("use \$rev later", 8))
        assertEquals(SkillMentionQuery("skill", 3, 9), skillMentionQuery("abc\$skill", 99))
        assertNull(skillMentionQuery("no mention", 10))
    }

    @Test
    fun `skill candidates rank exact prefix name and description matches`() {
        val candidates = skillMentionCandidates(
            query = SkillMentionQuery("git", 0, 4),
            skills = listOf(
                skill("tool-git", "contains name"),
                skill("git", "exact"),
                skill("github", "prefix"),
                skill("review", "Git workflows"),
                skill("unavailable-git", "ignored", available = false),
            ),
            recentCommands = emptyList(),
        )

        assertEquals(listOf("\$git", "\$github", "\$tool-git", "\$review"), candidates.map { it.command })
    }

    @Test
    fun `empty skill query uses recent order then source order and caps at eight`() {
        val candidates = skillMentionCandidates(
            query = SkillMentionQuery("", 0, 1),
            skills = (1..10).map { skill("skill-$it", "") },
            recentCommands = listOf("\$skill-4", "\$skill-2"),
        )

        assertEquals(listOf("\$skill-4", "\$skill-2"), candidates.take(2).map { it.command })
        assertTrue(candidates.take(2).all { it.recent })
        assertEquals(8, candidates.size)
    }

    @Test
    fun `skill insertion respects cursor and existing suffix space`() {
        val candidate = SkillMentionCandidate("\$review", false, skill("review", ""))
        assertEquals(
            MentionInsertion("run \$review now", 11),
            insertSkillMention("run \$re now", SkillMentionQuery("re", 4, 7), candidate),
        )
    }

    private fun cli(
        name: String,
        installed: Boolean = true,
        displayName: String = name,
        description: String = "",
    ) = CliAppInfo(
        name = name,
        displayName = displayName,
        category = "developer",
        description = description,
        entryPoint = "bin/$name",
        installed = installed,
    )

    private fun mcp(
        name: String,
        installed: Boolean = true,
        configured: Boolean = true,
        displayName: String = name,
        description: String = "",
        transport: String = "stdio",
    ) = McpPresetInfo(
        name = name,
        displayName = displayName,
        category = "developer",
        description = description,
        transport = transport,
        installed = installed,
        configured = configured,
        status = "ready",
    )

    private fun skill(
        name: String,
        description: String,
        available: Boolean = true,
    ) = SkillSummary(
        name = name,
        description = description,
        source = "workspace",
        available = available,
    )
}


