package com.nanobotkt.feature.chat

import android.net.TestUri
import android.net.Uri
import com.nanobotkt.core.model.CliAppInfo
import com.nanobotkt.core.model.McpPresetInfo
import com.nanobotkt.core.model.OutboundMedia
import com.nanobotkt.core.model.SkillSummary
import com.nanobotkt.core.model.SlashCommand
import com.nanobotkt.core.model.WorkspaceAccessMode
import com.nanobotkt.core.model.WorkspaceScope
import com.nanobotkt.core.persistence.ComposerRecentsStore
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.TestDispatcher
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TestWatcher
import org.junit.runner.Description

@OptIn(ExperimentalCoroutinesApi::class)
class ChatViewModelTest {
    @get:Rule
    val mainDispatcherRule = MainDispatcherRule()

    private val repository = FakeChatRepository()
    private val attachmentEncoding = object : AttachmentEncoding {
        override suspend fun encode(uri: Uri, maxFileBytes: Long): ComposerAttachment =
            error("not used")
    }
    private val voiceRecorder = FakeVoiceRecorder()
    private val composerRecentsStore = FakeComposerRecentsStore()

    @Test
    fun `opening a different session resets composer and active recording`() {
        val viewModel = viewModel()
        viewModel.open("websocket:a", "a")
        viewModel.updateText("draft")
        viewModel.setQuotedContext("quoted")
        viewModel.startVoiceRecording(permissionGranted = true)
        val cancelCountBeforeSwitch = voiceRecorder.cancelCount

        viewModel.open("websocket:b", "b")

        assertEquals(ComposerUiState(), viewModel.composer.value)
        assertTrue(voiceRecorder.cancelCount > cancelCountBeforeSwitch)
        assertEquals("websocket:b", repository.state.value.sessionKey)
    }

    @Test
    fun `opening the same session preserves composer draft`() {
        val viewModel = viewModel()
        viewModel.open("websocket:a", "a")
        viewModel.updateText("draft")

        viewModel.open("websocket:a", "a")

        assertEquals("draft", viewModel.composer.value.text)
    }

    @Test
    fun `late attachment result from previous session cannot mutate the new composer`() = runTest {
        val encoder = DeferredAttachmentEncoder()
        val viewModel = viewModel(encoder)
        val oldUri = TestUri("test://attachments/old.txt")
        val oldAttachment = composerAttachment(oldUri, "old.txt")

        viewModel.open("websocket:a", "a")
        viewModel.addAttachments(listOf(oldUri))
        // 让 addAttachments 真正进入 encode 并挂起，确保 requestEpoch 已经属于旧会话。
        runCurrent()
        assertEquals(1, viewModel.composer.value.encodingCount)

        viewModel.open("websocket:b", "b")
        assertEquals(ComposerUiState(), viewModel.composer.value)

        // 旧会话编码完成后，结果必须被 epoch guard 丢弃，不能写入 B 的 composer。
        encoder.pending.single().completion.complete(oldAttachment)
        runCurrent()

        assertEquals(ComposerUiState(), viewModel.composer.value)
    }

    @Test
    fun `late attachment failure from start new topic cannot mutate the new composer`() = runTest {
        val encoder = DeferredAttachmentEncoder()
        val viewModel = viewModel(encoder)
        val oldUri = TestUri("test://attachments/old.txt")

        viewModel.open("websocket:a", "a")
        viewModel.addAttachments(listOf(oldUri))
        // 先挂起旧 topic 的编码，再切换到新 topic，覆盖成功和失败两条 stale-result 路径。
        runCurrent()
        assertEquals(1, viewModel.composer.value.encodingCount)

        viewModel.startNewTopic()
        assertEquals(ComposerUiState(), viewModel.composer.value)

        encoder.pending.single().completion.completeExceptionally(IllegalStateException("old_failure"))
        runCurrent()

        // 旧错误、旧 encodingCount 都不能污染新 topic 的空 composer。
        assertEquals(ComposerUiState(), viewModel.composer.value)
    }

    @Test
    fun `concurrent attachment batches keep encoding count until every encoder finishes`() = runTest {
        val encoder = DeferredAttachmentEncoder()
        val viewModel = viewModel(encoder)
        val firstUri = TestUri("test://attachments/first.txt")
        val secondUri = TestUri("test://attachments/second.txt")
        val firstAttachment = composerAttachment(firstUri, "first.txt")
        val secondAttachment = composerAttachment(secondUri, "second.txt")

        viewModel.open("websocket:a", "a")
        viewModel.addAttachments(listOf(firstUri))
        viewModel.addAttachments(listOf(secondUri))
        // 两批请求都应停在可控 encoder 上，此时两个编码任务都仍未完成。
        runCurrent()

        assertEquals(2, encoder.pending.size)
        assertEquals(2, viewModel.composer.value.encodingCount)

        encoder.pending[0].completion.complete(firstAttachment)
        runCurrent()
        // 第一批完成后，第二批仍在编码，send 不能因 count 提前归零而放行。
        assertEquals(1, viewModel.composer.value.encodingCount)
        assertEquals(listOf(firstAttachment), viewModel.composer.value.attachments)

        encoder.pending[1].completion.complete(secondAttachment)
        runCurrent()

        assertEquals(0, viewModel.composer.value.encodingCount)
        assertEquals(listOf(firstAttachment, secondAttachment), viewModel.composer.value.attachments)
    }

