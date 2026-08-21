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
import com.nanobotkt.core.persistence.ComposerDraftPayload
import com.nanobotkt.core.persistence.ComposerDraftRecord
import com.nanobotkt.core.persistence.ComposerDraftStore
import com.nanobotkt.core.persistence.ComposerRecentsStore
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.TestDispatcher
import kotlinx.coroutines.test.advanceTimeBy
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
    private val composerDraftStore = FakeComposerDraftStore()

    @Test
    fun `opening a different session resets composer and active recording`() = runTest {
        val viewModel = viewModel()
        viewModel.open("websocket:a", "a")
        viewModel.updateText("draft")
        viewModel.setQuotedContext("quoted")
        viewModel.startVoiceRecording(permissionGranted = true)
        val cancelCountBeforeSwitch = voiceRecorder.cancelCount

        viewModel.open("websocket:b", "b")
        runCurrent()

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
        runCurrent()
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
        runCurrent()
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
        runCurrent()
        viewModel.updateText("question")

        viewModel.send()
        advanceUntilIdle()

        assertEquals(scope, repository.sentPrompts.single().options.workspaceScope)
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
    fun `active turn blocks send and keeps the complete draft`() = runTest {
        val viewModel = viewModel()
        viewModel.open("websocket:a", "a")
        runCurrent()
        repository.setActiveTurn("turn-1")
        viewModel.updateText("next question", cursorPosition = 4)
        viewModel.setQuotedContext("quoted answer")

        viewModel.send()
        runCurrent()

        assertTrue(repository.sentPrompts.isEmpty())
        assertEquals("next question", viewModel.composer.value.text)
        assertEquals(4, viewModel.composer.value.cursorPosition)
        assertEquals("quoted answer", viewModel.composer.value.quotedContext)
        assertFalse(viewModel.composer.value.sending)
    }

    @Test
    fun `send persists draft before repository and keeps composer visible until acceptance`() = runTest {
        val acceptanceGate = CompletableDeferred<Unit>()
        val viewModel = viewModel()
        viewModel.open("websocket:a", "a")
        runCurrent()
        viewModel.updateText("a very long question", cursorPosition = 7)
        viewModel.setQuotedContext("quoted answer")
        repository.sendBlock = { _, _, _ -> acceptanceGate.await() }

        viewModel.send()
        runCurrent()

        val stored = composerDraftStore.records.getValue("session:websocket:a:a")
        assertEquals("a very long question", stored.payload.text)
        assertEquals(7, stored.payload.cursorPosition)
        assertEquals("quoted answer", stored.payload.quotedContext)
        assertEquals("a very long question", viewModel.composer.value.text)
        assertEquals("quoted answer", viewModel.composer.value.quotedContext)
        assertTrue(viewModel.composer.value.sending)

        // acceptance 等待期间所有业务入口都必须遵守只读边界，不能制造第二份 revision。
        viewModel.updateText("must be ignored")
        viewModel.clearQuotedContext()
        assertEquals("a very long question", viewModel.composer.value.text)
        assertEquals("quoted answer", viewModel.composer.value.quotedContext)

        acceptanceGate.complete(Unit)
        advanceUntilIdle()

        assertEquals("", viewModel.composer.value.text)
        assertNull(viewModel.composer.value.quotedContext)
        assertFalse(viewModel.composer.value.sending)
        assertTrue(composerDraftStore.records.isEmpty())
    }

    @Test
    fun `local draft save failure never calls repository and retains complete payload`() = runTest {
        val attachment = composerAttachment(TestUri("test://attachments/report.txt"), "report.txt")
        val encoder = ImmediateAttachmentEncoder(attachment)
        val viewModel = viewModel(encoder)
        viewModel.open("websocket:a", "a")
        runCurrent()
        viewModel.updateText("thousands of words", cursorPosition = 5)
        viewModel.setQuotedContext("quoted")
        viewModel.addAttachments(listOf(attachment.uri))
        runCurrent()
        composerDraftStore.saveFailure = IllegalStateException("local_disk_unavailable")

        viewModel.send()
        advanceUntilIdle()

        assertTrue(repository.sentPrompts.isEmpty())
        assertEquals("thousands of words", viewModel.composer.value.text)
        assertEquals(5, viewModel.composer.value.cursorPosition)
        assertEquals("quoted", viewModel.composer.value.quotedContext)
        assertEquals(listOf(attachment), viewModel.composer.value.attachments)
        assertEquals("local_disk_unavailable", viewModel.composer.value.error)
        assertFalse(viewModel.composer.value.sending)
    }

    @Test
    fun `failed retained outcome keeps draft and permits manual resend`() = runTest {
        val viewModel = viewModel()
        viewModel.open("websocket:a", "a")
        runCurrent()
        viewModel.updateText("retry me")
        viewModel.setQuotedContext("context")
        repository.sendOutcome = ChatSendOutcome.FailedRetained("local:turn-1", "server_rejected")

        viewModel.send()
        advanceUntilIdle()

        assertEquals("retry me", viewModel.composer.value.text)
        assertEquals("context", viewModel.composer.value.quotedContext)
        assertEquals("server_rejected", viewModel.composer.value.error)
        assertFalse(viewModel.composer.value.sending)
        assertEquals("retry me", composerDraftStore.records.getValue("session:websocket:a:a").payload.text)
    }

    @Test
    fun `repository exception keeps durable draft instead of clearing composer`() = runTest {
        val viewModel = viewModel()
        viewModel.open("websocket:a", "a")
        runCurrent()
        viewModel.updateText("keep after disconnect")
        repository.sendBlock = { _, _, _ -> error("socket_acceptance_failed") }

        viewModel.send()
        advanceUntilIdle()

        assertEquals("keep after disconnect", viewModel.composer.value.text)
        assertEquals("socket_acceptance_failed", viewModel.composer.value.error)
        assertFalse(viewModel.composer.value.sending)
        assertEquals(
            "keep after disconnect",
            composerDraftStore.records.getValue("session:websocket:a:a").payload.text,
        )
    }

    @Test
    fun `background final flush persists draft before debounce deadline`() = runTest {
        val viewModel = viewModel()
        viewModel.open("websocket:a", "a")
        runCurrent()
        viewModel.updateText("lock screen now")

        // 不推进 250ms debounce，直接模拟 Activity ON_STOP；final flush 必须立即落盘。
        viewModel.onAppBackgrounded()
        runCurrent()

        assertEquals(
            "lock screen now",
            composerDraftStore.records.getValue("session:websocket:a:a").payload.text,
        )
    }

    @Test
    fun `new view model restores draft but never sends it automatically`() = runTest {
        composerDraftStore.save(
            scopeKey = "session:websocket:a:a",
            revision = 7L,
            payload =
                ComposerDraftPayload(
                    text = "restored after process death",
                    cursorPosition = 9,
                    quotedContext = "quote",
                    sessionKey = "websocket:a",
                    chatId = "a",
                ),
        )
        val viewModel = viewModel()

        viewModel.open("websocket:a", "a")
        advanceUntilIdle()

        assertEquals("restored after process death", viewModel.composer.value.text)
        assertEquals(9, viewModel.composer.value.cursorPosition)
        assertEquals("quote", viewModel.composer.value.quotedContext)
        assertTrue(repository.sentPrompts.isEmpty())
        assertFalse(viewModel.composer.value.sending)
    }

    @Test
    fun `stop only stops active turn and never clears draft`() = runTest {
        val viewModel = viewModel()
        viewModel.open("websocket:a", "a")
        runCurrent()
        repository.setActiveTurn("turn-1")
        viewModel.updateText("send after stop")
        viewModel.setQuotedContext("keep quote")

        viewModel.stop()
        runCurrent()

        assertEquals(1, repository.stopCount)
        assertEquals("send after stop", viewModel.composer.value.text)
        assertEquals("keep quote", viewModel.composer.value.quotedContext)
        assertTrue(repository.sentPrompts.isEmpty())
    }

    @Test
    fun `session switching restores each sessions own draft`() = runTest {
        val viewModel = viewModel()
        viewModel.open("websocket:a", "a")
        runCurrent()
        viewModel.updateText("draft a")
        viewModel.open("websocket:b", "b")
        advanceUntilIdle()
        viewModel.updateText("draft b")

        viewModel.open("websocket:a", "a")
        advanceUntilIdle()
        assertEquals("draft a", viewModel.composer.value.text)

        viewModel.open("websocket:b", "b")
        advanceUntilIdle()
        assertEquals("draft b", viewModel.composer.value.text)
    }

    @Test
    fun `draft typed during hydration cannot send until disk revision is reconciled`() = runTest {
        composerDraftStore.save(
            scopeKey = "session:websocket:a:a",
            revision = 4L,
            payload = ComposerDraftPayload(text = "older disk draft", cursorPosition = 4),
        )
        val loadGate = CompletableDeferred<Unit>()
        composerDraftStore.loadGate = loadGate
        val viewModel = viewModel()

        viewModel.open("websocket:a", "a")
        runCurrent()
        viewModel.updateText("new draft")
        viewModel.send()
        runCurrent()

        // hydration 尚未确定磁盘 revision 时不允许发送；正文仍可编辑且点击不会进入 sending。
        assertTrue(repository.sentPrompts.isEmpty())
        assertEquals("new draft", viewModel.composer.value.text)
        assertFalse(viewModel.composer.value.sending)

        loadGate.complete(Unit)
        advanceUntilIdle()
        assertEquals("new draft", viewModel.composer.value.text)
        assertFalse(viewModel.composer.value.hydrating)
        assertTrue(repository.sentPrompts.isEmpty())

        // hydration 只解除发送闸门，绝不替用户自动重发；必须再次显式点击才发送。
        viewModel.send()
        advanceUntilIdle()
        assertEquals(listOf("new draft"), repository.sentPrompts.map { it.text })
        assertEquals("", viewModel.composer.value.text)
    }

    @Test
    fun `background final flush waits for hydration and persists the users newer text`() = runTest {
        composerDraftStore.save(
            scopeKey = "session:websocket:a:a",
            revision = 8L,
            payload = ComposerDraftPayload(text = "stale disk text", cursorPosition = 3),
        )
        val loadGate = CompletableDeferred<Unit>()
        composerDraftStore.loadGate = loadGate
        val viewModel = viewModel()

        viewModel.open("websocket:a", "a")
        runCurrent()
        viewModel.updateText("text typed immediately before lock")
        viewModel.onAppBackgrounded()
        runCurrent()

        // 模拟 Room load 与锁屏 final flush 重叠：load 完成后必须先提升 revision，再由 flush 保存
        // 当前内存正文，不能让旧磁盘记录凭借更大的 revision 在进程重建时重新出现。
        loadGate.complete(Unit)
        advanceUntilIdle()

        val persisted = composerDraftStore.records.getValue("session:websocket:a:a")
        assertEquals("text typed immediately before lock", persisted.payload.text)
        assertTrue(persisted.revision > 8L)
    }

    @Test
    fun `clearing a persisted draft removes stale content before process recreation`() = runTest {
        val firstViewModel = viewModel()
        firstViewModel.open("websocket:a", "a")
        runCurrent()
        firstViewModel.updateText("old text that must not return")
        advanceTimeBy(250L)
        advanceUntilIdle()
        assertEquals(
            "old text that must not return",
            composerDraftStore.records.getValue("session:websocket:a:a").payload.text,
        )

        // 清空动作会产生一个比磁盘记录更新的 revision。持久化层必须按 scope 删除旧记录，
        // 不能拿新 revision 去匹配旧 revision；否则删除静默失败，进程重建后旧长文本会重新出现。
        firstViewModel.updateText("")
        advanceTimeBy(250L)
        advanceUntilIdle()
        assertFalse(composerDraftStore.records.containsKey("session:websocket:a:a"))

        val recreatedViewModel = viewModel()
        recreatedViewModel.open("websocket:a", "a")
        advanceUntilIdle()

        assertEquals("", recreatedViewModel.composer.value.text)
        assertFalse(recreatedViewModel.composer.value.hydrating)
    }

    @Test
    fun `typing during hydration wins over an older persisted draft`() = runTest {
        composerDraftStore.save(
            scopeKey = "session:websocket:a:a",
            revision = 4L,
            payload = ComposerDraftPayload(text = "old disk text", cursorPosition = 4),
        )
        val loadGate = CompletableDeferred<Unit>()
        composerDraftStore.loadGate = loadGate
        val viewModel = viewModel()

        viewModel.open("websocket:a", "a")
        runCurrent()
        viewModel.updateText("new local text")
        loadGate.complete(Unit)
        advanceUntilIdle()

        assertEquals("new local text", viewModel.composer.value.text)
        assertFalse(viewModel.composer.value.hydrating)
        advanceTimeBy(250L)
        advanceUntilIdle()
        assertEquals(
            "new local text",
            composerDraftStore.records.getValue("session:websocket:a:a").payload.text,
        )
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
        // Stop 只影响服务端 turn，不清空用户已经为下一轮准备的 Draft。
        assertEquals("draft", viewModel.composer.value.text)
        assertEquals("quote", viewModel.composer.value.quotedContext)
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
        runCurrent()
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
        composerDraftStore,
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

/**
 * 与 Room 实现保持同样的单 Draft / revision 语义，并提供失败与挂起钩子来稳定覆盖锁屏竞态。
 */
private class FakeComposerDraftStore : ComposerDraftStore {
    val records = linkedMapOf<String, ComposerDraftRecord>()
    var saveFailure: Throwable? = null
    var loadGate: CompletableDeferred<Unit>? = null

    override suspend fun load(scopeKey: String): ComposerDraftRecord? {
        loadGate?.await()
        return records[scopeKey]
    }

    override suspend fun save(scopeKey: String, revision: Long, payload: ComposerDraftPayload) {
        saveFailure?.let { throw it }
        val current = records[scopeKey]
        if (current == null || current.revision <= revision) {
            records[scopeKey] = ComposerDraftRecord(scopeKey, revision, payload)
        }
    }

    override suspend fun delete(scopeKey: String, expectedRevision: Long?): Boolean {
        val current = records[scopeKey] ?: return false
        if (expectedRevision != null && current.revision != expectedRevision) return false
        records.remove(scopeKey)
        return true
    }

    override suspend fun deleteAll() {
        records.clear()
    }
}

/** 立即返回固定附件，避免可靠性测试依赖 Android ContentResolver。 */
private class ImmediateAttachmentEncoder(
    private val attachment: ComposerAttachment,
) : AttachmentEncoding {
    override suspend fun encode(uri: Uri, maxFileBytes: Long): ComposerAttachment = attachment
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
