package com.nanobotkt.feature.chat

/**
 * Queue 的边沿状态机。
 *
 * 这里只决定何时可以 flush，不执行网络请求，也不持有 Composer StateFlow。这样 active turn 的
 * 边沿、手动 stop 和 acceptance 回调不会继续以三个布尔变量散落在 ViewModel 中。
 */
internal class ChatQueueCoordinator(initialTurnActive: Boolean) {
    private var lastTurnActive = initialTurnActive
    private var skipNextFlush = false
    private var flushDeferredUntilSendCompletes = false
    private var counter = 0L

    fun reset(turnActive: Boolean) {
        lastTurnActive = turnActive
        skipNextFlush = false
        flushDeferredUntilSendCompletes = false
    }

    /** 返回 true 表示 ViewModel 现在可以发送队首。 */
    fun onChatStateChanged(turnActive: Boolean, composer: ComposerUiState): Boolean {
        val wasTurnActive = lastTurnActive
        lastTurnActive = turnActive
        if (!wasTurnActive || turnActive) return false

        if (skipNextFlush) {
            skipNextFlush = false
            flushDeferredUntilSendCompletes = false
            return false
        }
        if (composer.sending) {
            flushDeferredUntilSendCompletes = composer.queuedPrompts.isNotEmpty()
            return false
        }
        return true
    }

    fun onDirectSendStarted() {
        skipNextFlush = false
        flushDeferredUntilSendCompletes = false
    }

    fun onStop(hasQueuedPrompts: Boolean) {
        skipNextFlush = hasQueuedPrompts
        flushDeferredUntilSendCompletes = false
    }

    fun onQueueFlushStarted() {
        flushDeferredUntilSendCompletes = false
    }

    /** acceptance 完成后返回 true，表示 turn-end 已先到达，需要补做一次 flush。 */
    fun onSubmitSucceeded(): Boolean = flushDeferredUntilSendCompletes.also {
        flushDeferredUntilSendCompletes = false
    }

    fun onSubmitFailed() {
        flushDeferredUntilSendCompletes = false
    }

    fun nextPromptId(nowMs: Long = System.currentTimeMillis()): String {
        counter += 1
        return "queued-prompt-$nowMs-$counter"
    }
}