    @Test
    fun `session open forwards sidebar workspace scope`() {
        val scope = workspaceScope("/srv/project", WorkspaceAccessMode.RESTRICTED)
        val viewModel = viewModel()

        viewModel.open("websocket:a", "a", scope)

        assertEquals(OpenedSession("websocket:a", "a", scope), repository.openedSessions.single())
        assertEquals(scope, repository.state.value.workspaceScope)
    }

    @Test
    fun `session open forwards sidebar model preset`() {
        val viewModel = viewModel()

        viewModel.open("websocket:a", "a", modelPreset = "fast")

        assertEquals(
            OpenedSession("websocket:a", "a", workspaceScope = null, modelPreset = "fast"),
            repository.openedSessions.single(),
        )
    }

    @Test
    fun `model preset change is forwarded`() = runTest {
        val viewModel = viewModel()

        viewModel.changeModelPreset("fast")
        advanceUntilIdle()

        assertEquals(listOf("fast"), repository.modelPresetChanges)
    }

    @Test
    fun `draft workspace change is forwarded and explicit new chat uses it`() = runTest {
        val scope = workspaceScope("C:\\dev\\project", WorkspaceAccessMode.FULL)
        val viewModel = viewModel()
        viewModel.startNewTopic()

        viewModel.setWorkspaceScope(scope)
        viewModel.newChat()
        advanceUntilIdle()

        assertEquals(listOf(scope), repository.workspaceScopeChanges)
        assertEquals(listOf(scope), repository.newChatScopes)
        assertEquals(1, repository.startNewTopicCount)
    }

    @Test
    fun `send snapshots current workspace scope`() = runTest {
        val scope = workspaceScope("/srv/project", WorkspaceAccessMode.RESTRICTED)
        val viewModel = viewModel()
        viewModel.open("websocket:a", "a", scope)
        viewModel.updateText("question")

        viewModel.send()
        advanceUntilIdle()

        assertEquals(scope, repository.sentPrompts.single().options.workspaceScope)
    }

    @Test
    fun `queued prompt retains enqueue time workspace scope`() = runTest {
        val queuedScope = workspaceScope("/srv/queued", WorkspaceAccessMode.RESTRICTED)
        val laterScope = workspaceScope("/srv/later", WorkspaceAccessMode.FULL)
        val viewModel = viewModel()
        viewModel.open("websocket:a", "a", queuedScope)
        repository.setActiveTurn("turn-1")
        runCurrent()
        viewModel.updateText("queued question")
        viewModel.send()

        repository.setWorkspaceScopeState(laterScope)
        repository.setActiveTurn(null)
        advanceUntilIdle()

        assertEquals(queuedScope, repository.sentPrompts.single().options.workspaceScope)
    }

    @Test
    fun `workspace change is ignored while a turn is active`() {
        val initial = workspaceScope("/srv/initial", WorkspaceAccessMode.RESTRICTED)
        val replacement = workspaceScope("/srv/replacement", WorkspaceAccessMode.FULL)
        val viewModel = viewModel()
        viewModel.open("websocket:a", "a", initial)
        repository.setActiveTurn("turn-1")

        viewModel.setWorkspaceScope(replacement)

        assertTrue(repository.workspaceScopeChanges.isEmpty())
        assertEquals(initial, repository.state.value.workspaceScope)
    }

    @Test
    fun `composer recents hydrate and survive session resets`() = runTest {
        composerRecentsStore.persisted = listOf("/help", "\$review")
        val viewModel = viewModel()

        advanceUntilIdle()
        viewModel.open("websocket:a", "a")
        viewModel.updateText("draft")
        viewModel.open("websocket:b", "b")

        assertEquals(listOf("/help", "\$review"), viewModel.composer.value.recentCommands)
    }

