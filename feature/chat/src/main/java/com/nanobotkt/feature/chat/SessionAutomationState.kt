package com.nanobotkt.feature.chat

import com.nanobotkt.core.model.SessionAutomationJob
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue

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
    private var loadedOnce = false
    private var cancelled = false

    /** Starts a fresh generation: clears state and shows the loading card. */
    fun beginLoad() {
        requestGeneration += 1
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
        try {
            val nextJobs = loadJobs(sessionKey)
            if (cancelled || requestGeneration != generation) return
            jobs = nextJobs
            loadFailed = false
            loadedOnce = true
        } catch (error: Exception) {
            if (!cancelled && requestGeneration == generation && !loadedOnce) {
                loadFailed = true
            }
        } finally {
            if (!cancelled && requestGeneration == generation && showLoading) {
                loading = false
            }
        }
    }
}
