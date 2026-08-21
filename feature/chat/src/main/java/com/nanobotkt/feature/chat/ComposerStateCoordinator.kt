package com.nanobotkt.feature.chat

import android.net.Uri
import com.nanobotkt.core.persistence.ComposerDraftAttachment
import com.nanobotkt.core.persistence.ComposerDraftPayload
import com.nanobotkt.core.persistence.ComposerDraftStore
import com.nanobotkt.core.persistence.ComposerRecentsStore
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

/** Composer 草稿所属的会话作用域；scope key 是 Room 中单草稿记录的稳定主键。 */
internal data class ComposerDraftScope(
    val key: String,
    val sessionKey: String?,
    val chatId: String?,
)

/**
 * Composer 的单一状态持有者。
 *
 * 本类只实现两件事：维护输入状态、把每个会话的一条 Draft 写入 Room。它不再保存待发送队列、自动重发记录
 * 或消息投递状态。发送前同步保存 Draft；只有服务端 acceptance 成功后才按 revision 条件清空，
 * 因此明确失败、断网和锁屏进程死亡都只会留下可恢复正文，不会触发自动重发。
 */
internal class ComposerStateCoordinator(
    private val scope: CoroutineScope,
    private val recentsStore: ComposerRecentsStore,
    private val draftStore: ComposerDraftStore,
) {
    private val mutableState = MutableStateFlow(ComposerUiState())
    private val recents = mutableListOf<String>()
    private val recentsSaveMutex = Mutex()
    private val draftPersistenceMutex = Mutex()
    private var recentsRevision = 0L
    private var draftSaveJob: Job? = null
    private var currentScope: ComposerDraftScope? = null
    private var applyingInternalState = false

    val state: StateFlow<ComposerUiState> = mutableState.asStateFlow()

    var value: ComposerUiState
        get() = mutableState.value
        set(next) {
            val previous = mutableState.value
            val payloadChanged =
                !applyingInternalState && previous.hasDifferentDraftPayload(next)
            val normalized =
                if (payloadChanged) next.copy(revision = previous.revision + 1L) else next
            mutableState.value = normalized
            if (payloadChanged) scheduleDraftPersistence(normalized)
        }

    var epoch: Long = 0L
        private set

    init {
        val hydrationRevision = recentsRevision
        scope.launch {
            val persisted = recentsStore.load()
            // 用户在磁盘读取完成前已经选择过命令时，内存中的新顺序优先，禁止旧快照回灌。
            if (recentsRevision == hydrationRevision) {
                recents.clear()
                recents.addAll(persisted)
                value = value.copy(recentCommands = recents.toList())
            }
        }
    }

    fun update(transform: (ComposerUiState) -> ComposerUiState) {
        value = transform(value)
    }

    /**
     * 切换会话时先尽力保存旧会话快照，再异步恢复目标会话的唯一 Draft。
     *
     * hydration 期间如果用户已经开始输入，revision 会从 0 增长；此时磁盘旧草稿不得覆盖新输入。
     * 旧会话保存使用切换前捕获的 scope，不会因为 currentScope 已更新而写入新会话。
     */
    fun switchScope(target: ComposerDraftScope) {
        val previousScope = currentScope
        val previousSnapshot = mutableState.value
        draftSaveJob?.cancel()

        epoch += 1L
        currentScope = target
        val expectedEpoch = epoch
        setInternal(ComposerUiState(recentCommands = recents.toList(), hydrating = true))
        scope.launch {
            var promotedSnapshot: ComposerUiState? = null
            draftPersistenceMutex.withLock {
                // 旧 scope 的最终快照、新 scope 的读取以及 hydration 状态提交必须处在同一串行边界。
                // 否则 Activity STOP 的 final flush 可能在“Room 已读完、revision 尚未提升”的缝隙里
                // 保存较小 revision，随后进程被杀时，用户刚输入的长文本仍会输给磁盘旧记录。
                if (previousScope != null && !previousSnapshot.hydrating) {
                    runCatching { persistSnapshotUnlocked(previousScope, previousSnapshot) }
                }
                val loaded = runCatching { draftStore.load(target.key) }.getOrNull()
                if (epoch != expectedEpoch || currentScope != target) return@withLock

                val current = mutableState.value
                if (current.revision != 0L) {
                    // 用户已在磁盘读取期间输入，内存正文必须优先。进程重建后磁盘 revision 可能比
                    // 新 ViewModel 从 0 开始递增的 revision 更大；若直接保存，Room 会把它识别为旧写入。
                    // 因此只提升 revision、不改 payload，并重新安排保存，让用户新输入最终覆盖旧磁盘草稿。
                    val promoted =
                        current.copy(
                            revision = maxOf(current.revision, (loaded?.revision ?: -1L) + 1L),
                            hydrating = false,
                        )
                    setInternal(promoted)
                    promotedSnapshot = promoted
                } else if (loaded != null) {
                    setInternal(
                        loaded.payload.toComposerState(
                            revision = loaded.revision,
                            recentCommands = recents.toList(),
                        )
                    )
                } else {
                    setInternal(current.copy(hydrating = false))
                }
            }
            // 调度 debounce 不能放在 Mutex 内，否则测试调度器或未来的立即执行实现可能反向等待
            // draftPersistenceMutex。这里只为 hydration 期间产生的新输入补一次最终保存。
            promotedSnapshot?.let(::scheduleDraftPersistence)
        }
    }

    fun isCurrent(expectedEpoch: Long): Boolean = epoch == expectedEpoch

    /** Activity STOP 前绕过 debounce，把最后一次输入同步提交给 Room。 */
    suspend fun persistCurrentDraftNow() {
        val target = currentScope ?: return
        draftSaveJob?.cancel()
        draftPersistenceMutex.withLock {
            // final flush 可能在会话 hydration 或快速切换期间等待 Mutex。必须在真正获得锁后
            // 重新读取 currentScope 与状态；使用调用前捕获的临时空快照会误删磁盘 Draft，使用
            // 新会话快照写入旧 scope 则会串话。hydration 提交也在同一锁内，因此此处拿到的一定
            // 是已经完成 revision 提升或磁盘恢复的最终状态。
            if (currentScope != target) return@withLock
            persistSnapshotUnlocked(target, mutableState.value)
        }
    }

    /**
     * 发送前的硬边界：只有当前会话和 revision 仍与点击时一致，才把完整 Draft 同步落盘。
     * 返回 false 表示用户已切换会话或状态已变化，调用方必须放弃本次网络发送。
     */
    suspend fun persistDraftForSend(expectedEpoch: Long, expectedRevision: Long): Boolean {
        val target = currentScope ?: return false
        val snapshot = mutableState.value
        if (epoch != expectedEpoch || snapshot.revision != expectedRevision) return false
        draftSaveJob?.cancel()
        return draftPersistenceMutex.withLock {
            // 等待磁盘锁期间会话可能已经切换；锁内再次校验，失败必须真实返回 false，
            // 否则调用方会在 Draft 未正确落盘时继续发起网络请求。
            if (
                epoch != expectedEpoch ||
                    currentScope != target ||
                    mutableState.value.revision != expectedRevision
            ) return@withLock false
            persistSnapshotUnlocked(target, snapshot)
            true
        }
    }

    /**
     * acceptance 后按点击时 revision 清理草稿。
     *
     * 发送期间文本框被禁用，正常路径 revision 不会变化；条件删除仍保留为最后一道竞态保护，
     * 防止会话切换或未来编辑行为变化时误删较新的草稿。服务端未 acceptance 时绝不能调用本方法。
     */
    suspend fun clearAfterAcceptance(expectedEpoch: Long, expectedRevision: Long) {
        val target = currentScope ?: return
        if (epoch != expectedEpoch) return
        draftSaveJob?.cancel()
        draftPersistenceMutex.withLock {
            draftStore.delete(target.key, expectedRevision)
        }
        if (epoch != expectedEpoch || mutableState.value.revision != expectedRevision) return
        setInternal(
            mutableState.value.copy(
                revision = expectedRevision + 1L,
                text = "",
                cursorPosition = 0,
                attachments = emptyList(),
                quotedContext = null,
                sending = false,
                error = null,
            )
        )
    }

    fun recordRecentCommand(command: String) {
        recents.remove(command)
        recents.add(0, command)
        while (recents.size > MAX_RECENT_COMMANDS) recents.removeAt(recents.lastIndex)

        recentsRevision += 1L
        val revision = recentsRevision
        val snapshot = recents.toList()
        value = value.copy(recentCommands = snapshot)
        scope.launch {
            recentsSaveMutex.withLock {
                // 串行磁盘写入后仍需核对 revision，防止较慢的旧保存覆盖最新排序。
                if (revision == recentsRevision) recentsStore.save(snapshot)
            }
        }
    }

    private fun scheduleDraftPersistence(snapshot: ComposerUiState) {
        val target = currentScope ?: return
        draftSaveJob?.cancel()
        draftSaveJob =
            scope.launch {
                delay(DRAFT_SAVE_DEBOUNCE_MS)
                draftPersistenceMutex.withLock { persistSnapshotUnlocked(target, snapshot) }
            }
    }

    private suspend fun persistSnapshot(target: ComposerDraftScope, snapshot: ComposerUiState) {
        draftPersistenceMutex.withLock { persistSnapshotUnlocked(target, snapshot) }
    }

    private suspend fun persistSnapshotUnlocked(
        target: ComposerDraftScope,
        snapshot: ComposerUiState,
    ) {
        if (snapshot.hasDraftContent()) {
            draftStore.save(target.key, snapshot.revision, snapshot.toDraftPayload(target))
        } else {
            // 用户把非空 Draft 清空时，内存 revision 已经比磁盘中的旧正文大 1；若按新 revision
            // 条件删除，旧记录永远匹配不到，进程重启后会把已经清空的文字重新恢复。所有草稿写入、
            // 会话切换保存和 hydration 都由 draftPersistenceMutex 串行化，因此这里按 scope 删除即可，
            // 不会越过较新的保存任务误删新输入。acceptance 清理仍使用严格 revision 条件。
            draftStore.delete(target.key)
        }
    }

    private fun setInternal(next: ComposerUiState) {
        applyingInternalState = true
        mutableState.value = next
        applyingInternalState = false
    }

    private companion object {
        const val MAX_RECENT_COMMANDS = 5
        const val DRAFT_SAVE_DEBOUNCE_MS = 250L
    }
}