    @Test
    fun `recording recents persists newest unique commands with a five item limit`() = runTest {
        val viewModel = viewModel()
        advanceUntilIdle()

        (1..6).forEach { index ->
            viewModel.selectSlashCommand(slashCommand("/command-$index", lifecycle = "message"))
        }
        viewModel.selectSlashCommand(slashCommand("/command-4", lifecycle = "message"))
        advanceUntilIdle()

        val expected = listOf("/command-4", "/command-6", "/command-5", "/command-3", "/command-2")
        assertEquals(expected, viewModel.composer.value.recentCommands)
        assertEquals(expected, composerRecentsStore.saved.single())
    }
    @Test
    fun `permission denial maps to stable voice error`() {
        val viewModel = viewModel()

        viewModel.startVoiceRecording(permissionGranted = false)

        assertEquals(VoiceRecorderError.PERMISSION, viewModel.composer.value.voice.error)
        assertFalse(viewModel.composer.value.voice.isRecording)
    }

    @Test
    fun `send forwards quote and clears composer after socket acceptance`() = runTest {
        val viewModel = viewModel()
        viewModel.open("websocket:a", "a")
        viewModel.updateText("question")
        viewModel.setQuotedContext("answer")

        viewModel.send()
        assertTrue(viewModel.composer.value.sending)
        advanceUntilIdle()

        assertEquals("question", repository.lastSentText)
        assertEquals("answer", repository.lastQuotedContext)
        assertEquals(ComposerUiState(), viewModel.composer.value)
    }

    @Test
    fun `active turn queues prompt and clears its draft context`() = runTest {
        val viewModel = viewModel()
        viewModel.open("websocket:a", "a")
        runCurrent()
        repository.setActiveTurn("turn-1")
        runCurrent()
        viewModel.updateText("queued question")
        viewModel.setQuotedContext("quoted answer")

        viewModel.send()

        assertTrue(repository.sentPrompts.isEmpty())
        assertEquals("", viewModel.composer.value.text)
        assertNull(viewModel.composer.value.quotedContext)
        assertEquals(1, viewModel.composer.value.queuedPrompts.size)
        assertEquals("queued question", viewModel.composer.value.queuedPrompts.single().text)
        assertEquals("quoted answer", viewModel.composer.value.queuedPrompts.single().quotedContext)
    }

    @Test
    fun `turn end flushes only the first queued prompt`() = runTest {
        val viewModel = viewModel()
        viewModel.open("websocket:a", "a")
        runCurrent()
        repository.setActiveTurn("turn-1")
        runCurrent()
        viewModel.updateText("first")
        viewModel.send()
        viewModel.updateText("second")
        viewModel.send()

        repository.setActiveTurn(null)
        advanceUntilIdle()

        assertEquals(listOf("first"), repository.sentPrompts.map(SentPrompt::text))
        assertFalse(repository.sentPrompts.single().options.retainFailureInTimeline)
        assertEquals(listOf("second"), viewModel.composer.value.queuedPrompts.map(QueuedPrompt::text))
        assertFalse(viewModel.composer.value.sending)
    }

    @Test
    fun `fast queued turn end during acceptance continues flushing remaining prompts`() = runTest {
        val firstAcceptance = CompletableDeferred<Unit>()
        repository.sendBlock = { text, _, _ ->
            if (text == "first") firstAcceptance.await()
        }
        val viewModel = viewModel()
        viewModel.open("websocket:a", "a")
        runCurrent()
        repository.setActiveTurn("turn-1")
        runCurrent()
        viewModel.updateText("first")
        viewModel.send()
        viewModel.updateText("second")
        viewModel.send()

        // 原 turn 结束后开始 flush 第一条；仓储 acceptance 仍挂起，因此 Composer 保持
        // sending=true，同时第二条仍在 Queue 中。
        repository.setActiveTurn(null)
        runCurrent()
        assertTrue(viewModel.composer.value.sending)
        assertEquals(listOf("second"), viewModel.composer.value.queuedPrompts.map(QueuedPrompt::text))

        // 模拟服务端极快完成第一条排队消息：turn-2 在 acceptance 返回前已经开始并结束。
        // 旧逻辑会在这里因为 sending=true 放弃 flush，并让 second 永久卡在 Queue。
        repository.setActiveTurn("turn-2")
        runCurrent()
        repository.setActiveTurn(null)
        runCurrent()
        firstAcceptance.complete(Unit)
        advanceUntilIdle()

        assertEquals(listOf("first", "second"), repository.sentPrompts.map(SentPrompt::text))
        assertTrue(viewModel.composer.value.queuedPrompts.isEmpty())
        assertFalse(viewModel.composer.value.sending)
    }

