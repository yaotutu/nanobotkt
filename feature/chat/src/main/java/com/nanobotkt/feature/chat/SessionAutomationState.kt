package com.nanobotkt.feature.chat

import com.nanobotkt.core.model.SessionAutomationJob
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import kotlinx.coroutines.CancellationException

internal const val AUTOMATIONS_REFRESH_MS: Long = 3_000L

/**
 * View state for the session automation list, mirroring RN `useSessionAutomations`:
 *
 * - `loading` only shows for the first load of a generation;
 * - a failure is surfaced only when this generation has never loaded successfully
 *   (silent background refreshes never replace a healthy list with an error);
 * - stale responses (from an earlier generation) are ignored.
 */
internal class SessionAutomationState {
    var jobs: List<SessionAutomationJob> by mutableStateOf(emptyList())
        private set
    var loading: Boolean by mutableStateOf(false)
        private set
    var loadFailed: Boolean by mutableStateOf(false)
        private set

    private var requestGeneration = 0
    /** 同一 generation 内 manual retry 与轮询也可能并发，只有最后发起的请求允许写回。 */
    private var latestRequestId = 0L
    private var loadedOnce = false
    private var cancelled = false

    /** Starts a fresh generation: clears state and shows the loading card. */
    fun beginLoad() {
        requestGeneration += 1
        latestRequestId += 1L
        loadedOnce = false
        cancelled = false
        jobs = emptyList()
        loadFailed = false
        loading = true
    }

    /** Marks the current generation as finished; later responses are ignored. */
    fun endGeneration() {
        cancelled = true
        requestGeneration += 1
        latestRequestId += 1L
    }

    /**
     * Runs one load. [showLoading] mirrors the RN `refresh(showLoading)` flag:
     * only the initial request resets jobs/loading; poll and app-foreground
     * refreshes update the list silently.
     */
    suspend fun request(
        loadJobs: suspend (sessionKey: String) -> List<SessionAutomationJob>,
        sessionKey: String,
        showLoading: Boolean = false,
    ) {
        if (cancelled) return
        if (showLoading) {
            jobs = emptyList()
            loadFailed = false
            loading = true
        }
        val generation = requestGeneration
        val requestId = ++latestRequestId
        try {
            val nextJobs = loadJobs(sessionKey)
            if (!isCurrentRequest(generation, requestId)) return
            jobs = nextJobs
            loadFailed = false
            loadedOnce = true
        } catch (error: CancellationException) {
            // repeatOnLifecycle 在 STOP 时通过协程取消终止请求；必须传播取消，不能误报加载失败。
            throw error
        } catch (error: Exception) {
            if (isCurrentRequest(generation, requestId) && !loadedOnce) {
                loadFailed = true
            }
        } finally {
            if (isCurrentRequest(generation, requestId)) {
                // loading 表示“当前 generation 尚无最新请求结论”，而不是某一个 showLoading 请求的
                // 私有状态。同代 silent refresh 若后来居上，它完成后也必须关闭旧请求留下的 loading；
                // 否则旧请求因 requestId 过期无法清理，页面会永久停留在加载卡片。
                loading = false
            }
        }
    }

    private fun isCurrentRequest(generation: Int, requestId: Long): Boolean =
        !cancelled && requestGeneration == generation && latestRequestId == requestId
}
