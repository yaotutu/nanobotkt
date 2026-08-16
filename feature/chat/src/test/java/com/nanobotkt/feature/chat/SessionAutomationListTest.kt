package com.nanobotkt.feature.chat

import com.nanobotkt.core.model.AutomationPayload
import com.nanobotkt.core.model.AutomationSchedule
import com.nanobotkt.core.model.AutomationState
import com.nanobotkt.core.model.SessionAutomationJob
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class SessionAutomationListTest {

    // ---------- formatSessionSchedule ----------

    @Test
    fun `schedule local trigger wins over every schedule`() {
        val job = job(
            id = "local",
            schedule = AutomationSchedule(kind = "every", everyMs = HOUR_MS),
            kind = "local_trigger",
        )
        assertEquals(SessionScheduleText.Local, formatSessionSchedule(job))
    }

    @Test
    fun `schedule payload kind local trigger wins over at schedule`() {
        val job = job(
            id = "local-payload",
            schedule = AutomationSchedule(kind = "at", atMs = 123L),
            payload = AutomationPayload(message = "run", kind = "local_trigger"),
        )
        assertEquals(SessionScheduleText.Local, formatSessionSchedule(job))
    }

    @Test
    fun `schedule kind local wins over cron schedule`() {
        val job = job(
            id = "local-schedule",
            schedule = AutomationSchedule(kind = "local", expr = "0 0 * * *"),
        )
        assertEquals(SessionScheduleText.Local, formatSessionSchedule(job))
    }

    @Test
    fun `schedule at keeps timestamp`() {
        val job = job(id = "at", schedule = AutomationSchedule(kind = "at", atMs = 1_700_000_000_000L))
        assertEquals(SessionScheduleText.At(1_700_000_000_000L), formatSessionSchedule(job))
    }

    @Test
    fun `schedule at without timestamp falls back to unknown`() {
        val job = job(id = "at-null", schedule = AutomationSchedule(kind = "at", atMs = null))
        assertEquals(SessionScheduleText.Unknown, formatSessionSchedule(job))
    }

    @Test
    fun `schedule every keeps duration`() {
        val job = job(id = "every", schedule = AutomationSchedule(kind = "every", everyMs = 3 * HOUR_MS))
        assertEquals(SessionScheduleText.Every(3 * HOUR_MS), formatSessionSchedule(job))
    }

    @Test
    fun `schedule every without duration falls back to unknown`() {
        val job = job(id = "every-null", schedule = AutomationSchedule(kind = "every", everyMs = null))
        assertEquals(SessionScheduleText.Unknown, formatSessionSchedule(job))
    }

    @Test
    fun `schedule cron without timezone`() {
        val job = job(
            id = "cron",
            schedule = AutomationSchedule(kind = "cron", expr = "0 */5 * * *", tz = null),
        )
        assertEquals(SessionScheduleText.Cron("0 */5 * * *", null), formatSessionSchedule(job))
    }

    @Test
    fun `schedule cron with timezone`() {
        val job = job(
            id = "cron-tz",
            schedule = AutomationSchedule(kind = "cron", expr = "0 9 * * *", tz = "Asia/Shanghai"),
        )
        assertEquals(
            SessionScheduleText.Cron("0 9 * * *", "Asia/Shanghai"),
            formatSessionSchedule(job),
        )
    }

    @Test
    fun `schedule unknown kind falls back to unknown`() {
        val job = job(id = "mystery", schedule = AutomationSchedule(kind = "fortnightly"))
        assertEquals(SessionScheduleText.Unknown, formatSessionSchedule(job))
    }

    // ---------- formatSessionNextRun ----------

    @Test
    fun `next run disabled wins`() {
        val job = job(
            id = "disabled",
            enabled = false,
            state = AutomationState(nextRunAtMs = 999L, pending = true),
        )
        assertEquals(SessionNextRunText.Disabled, formatSessionNextRun(job))
    }

    @Test
    fun `next run pending`() {
        val job = job(
            id = "pending",
            state = AutomationState(pending = true),
        )
        assertEquals(SessionNextRunText.Pending, formatSessionNextRun(job))
    }

    @Test
    fun `next run local trigger`() {
        val job = job(
            id = "local-next",
            schedule = AutomationSchedule(kind = "local"),
            state = AutomationState(nextRunAtMs = 999L),
        )
        assertEquals(SessionNextRunText.LocalTrigger, formatSessionNextRun(job))
    }

    @Test
    fun `next run none when no timestamp`() {
        val job = job(id = "none", state = AutomationState(nextRunAtMs = null))
        assertEquals(SessionNextRunText.None, formatSessionNextRun(job))
    }

    @Test
    fun `next run relative keeps timestamp`() {
        val next = 1_700_100_000_000L
        val job = job(id = "relative", state = AutomationState(nextRunAtMs = next))
        assertEquals(SessionNextRunText.Relative(next), formatSessionNextRun(job))
    }

    // ---------- isLocalTrigger ----------

    @Test
    fun `local trigger detection covers kind payload kind and schedule kind`() {
        assertTrue(isLocalTrigger(job("a", kind = "local_trigger")))
        assertTrue(
            isLocalTrigger(
                job("b", payload = AutomationPayload(message = "x", kind = "local_trigger")),
            ),
        )
        assertTrue(isLocalTrigger(job("c", schedule = AutomationSchedule(kind = "local"))))
        assertFalse(isLocalTrigger(job("d", schedule = AutomationSchedule(kind = "cron"))))
    }

    // ---------- sessionDuration ----------

    @Test
    fun `duration picks largest exact unit`() {
        assertEquals(SessionDuration(SessionDurationUnit.DAY, 2), sessionDuration(2 * DAY_MS))
        assertEquals(SessionDuration(SessionDurationUnit.HOUR, 5), sessionDuration(5 * HOUR_MS))
        assertEquals(SessionDuration(SessionDurationUnit.MINUTE, 12), sessionDuration(12 * MINUTE_MS))
        assertEquals(SessionDuration(SessionDurationUnit.SECOND, 45), sessionDuration(45 * SECOND_MS))
    }

    @Test
    fun `duration falls back to minutes for non dividing values`() {
        assertEquals(
            SessionDuration(SessionDurationUnit.MINUTE, 1),
            sessionDuration(90 * SECOND_MS + 500L),
        )
        assertEquals(
            SessionDuration(SessionDurationUnit.MINUTE, 0),
            sessionDuration(500L),
        )
        assertEquals(
            SessionDuration(SessionDurationUnit.SECOND, 90),
            sessionDuration(90 * SECOND_MS),
        )
    }

    // ---------- SessionAutomationState ----------

    @Test
    fun `initial load shows loading then jobs`() = runTest {
        val state = SessionAutomationState()
        state.beginLoad()
        assertTrue(state.loading)
        assertFalse(state.loadFailed)

        state.request({ listOf(job("a")) }, "key", showLoading = true)

        assertFalse(state.loading)
        assertFalse(state.loadFailed)
        assertEquals(listOf("a"), state.jobs.map { it.id })
    }

    @Test
    fun `empty list is not an error`() = runTest {
        val state = SessionAutomationState()
        state.beginLoad()
        state.request({ emptyList() }, "key", showLoading = true)

        assertFalse(state.loading)
        assertFalse(state.loadFailed)
        assertTrue(state.jobs.isEmpty())
    }

    @Test
    fun `first load failure surfaces error`() = runTest {
        val state = SessionAutomationState()
        state.beginLoad()
        state.request({ error("boom") }, "key", showLoading = true)

        assertFalse(state.loading)
        assertTrue(state.loadFailed)
        assertTrue(state.jobs.isEmpty())
    }

    @Test
    fun `failure after success is silent`() = runTest {
        val state = SessionAutomationState()
        var fail = false
        state.beginLoad()
        state.request(
            { if (fail) error("boom") else listOf(job("a")) },
            "key",
            showLoading = true,
        )

        fail = true
        state.request({ error("boom") }, "key", showLoading = false)

        assertFalse(state.loadFailed)
        assertEquals(listOf("a"), state.jobs.map { it.id })
    }

    @Test
    fun `failure then success clears error`() = runTest {
        val state = SessionAutomationState()
        var fail = true
        state.beginLoad()
        state.request({ error("boom") }, "key", showLoading = true)
        assertTrue(state.loadFailed)

        fail = false
        state.request({ listOf(job("a")) }, "key", showLoading = false)

        assertFalse(state.loadFailed)
        assertEquals(listOf("a"), state.jobs.map { it.id })
    }

    @Test
    fun `stale generation response is ignored`() = runTest {
        val state = SessionAutomationState()
        val slow = CompletableDeferred<List<SessionAutomationJob>>()

        state.beginLoad()
        val first = launch { state.request({ slow.await() }, "key", showLoading = true) }
        kotlinx.coroutines.yield()
        state.beginLoad()
        state.request({ listOf(job("new")) }, "key", showLoading = true)

        slow.complete(listOf(job("old")))
        first.join()

        assertEquals(listOf("new"), state.jobs.map { it.id })
        assertFalse(state.loading)
    }

    @Test
    fun `latest request wins within the same generation`() = runTest {
        val state = SessionAutomationState()
        val slowOlderRequest = CompletableDeferred<List<SessionAutomationJob>>()

        state.beginLoad()
        val older = launch {
            state.request({ slowOlderRequest.await() }, "key", showLoading = true)
        }
        kotlinx.coroutines.yield()
        state.request({ listOf(job("new")) }, "key", showLoading = false)

        slowOlderRequest.complete(listOf(job("old")))
        older.join()

        // manual retry 与轮询可能同代并发；较早请求迟到时不能覆盖最后发起请求的结果。
        assertEquals(listOf("new"), state.jobs.map { it.id })
        assertFalse(state.loadFailed)
        assertFalse(state.loading)
    }

    @Test
    fun `lifecycle cancellation is rethrown without becoming a load failure`() = runTest {
        val state = SessionAutomationState()
        state.beginLoad()
        var cancellationObserved = false

        try {
            state.request(
                loadJobs = { throw CancellationException("screen stopped") },
                sessionKey = "key",
                showLoading = true,
            )
        } catch (_: CancellationException) {
            cancellationObserved = true
        }

        // STOP 取消属于正常生命周期，不应污染错误卡片；调用协程仍必须收到取消信号。
        assertTrue(cancellationObserved)
        assertFalse(state.loadFailed)
        assertFalse(state.loading)
    }

    @Test
    fun `endGeneration discards later responses`() = runTest {
        val state = SessionAutomationState()
        state.beginLoad()
        state.endGeneration()
        state.request({ listOf(job("late")) }, "key", showLoading = false)

        assertTrue(state.jobs.isEmpty())
        assertFalse(state.loadFailed)
    }

    // ---------- fixtures ----------

    private fun job(
        id: String,
        enabled: Boolean = true,
        schedule: AutomationSchedule = AutomationSchedule(kind = "every", everyMs = HOUR_MS),
        payload: AutomationPayload = AutomationPayload(message = "run $id"),
        state: AutomationState = AutomationState(),
        kind: String? = null,
    ) = SessionAutomationJob(
        id = id,
        name = "Job $id",
        enabled = enabled,
        kind = kind,
        schedule = schedule,
        payload = payload,
        state = state,
    )
}