    @Test
    fun `failed automatic queue flush reinserts prompt at the front`() = runTest {
        repository.sendBlock = { _, _, _ -> error("socket_acceptance_failed") }
        val viewModel = viewModel()
        viewModel.open("websocket:a", "a")
        runCurrent()
        repository.setActiveTurn("turn-1")
        runCurrent()
        viewModel.updateText("first")
        viewModel.send()
        viewModel.updateText("second")
        viewModel.send()

        repository.setActiveTurn(null)
        advanceUntilIdle()

        assertEquals(listOf("first"), repository.sentPrompts.map(SentPrompt::text))
        assertEquals(
            listOf("first", "second"),
            viewModel.composer.value.queuedPrompts.map(QueuedPrompt::text),
        )
        assertEquals("socket_acceptance_failed", viewModel.composer.value.error)
        assertFalse(viewModel.composer.value.sending)
    }

    @Test
    fun `stop clears queued prompts and suppresses the following turn-end flush`() = runTest {
        val viewModel = viewModel()
        viewModel.open("websocket:a", "a")
        runCurrent()
        repository.setActiveTurn("turn-1")
        runCurrent()
        viewModel.updateText("do not send")
        viewModel.send()

        viewModel.stop()
        repository.setActiveTurn(null)
        advanceUntilIdle()

        assertEquals(1, repository.stopCount)
        assertTrue(repository.sentPrompts.isEmpty())
        assertTrue(viewModel.composer.value.queuedPrompts.isEmpty())
    }

    @Test
    fun `rejected duplicate stop keeps queued prompts unchanged`() = runTest {
        val viewModel = viewModel()
        viewModel.open("websocket:a", "a")
        runCurrent()
        repository.setActiveTurn("turn-1")
        runCurrent()
        viewModel.updateText("keep queued")
        viewModel.send()
        repository.stopResult = false

        viewModel.stop()

        // Repository 返回 false 表示当前 turn 已有停止请求在途；ViewModel 不能再次触发
        // Queue 的 stop 转换，否则重复点击会把本地状态当作新的停止操作处理。
        assertEquals(1, repository.stopCount)
        assertEquals(listOf("keep queued"), viewModel.composer.value.queuedPrompts.map(QueuedPrompt::text))
    }

    @Test
    fun `queued prompt can be removed without changing the remaining order`() = runTest {
        val viewModel = viewModel()
        viewModel.open("websocket:a", "a")
        runCurrent()
        repository.setActiveTurn("turn-1")
        runCurrent()
        viewModel.updateText("first")
        viewModel.send()
        viewModel.updateText("second")
        viewModel.send()
        val firstId = viewModel.composer.value.queuedPrompts.first().id

        viewModel.removeQueuedPrompt(firstId)

        assertEquals(listOf("second"), viewModel.composer.value.queuedPrompts.map(QueuedPrompt::text))
    }

    @Test
    fun `direct send failure restores text and quote`() = runTest {
        repository.sendBlock = { _, _, _ -> error("send_failed_remote") }
        val viewModel = viewModel()
        viewModel.open("websocket:a", "a")
        viewModel.updateText("keep me")
        viewModel.setQuotedContext("keep quote")

        viewModel.send()
        advanceUntilIdle()

        assertEquals("keep me", viewModel.composer.value.text)
        assertEquals("keep quote", viewModel.composer.value.quotedContext)
        assertEquals("send_failed_remote", viewModel.composer.value.error)
        assertFalse(viewModel.composer.value.sending)
    }

    @Test
    fun `retained send failure keeps failed bubble ownership and does not restore composer`() = runTest {
        repository.sendOutcome = ChatSendOutcome.FailedRetained("local:turn-1", "send_rejected")
        val viewModel = viewModel()
        viewModel.open("websocket:a", "a")
        viewModel.updateText("already represented by failed bubble")
        viewModel.setQuotedContext("quoted answer")

        viewModel.send()
        advanceUntilIdle()

        // Repository 已持有原始文本、引用和附件供气泡重试；Composer 若再恢复草稿会形成重复内容。
        assertEquals("", viewModel.composer.value.text)
        assertNull(viewModel.composer.value.quotedContext)
        assertTrue(viewModel.composer.value.attachments.isEmpty())
        assertNull(viewModel.composer.value.error)
        assertFalse(viewModel.composer.value.sending)
    }

    @Test
    fun `opening a different session clears queue and prevents stale flush`() = runTest {
        val viewModel = viewModel()
        viewModel.open("websocket:a", "a")
        runCurrent()
        repository.setActiveTurn("turn-a")
        runCurrent()
        viewModel.updateText("old queued prompt")
        viewModel.send()

        viewModel.open("websocket:b", "b")
        runCurrent()
        repository.setActiveTurn(null)
        advanceUntilIdle()

        assertTrue(repository.sentPrompts.isEmpty())
        assertEquals(ComposerUiState(), viewModel.composer.value)
        assertEquals("websocket:b", repository.state.value.sessionKey)
    }