internal fun existingComposerScope(sessionKey: String, chatId: String): ComposerDraftScope =
    ComposerDraftScope("session:$sessionKey:$chatId", sessionKey, chatId)

internal fun newTopicComposerScope(
    @Suppress("UNUSED_PARAMETER") workspaceProjectPath: String?
): ComposerDraftScope =
    // 新主题在发送前允许切换 Workspace，因此使用唯一稳定 scope；Workspace 仍在点击发送时捕获。
    ComposerDraftScope("new-topic", null, null)

private fun ComposerUiState.hasDifferentDraftPayload(other: ComposerUiState): Boolean =
    text != other.text ||
        cursorPosition != other.cursorPosition ||
        attachments != other.attachments ||
        quotedContext != other.quotedContext

private fun ComposerUiState.hasDraftContent(): Boolean =
    text.isNotBlank() || attachments.isNotEmpty() || !quotedContext.isNullOrBlank()

private fun ComposerUiState.toDraftPayload(scope: ComposerDraftScope): ComposerDraftPayload =
    ComposerDraftPayload(
        text = text,
        cursorPosition = cursorPosition,
        quotedContext = quotedContext,
        attachments = attachments.map(ComposerAttachment::toDraftAttachment),
        sessionKey = scope.sessionKey,
        chatId = scope.chatId,
    )

private fun ComposerAttachment.toDraftAttachment(): ComposerDraftAttachment =
    ComposerDraftAttachment(
        uri = uri.toString(),
        name = name,
        mimeType = mimeType,
        bytes = bytes,
        outbound = outbound,
    )

private fun ComposerDraftAttachment.toComposerAttachment(): ComposerAttachment =
    ComposerAttachment(
        uri = Uri.parse(uri),
        name = name,
        mimeType = mimeType,
        bytes = bytes,
        outbound = outbound,
    )

private fun ComposerDraftPayload.toComposerState(
    revision: Long,
    recentCommands: List<String>,
): ComposerUiState =
    ComposerUiState(
        revision = revision,
        hydrating = false,
        text = text,
        cursorPosition = cursorPosition.coerceIn(0, text.length),
        attachments = attachments.map(ComposerDraftAttachment::toComposerAttachment),
        quotedContext = quotedContext,
        recentCommands = recentCommands,
    )
