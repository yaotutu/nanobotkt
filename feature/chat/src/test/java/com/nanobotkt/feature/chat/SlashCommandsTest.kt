package com.nanobotkt.feature.chat

import com.nanobotkt.core.model.SlashCommand
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class SlashCommandsTest {
    @Test
    fun `matching command requires an exact command name`() {
        val commands = listOf(command("/status", "side_channel"))

        assertEquals(commands.single(), matchingSlashCommand("  /status  ", commands))
        assertNull(matchingSlashCommand("/status-more", commands))
    }

    @Test
    fun `matching command rejects arguments when unsupported`() {
        val commands = listOf(command("/status", "side_channel", acceptsArgs = false))

        assertNull(matchingSlashCommand("/status now", commands))
    }

    @Test
    fun `agent turn with args is side channel without args`() {
        val commands = listOf(command("/model", "agent_turn_with_args", acceptsArgs = true))

        assertEquals(
            ResolvedSlashCommandLifecycle.SIDE_CHANNEL,
            slashCommandLifecycle("/model", commands),
        )
    }

    @Test
    fun `agent turn with args starts agent turn when args are present`() {
        val commands = listOf(command("/model", "agent_turn_with_args", acceptsArgs = true))

        assertEquals(
            ResolvedSlashCommandLifecycle.AGENT_TURN,
            slashCommandLifecycle("/model fast", commands),
        )
    }

    @Test
    fun `slash query rejects whitespace in current token`() {
        assertEquals("mod", slashQuery("/MoD"))
        assertNull(slashQuery(" /mod"))
        assertNull(slashQuery("/model fast"))
    }

    @Test
    fun `empty palette hides restart and inactive stop`() {
        val commands = listOf(
            command("/restart", "side_channel"),
            command("/stop", "stop_active_turn"),
            command("/status", "side_channel"),
        )

        assertEquals(
            listOf("/status"),
            visibleSlashCommands("/", commands, turnActive = false).map(SlashCommand::command),
        )
    }

    @Test
    fun `active palette places stop first`() {
        val commands = listOf(
            command("/status", "side_channel"),
            command("/help", "side_channel"),
            command("/stop", "stop_active_turn"),
        )

        assertEquals(
            listOf("/stop", "/status", "/help"),
            visibleSlashCommands("/", commands, turnActive = true).map(SlashCommand::command),
        )
    }

    @Test
    fun `palette searches command metadata`() {
        val commands = listOf(
            command("/status", "side_channel", title = "Runtime state"),
            command("/model", "agent_turn", description = "Choose provider"),
            command("/web", "agent_turn", argHint = "query terms", acceptsArgs = true),
        )

        assertEquals(listOf("/status"), visibleSlashCommands("/runtime", commands, false).map(SlashCommand::command))
        assertEquals(listOf("/model"), visibleSlashCommands("/provider", commands, false).map(SlashCommand::command))
        assertEquals(listOf("/web"), visibleSlashCommands("/terms", commands, false).map(SlashCommand::command))
    }

    @Test
    fun `palette caps results at eight and removes unsupported lifecycle`() {
        val commands = buildList {
            add(command("/invalid", "unknown"))
            repeat(10) { index -> add(command("/command$index", "side_channel")) }
        }

        val visible = visibleSlashCommands("/command", commands, turnActive = false)

        assertEquals(8, visible.size)
        assertEquals((0..7).map { "/command$it" }, visible.map(SlashCommand::command))
    }

    private fun command(
        command: String,
        lifecycle: String,
        title: String = command,
        description: String = "$command description",
        argHint: String = "",
        acceptsArgs: Boolean = false,
    ) = SlashCommand(
        command = command,
        title = title,
        description = description,
        icon = "terminal",
        argHint = argHint,
        lifecycle = lifecycle,
        acceptsArgs = acceptsArgs,
    )
}