    @Test
    fun `fork failure preserves draft and exposes retryable error`() = runTest {
        repository.forkBlock = { _, _ -> error("fork_failed_remote") }
        val viewModel = viewModel()
        viewModel.open("websocket:a", "a")
        viewModel.updateText("keep me")

        viewModel.fork("assistant-1", 3, "title") {}
        assertEquals("keep me", viewModel.composer.value.text)
        assertEquals("assistant-1", viewModel.composer.value.forkingMessageId)
        advanceUntilIdle()

        assertEquals("keep me", viewModel.composer.value.text)
        assertEquals("fork_failed_remote", viewModel.composer.value.error)
        assertNull(viewModel.composer.value.forkingMessageId)
    }

    @Test
    fun `fork completion from an old session cannot navigate or restore stale state`() = runTest {
        val gate = CompletableDeferred<String>()
        repository.forkBlock = { _, _ -> gate.await() }
        val viewModel = viewModel()
        viewModel.open("websocket:a", "a")
        viewModel.updateText("old draft")
        var created: String? = null

        viewModel.fork("assistant-1", 1, "title") { created = it }
        runCurrent()
        viewModel.open("websocket:b", "b")
        gate.complete("websocket:forked")
        advanceUntilIdle()

        assertNull(created)
        assertEquals(ComposerUiState(), viewModel.composer.value)
        assertEquals("websocket:b", repository.state.value.sessionKey)
    }

    @Test
    fun `retry exposes busy state until socket acceptance finishes`() = runTest {
        val gate = CompletableDeferred<Unit>()
        repository.retryBlock = { gate.await() }
        val viewModel = viewModel()
        viewModel.open("websocket:a", "a")

        viewModel.retry("assistant-1")
        assertEquals("assistant-1", viewModel.composer.value.retryingMessageId)
        runCurrent()
        gate.complete(Unit)
        advanceUntilIdle()

        assertNull(viewModel.composer.value.retryingMessageId)
        assertNull(viewModel.composer.value.error)
    }

    @Test
    fun `retry rejection keeps failure feedback in timeline without composer error`() = runTest {
        repository.retryOutcome = ChatSendOutcome.FailedRetained("local:turn-2", "send_rejected")
        val viewModel = viewModel()
        viewModel.open("websocket:a", "a")

        viewModel.retry("local:turn-1")
        advanceUntilIdle()

        assertNull(viewModel.composer.value.retryingMessageId)
        assertNull(viewModel.composer.value.error)
    }

    @Test
    fun `active stop slash command clears draft and queue without sending`() = runTest {
        val viewModel = viewModel()
        viewModel.open("websocket:a", "a")
        repository.setSlashCommands(listOf(slashCommand("/stop", "stop_active_turn")))
        runCurrent()
        repository.setActiveTurn("turn-1")
        runCurrent()
        viewModel.updateText("queued")
        viewModel.send()
        viewModel.updateText("/stop")
        viewModel.setQuotedContext("quoted")

        viewModel.send()
        advanceUntilIdle()

        assertEquals(1, repository.stopCount)
        assertTrue(repository.sentPrompts.isEmpty())
        assertEquals("", viewModel.composer.value.text)
        assertNull(viewModel.composer.value.quotedContext)
        assertTrue(viewModel.composer.value.queuedPrompts.isEmpty())
        assertTrue(viewModel.composer.value.slashMenuDismissed)
    }

    @Test
    fun `side channel slash command sends immediately during active turn`() = runTest {
        val viewModel = viewModel()
        viewModel.open("websocket:a", "a")
        repository.setSlashCommands(listOf(slashCommand("/status", "side_channel")))
        repository.setActiveTurn("turn-1")
        runCurrent()
        viewModel.updateText("/status")

        viewModel.send()
        advanceUntilIdle()

        assertEquals(listOf("/status"), repository.sentPrompts.map(SentPrompt::text))
        assertTrue(repository.sentPrompts.single().options.sideChannel)
        assertTrue(viewModel.composer.value.queuedPrompts.isEmpty())
    }

    @Test
    fun `agent slash command bypasses active turn queue`() = runTest {
        val viewModel = viewModel()
        viewModel.open("websocket:a", "a")
        repository.setSlashCommands(listOf(slashCommand("/plan", "agent_turn")))
        repository.setActiveTurn("turn-1")
        runCurrent()
        viewModel.updateText("/plan")

        viewModel.send()
        advanceUntilIdle()

        assertEquals(listOf("/plan"), repository.sentPrompts.map(SentPrompt::text))
        assertFalse(repository.sentPrompts.single().options.sideChannel)
        assertTrue(viewModel.composer.value.queuedPrompts.isEmpty())
    }

