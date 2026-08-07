package com.nanobotkt.core.model

import kotlinx.serialization.json.Json
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class ChatWireContractTest {
    private val json = Json {
        ignoreUnknownKeys = true
        explicitNulls = false
        encodeDefaults = false
        classDiscriminator = "event"
    }

    @Test
    fun `delta event decodes discriminator and snake case ids`() {
        val event = json.decodeFromString<InboundEvent>(
            """{
              "event": "delta",
              "chat_id": "chat-1",
              "text": "hello",
              "stream_id": "stream-1",
              "turn_id": "turn-1",
              "turn_phase": "answer",
              "turn_seq": 7,
              "future": true
            }""",
        )
        assertTrue(event is InboundEvent.Delta)
        val delta = event as InboundEvent.Delta
        assertEquals("chat-1", delta.chatId)
        assertEquals("stream-1", delta.streamId)
        assertEquals("turn-1", delta.turnId)
        assertEquals(7, delta.turnSeq)
    }

    @Test
    fun `thread payload keeps pagination fork and workspace fields`() {
        val payload = json.decodeFromString<WebUiThreadPayload>(
            """{
              "schemaVersion": 3,
              "sessionKey": "webui:chat-1",
              "messages": [{
                "id": "m-1",
                "role": "assistant",
                "content": "done",
                "createdAt": 100,
                "turnId": "turn-1",
                "images": [{"url": "https://example.invalid/image.png"}]
              }],
              "fork_boundary_message_count": 4,
              "completed_turn_ids": ["turn-1"],
              "active_turn_id": null,
              "page": {
                "before_cursor": "cursor-1",
                "has_more_before": true,
                "loaded_message_count": 1
              },
              "workspace_scope": {
                "project_path": "/workspace",
                "project_name": "Workspace",
                "access_mode": "restricted"
              }
            }""",
        )
        assertEquals(3, payload.schemaVersion)
        assertEquals(4, payload.forkBoundaryMessageCount)
        assertEquals(listOf("turn-1"), payload.completedTurnIds)
        assertNull(payload.activeTurnId)
        assertTrue(payload.page?.hasMoreBefore == true)
        assertEquals("/workspace", payload.workspaceScope?.projectPath)
    }

    @Test
    fun `file edit event preserves diff metadata`() {
        val event = json.decodeFromString<InboundEvent>(
            """{
              "event": "file_edit",
              "chat_id": "chat-1",
              "turn_id": "turn-1",
              "edits": [{
                "call_id": "call-1",
                "tool": "edit_file",
                "path": "src/main.kt",
                "absolute_path": "/workspace/src/main.kt",
                "phase": "end",
                "added": 3,
                "deleted": 1,
                "status": "done",
                "diff": {"format": "unified", "context": 3, "truncated": false, "text": "@@"}
              }]
            }""",
        )
        assertTrue(event is InboundEvent.FileEdit)
        val edit = (event as InboundEvent.FileEdit).edits.single()
        assertEquals("/workspace/src/main.kt", edit.absolutePath)
        assertEquals(3, edit.added)
        assertEquals("unified", edit.diff?.format)
    }
}
