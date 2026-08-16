package com.nanobotkt.feature.chat

import com.nanobotkt.core.persistence.ComposerRecentsStore
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

/**
 * Composer 的单一状态持有者。
 *
 * ViewModel 仍负责业务事件编排，但不再同时维护 StateFlow、会话 epoch 和最近命令持久化三套
 * 可变状态。所有异步任务通过 [epoch] 判断结果是否仍属于当前会话；切换会话时 [resetForSession]
 * 一次性提升 epoch 并恢复最近命令，避免附件、语音或发送回调写回另一个会话的草稿。
 */
internal class ComposerStateCoordinator(
    private val scope: CoroutineScope,
    private val recentsStore: ComposerRecentsStore,
) {
    private val mutableState = MutableStateFlow(ComposerUiState())
    private val recents = mutableListOf<String>()
    private val recentsSaveMutex = Mutex()
    private var recentsRevision = 0L

    val state: StateFlow<ComposerUiState> = mutableState.asStateFlow()

    var value: ComposerUiState
        get() = mutableState.value
        set(value) {
            mutableState.value = value
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

    fun resetForSession() {
        epoch += 1
        value = ComposerUiState(recentCommands = recents.toList())
    }

    fun isCurrent(expectedEpoch: Long): Boolean = epoch == expectedEpoch

    fun recordRecentCommand(command: String) {
        recents.remove(command)
        recents.add(0, command)
        while (recents.size > MAX_RECENT_COMMANDS) recents.removeAt(recents.lastIndex)

        recentsRevision += 1
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

    private companion object {
        const val MAX_RECENT_COMMANDS = 5
    }
}