    @Test
    fun `selecting slash commands inserts expected text and dismisses palette`() {
        val viewModel = viewModel()
        val withArgs = slashCommand("/model", "agent_turn", acceptsArgs = true)
        val withoutArgs = slashCommand("/status", "side_channel")

        viewModel.selectSlashCommand(withArgs)
        assertEquals("/model ", viewModel.composer.value.text)
        assertTrue(viewModel.composer.value.slashMenuDismissed)

        viewModel.updateText("/")
        assertFalse(viewModel.composer.value.slashMenuDismissed)
        viewModel.selectSlashCommand(withoutArgs)
        assertEquals("/status", viewModel.composer.value.text)
        assertTrue(viewModel.composer.value.slashMenuDismissed)
    }

    @Test
    fun `selecting stop lifecycle while active invokes stop immediately`() = runTest {
        val viewModel = viewModel()
        viewModel.open("websocket:a", "a")
        repository.setActiveTurn("turn-1")
        runCurrent()
        viewModel.updateText("draft")
        viewModel.setQuotedContext("quote")

        viewModel.selectSlashCommand(slashCommand("/halt", "stop_active_turn"))

        assertEquals(1, repository.stopCount)
        assertEquals("", viewModel.composer.value.text)
        assertNull(viewModel.composer.value.quotedContext)
        assertTrue(viewModel.composer.value.slashMenuDismissed)
    }

    @Test
    fun `failed side channel send restores slash command draft`() = runTest {
        repository.sendBlock = { _, _, _ -> error("socket_acceptance_failed") }
        val viewModel = viewModel()
        viewModel.open("websocket:a", "a")
        repository.setSlashCommands(listOf(slashCommand("/status", "side_channel")))
        repository.setActiveTurn("turn-1")
        runCurrent()
        viewModel.updateText("/status")

        viewModel.send()
        advanceUntilIdle()

        assertEquals("/status", viewModel.composer.value.text)
        assertEquals("socket_acceptance_failed", viewModel.composer.value.error)
        assertTrue(repository.sentPrompts.single().options.sideChannel)
    }

    @Test
    fun `skill selection replaces token at cursor and records recent command`() {
        val viewModel = viewModel()
        val skill = SkillSummary("review", "Review changes", "workspace", available = true)
        viewModel.updateText("before \$re after", cursorPosition = 10)

        viewModel.selectSkillMention(SkillMentionCandidate("\$review", false, skill))

        assertEquals("before \$review after", viewModel.composer.value.text)
        assertEquals(14, viewModel.composer.value.cursorPosition)
        assertEquals(listOf("\$review"), viewModel.composer.value.recentCommands)
        assertTrue(viewModel.composer.value.slashMenuDismissed)
    }

    @Test
    fun `capability selection replaces only the token at the current cursor`() {
        val viewModel = viewModel()
        val app = cliApp("rg")
        viewModel.updateText("use @r here and @later", cursorPosition = 6)

        viewModel.selectCapabilityMention(CapabilityMentionCandidate.Cli("rg", app))

        assertEquals("use @rg here and @later", viewModel.composer.value.text)
        assertEquals(7, viewModel.composer.value.cursorPosition)
        assertTrue(viewModel.composer.value.mentionMenuDismissed)
    }

    @Test
    fun `send attaches active cli and mcp mention payloads`() = runTest {
        val viewModel = viewModel()
        viewModel.open("websocket:a", "a")
        repository.setComposerCatalogs(
            cliApps = listOf(cliApp("rg")),
            mcpPresets = listOf(mcpPreset("github")),
        )
        viewModel.updateText("Use @RG and (@github)")

        viewModel.send()
        advanceUntilIdle()

        val options = repository.sentPrompts.single().options
        assertEquals(listOf("rg"), options.cliApps.map { it.name })
        assertEquals(listOf("github"), options.mcpPresets.map { it.name })
    }

    @Test
    fun `queued prompt preserves capability payloads captured when enqueued`() = runTest {
        val viewModel = viewModel()
        viewModel.open("websocket:a", "a")
        repository.setComposerCatalogs(cliApps = listOf(cliApp("rg")))
        repository.setActiveTurn("turn-1")
        runCurrent()
        viewModel.updateText("Use @rg")
        viewModel.send()
        repository.setComposerCatalogs()

        repository.setActiveTurn(null)
        advanceUntilIdle()

        assertEquals(listOf("rg"), repository.sentPrompts.single().options.cliApps.map { it.name })
    }

    @Test
    fun `queued prompt preserves an intentionally empty capability snapshot`() = runTest {
        val viewModel = viewModel()
        viewModel.open("websocket:a", "a")
        repository.setActiveTurn("turn-1")
        runCurrent()
        viewModel.updateText("Use @newapp")
        viewModel.send()
        repository.setComposerCatalogs(cliApps = listOf(cliApp("newapp")))

        repository.setActiveTurn(null)
        advanceUntilIdle()

        val options = repository.sentPrompts.single().options
        assertTrue(options.capabilityPayloadsResolved)
        assertTrue(options.cliApps.isEmpty())
        assertTrue(options.mcpPresets.isEmpty())
    }

    @Test
    fun `voice transcription appends text and clears transient voice state`() = runTest {
        repository.transcript = "transcribed words"
        voiceRecorder.recording = EncodedVoiceRecording(
            dataUrl = "data:audio/m4a;base64,AAAA",
            durationMs = 1_000,
            maxReached = false,
            bytes = 4,
        )
        val viewModel = viewModel()
        viewModel.open("websocket:a", "a")
        viewModel.updateText("prefix")

        viewModel.startVoiceRecording(permissionGranted = true)
        viewModel.stopVoiceRecording()
        advanceUntilIdle()

        assertEquals("prefix transcribed words", viewModel.composer.value.text)
        assertEquals(VoiceUiState(), viewModel.composer.value.voice)
    }

    private fun viewModel(encoder: AttachmentEncoding = attachmentEncoding) = ChatViewModel(
        repository,
        encoder,
        voiceRecorder,
        composerRecentsStore,
    )
}

/**
 * 可控的挂起编码器：每次 encode 都把完成句柄暴露给测试，只有测试显式 complete
 * 或 completeExceptionally 后，ChatViewModel 的编码协程才会继续。这样可以稳定制造
 * “编码期间切换会话”和“多批编码交错完成”的竞态，而不依赖真实 ContentResolver。
 */
private class DeferredAttachmentEncoder : AttachmentEncoding {
    data class Pending(
        val uri: Uri,
        val completion: CompletableDeferred<ComposerAttachment>,
    )

    val pending = mutableListOf<Pending>()

    override suspend fun encode(uri: Uri, maxFileBytes: Long): ComposerAttachment {
        val request = Pending(uri, CompletableDeferred())
        pending += request
        return request.completion.await()
    }
}

private fun composerAttachment(uri: Uri, name: String): ComposerAttachment = ComposerAttachment(
    uri = uri,
    name = name,
    mimeType = "text/plain",
    bytes = 4,
    outbound = OutboundMedia("data:text/plain;base64,AAAA", name),
)

private class FakeComposerRecentsStore : ComposerRecentsStore {
    var persisted: List<String> = emptyList()
    val saved = mutableListOf<List<String>>()

    override suspend fun load(): List<String> = persisted

    override suspend fun save(commands: List<String>) {
        persisted = commands
        saved += commands
    }
}
private data class OpenedSession(
    val sessionKey: String,
    val chatId: String,
    val workspaceScope: WorkspaceScope?,
    val modelPreset: String? = null,
)

private data class SentPrompt(
    val text: String,
    val media: List<OutboundMedia>,
    val quotedContext: String?,
    val options: ChatSendOptions,
)

private class FakeChatRepository : ChatRepository {
    private val mutableState = MutableStateFlow(ChatUiState())
    override val state: StateFlow<ChatUiState> = mutableState
    val sentPrompts = mutableListOf<SentPrompt>()
    val lastSentText: String?
        get() = sentPrompts.lastOrNull()?.text
    val lastQuotedContext: String?
        get() = sentPrompts.lastOrNull()?.quotedContext
    var transcript: String = ""
    var stopCount: Int = 0
    /** 可控返回值用于验证 Repository 拒绝重复停止时，ViewModel 不会重复清理 Queue。 */
    var stopResult: Boolean = true
    var sendBlock: suspend (String, List<OutboundMedia>, String?) -> Unit = { _, _, _ -> }
    var sendOutcome: ChatSendOutcome = ChatSendOutcome.Accepted
    var retryBlock: suspend (String) -> Unit = {}
    var retryOutcome: ChatSendOutcome = ChatSendOutcome.Accepted
    var forkBlock: suspend (Int, String?) -> String = { _, _ -> "websocket:forked" }
    val openedSessions = mutableListOf<OpenedSession>()
    val newChatScopes = mutableListOf<WorkspaceScope?>()
    val workspaceScopeChanges = mutableListOf<WorkspaceScope>()
    val modelPresetChanges = mutableListOf<String>()
    var startNewTopicCount = 0

    override fun startNewTopic() {
        startNewTopicCount += 1
        mutableState.value = ChatUiState(workspaceScope = mutableState.value.workspaceScope)
    }

    override fun reset() = Unit

    override fun openSession(
        sessionKey: String,
        chatId: String,
        workspaceScope: WorkspaceScope?,
        modelPreset: String?,
    ) {
        openedSessions += OpenedSession(sessionKey, chatId, workspaceScope, modelPreset)
        mutableState.value = ChatUiState(sessionKey = sessionKey, chatId = chatId, workspaceScope = workspaceScope)
    }

    fun setActiveTurn(turnId: String?) {
        mutableState.value = mutableState.value.copy(activeTurnId = turnId)
    }

    fun setSlashCommands(commands: List<SlashCommand>) {
        mutableState.value = mutableState.value.copy(slashCommands = commands)
    }

    fun setComposerCatalogs(
        skills: List<SkillSummary> = emptyList(),
        cliApps: List<CliAppInfo> = emptyList(),
        mcpPresets: List<McpPresetInfo> = emptyList(),
    ) {
        mutableState.value = mutableState.value.copy(
            skills = skills,
            cliApps = cliApps,
            mcpPresets = mcpPresets,
        )
    }

    override suspend fun newChat(workspaceScope: WorkspaceScope?): String {
        newChatScopes += workspaceScope
        return "websocket:new"
    }

    override fun setWorkspaceScope(workspaceScope: WorkspaceScope) {
        workspaceScopeChanges += workspaceScope
        mutableState.value = mutableState.value.copy(workspaceScope = workspaceScope)
    }

    override suspend fun changeModelPreset(name: String) {
        modelPresetChanges += name
    }

    fun setWorkspaceScopeState(workspaceScope: WorkspaceScope?) {
        mutableState.value = mutableState.value.copy(workspaceScope = workspaceScope)
    }

    override fun refresh() = Unit
    override fun loadOlder() = Unit

    override suspend fun send(
        text: String,
        media: List<OutboundMedia>,
        quotedContext: String?,
        options: ChatSendOptions,
    ): ChatSendOutcome {
        sentPrompts += SentPrompt(text, media, quotedContext, options)
        sendBlock(text, media, quotedContext)
        return sendOutcome
    }

    override suspend fun retry(messageId: String): ChatSendOutcome {
        retryBlock(messageId)
        return retryOutcome
    }
    override suspend fun fork(beforeUserIndex: Int, title: String?): String = forkBlock(beforeUserIndex, title)
    override fun stop(): Boolean {
        stopCount += 1
        return stopResult
    }
    override suspend fun transcribeAudio(dataUrl: String, durationMs: Long): String = transcript
    override suspend fun loadSessionAutomations(sessionKey: String): List<com.nanobotkt.core.model.SessionAutomationJob> =
        emptyList()
    override fun loadFilePreview(path: String) = Unit
    override fun clearFilePreview() = Unit
    override fun clearError() = Unit
}

private fun cliApp(name: String) = CliAppInfo(
    name = name,
    displayName = name.uppercase(),
    category = "developer",
    entryPoint = "bin/$name",
    installed = true,
)

private fun mcpPreset(name: String) = McpPresetInfo(
    name = name,
    displayName = name.uppercase(),
    category = "developer",
    transport = "stdio",
    installed = true,
    configured = true,
    status = "ready",
)

private fun workspaceScope(path: String, accessMode: WorkspaceAccessMode) = WorkspaceScope(
    projectPath = path,
    projectName = path.replace('\\', '/').substringAfterLast('/'),
    accessMode = accessMode,
    restrictToWorkspace = accessMode == WorkspaceAccessMode.RESTRICTED,
)

private fun slashCommand(
    command: String,
    lifecycle: String,
    acceptsArgs: Boolean = false,
) = SlashCommand(
    command = command,
    title = command.removePrefix("/").replaceFirstChar(Char::uppercase),
    description = "$command description",
    icon = "terminal",
    lifecycle = lifecycle,
    acceptsArgs = acceptsArgs,
)

private class FakeVoiceRecorder : VoiceRecorder {
    var cancelCount: Int = 0
    var recording: EncodedVoiceRecording = EncodedVoiceRecording(
        dataUrl = "data:audio/m4a;base64,AAAA",
        durationMs = 1_000,
        maxReached = false,
        bytes = 4,
    )
    private var active = false

    override fun start(maxDurationSec: Int, maxUploadMb: Int) {
        active = true
    }

    override fun durationMs(): Long = if (active) recording.durationMs else 0
    override fun meteringDb(): Double? = null
    override fun maxReached(): Boolean = recording.maxReached

    override suspend fun stopAndEncode(): EncodedVoiceRecording {
        active = false
        return recording
    }

    override fun cancel() {
        active = false
        cancelCount += 1
    }
}

@OptIn(ExperimentalCoroutinesApi::class)
class MainDispatcherRule(
    val dispatcher: TestDispatcher = StandardTestDispatcher(),
) : TestWatcher() {
    override fun starting(description: Description) {
        kotlinx.coroutines.Dispatchers.setMain(dispatcher)
    }

    override fun finished(description: Description) {
        kotlinx.coroutines.Dispatchers.resetMain()
    }
}
