package com.nanobotkt.feature.channels

import com.nanobotkt.core.model.ChannelConnectPayload
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.TestDispatcher
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TestWatcher
import org.junit.runner.Description

/**
 * ChannelsViewModel 的轮询边界测试。
 *
 * 这些测试只使用 fake repository，不连接真实频道，也不产生第三方配置副作用；
 * 重点验证 session_id 传递和服务端 interval_ms 的安全范围。
 */
@OptIn(ExperimentalCoroutinesApi::class)
class ChannelsViewModelTest {
    @get:Rule
    val mainDispatcherRule = ChannelsMainDispatcherRule()

    @Test
    fun connectPollsWithSessionReturnedByStart() = runTest {
        val repository = FakeChannelsRepository(
            startResult = connection(sessionId = "session-from-start", status = "pending", intervalMs = 500),
            pollResults = listOf(connection(sessionId = "session-from-start", status = "ready")),
        )
        val viewModel = ChannelsViewModel(repository)
        runCurrent() // 消化初始化时的 refresh。

        viewModel.connect("WhatsApp", instanceId = "phone-1")
        runCurrent()
        assertEquals(emptyList<String>(), repository.pollSessionIds)

        advanceTimeBy(499)
        runCurrent()
        assertEquals(emptyList<String>(), repository.pollSessionIds)

        advanceTimeBy(1)
        runCurrent()

        assertEquals(listOf("session-from-start"), repository.pollSessionIds)
        assertEquals(listOf("WhatsApp" to "phone-1"), repository.startCalls)
    }

    @Test
    fun duplicateConnectForSameInstanceIsDroppedButDifferentInstancesCanConnect() = runTest {
        val repository = FakeChannelsRepository(
            startResult = connection(sessionId = "same-channel", status = "pending", intervalMs = 5_000),
        )
        val viewModel = ChannelsViewModel(repository)
        runCurrent()

        viewModel.connect("Slack", instanceId = "team-a")
        viewModel.connect("Slack", instanceId = "team-a")
        viewModel.connect("Slack", instanceId = "team-b")
        runCurrent()

        // 同一 channel + instance 只保留一个轮询 Job；不同 instance 必须使用独立 key，不能互相去重。
        assertEquals(
            listOf("Slack" to "team-a", "Slack" to "team-b"),
            repository.startCalls,
        )
    }

    @Test
    fun connectStopsPollingWhenRepositoryReturnsNull() = runTest {
        val repository = FakeChannelsRepository(
            startResult = connection(sessionId = "nullable-poll", status = "pending", intervalMs = 500),
            pollResults = listOf(null),
        )
        val viewModel = ChannelsViewModel(repository)
        runCurrent()

        viewModel.connect("Slack")
        runCurrent()
        advanceTimeBy(500)
        runCurrent()
        advanceTimeBy(5_000)
        runCurrent()

        // poll 失败返回 null 后必须结束当前轮询，不能在下一周期继续用同一个 session_id 重试。
        assertEquals(listOf("nullable-poll"), repository.pollSessionIds)
    }

    @Test
    fun connectClampsTooSmallAndTooLargeIntervals() = runTest {
        val tooSmall = FakeChannelsRepository(
            startResult = connection(sessionId = "small", status = "pending", intervalMs = 1),
            pollResults = listOf(connection(sessionId = "small", status = "ready")),
        )
        val smallViewModel = ChannelsViewModel(tooSmall)
        runCurrent()
        smallViewModel.connect("Slack")
        runCurrent()
        advanceTimeBy(499)
        runCurrent()
        assertTrue(tooSmall.pollSessionIds.isEmpty())
        advanceTimeBy(1)
        runCurrent()
        assertEquals(listOf("small"), tooSmall.pollSessionIds)

        val tooLarge = FakeChannelsRepository(
            startResult = connection(sessionId = "large", status = "pending", intervalMs = 10_000),
            pollResults = listOf(connection(sessionId = "large", status = "ready")),
        )
        val largeViewModel = ChannelsViewModel(tooLarge)
        runCurrent()
        largeViewModel.connect("Slack")
        runCurrent()
        advanceTimeBy(4_999)
        runCurrent()
        assertTrue(tooLarge.pollSessionIds.isEmpty())
        advanceTimeBy(1)
        runCurrent()
        assertEquals(listOf("large"), tooLarge.pollSessionIds)
    }

    @Test
    fun cancelStopsTheActivePollingJobBeforeCallingRepository() = runTest {
        val repository = FakeChannelsRepository(
            startResult = connection(sessionId = "cancel-me", status = "pending", intervalMs = 500),
            pollResults = listOf(connection(sessionId = "cancel-me", status = "ready")),
            cancelResult = connection(sessionId = "cancel-me", status = "cancelled", instanceId = "account-a"),
        )
        val viewModel = ChannelsViewModel(repository)
        runCurrent()

        viewModel.connect("Email", instanceId = "account-a")
        runCurrent()
        viewModel.cancel("Email", sessionId = "cancel-me", instanceId = "account-a")
        runCurrent()
        advanceTimeBy(5_000)
        runCurrent()

        // Cancel 后不能再继续 poll；否则服务端可能在取消后又写回旧的 pending 状态。
        assertTrue(repository.pollSessionIds.isEmpty())
        assertEquals(listOf(CancelCall("Email", "cancel-me", "account-a")), repository.cancelCalls)
    }

    @Test
    fun cancelWhilePollIsInFlightPreventsLatePollAfterCancellation() = runTest {
        val repository = FakeChannelsRepository(
            startResult = connection(sessionId = "in-flight", status = "pending", intervalMs = 500),
            holdPollOpen = true,
        )
        val viewModel = ChannelsViewModel(repository)
        runCurrent()

        viewModel.connect("Email", instanceId = "account-a")
        runCurrent()
        advanceTimeBy(500)
        runCurrent()
        repository.pollStarted.await()

        // 这里刻意让 poll 挂在网络调用中，再并发触发 Cancel；
        // 取消 connect Job 后，迟到的 poll 不能在 Cancel 之后再次发起。
        viewModel.cancel("Email", sessionId = "in-flight", instanceId = "account-a")
        runCurrent()
        repository.pollCancelled.await()
        advanceTimeBy(5_000)
        runCurrent()

        assertEquals(listOf("in-flight"), repository.pollSessionIds)
        assertEquals(listOf(CancelCall("Email", "in-flight", "account-a")), repository.cancelCalls)
    }

    @Test
    fun cancelForwardsExplicitSessionAndInstance() = runTest {
        val repository = FakeChannelsRepository(
            cancelResult = connection(sessionId = "cancel-me", status = "cancelled", instanceId = "account-a"),
        )
        val viewModel = ChannelsViewModel(repository)
        runCurrent()

        viewModel.cancel("Email", sessionId = "cancel-me", instanceId = "account-a")
        runCurrent()

        assertEquals(listOf(CancelCall("Email", "cancel-me", "account-a")), repository.cancelCalls)
    }
}

private class FakeChannelsRepository(
    private val startResult: ChannelConnectPayload? = null,
    private val pollResults: List<ChannelConnectPayload?> = emptyList(),
    private val cancelResult: ChannelConnectPayload? = null,
    private val holdPollOpen: Boolean = false,
) : ChannelsRepository {
    private val mutableState = MutableStateFlow(ChannelsUiState())
    override val state: StateFlow<ChannelsUiState> = mutableState.asStateFlow()

    val startCalls = mutableListOf<Pair<String, String?>>()
    val pollSessionIds = mutableListOf<String>()
    val cancelCalls = mutableListOf<CancelCall>()
    val pollStarted = CompletableDeferred<Unit>()
    val pollCancelled = CompletableDeferred<Unit>()
    private var pollIndex = 0

    override suspend fun refresh() = Unit

    override fun reset() = Unit

    override suspend fun setEnabled(name: String, enabled: Boolean, instanceId: String?) = Unit

    override suspend fun configure(
        name: String,
        values: Map<String, String>,
        enable: Boolean?,
        instanceId: String?,
    ) = Unit

    override suspend fun validateAndConfigure(
        name: String,
        values: Map<String, String>,
        instanceId: String?,
    ): com.nanobotkt.core.model.ChannelValidationPayload? = null

    override suspend fun validate(name: String, values: Map<String, String>, instanceId: String?) = Unit

    override suspend fun startConnect(name: String, instanceId: String?): ChannelConnectPayload? {
        startCalls += name to instanceId
        return startResult
    }

    override suspend fun pollConnect(
        name: String,
        sessionId: String,
        instanceId: String?,
    ): ChannelConnectPayload? {
        pollSessionIds += sessionId
        if (holdPollOpen) {
            pollStarted.complete(Unit)
            try {
                // 模拟真实挂起中的网络 poll；只有取消 connect Job 才能离开这里。
                awaitCancellation()
            } catch (error: kotlinx.coroutines.CancellationException) {
                pollCancelled.complete(Unit)
                throw error
            }
        }
        return pollResults.getOrNull(pollIndex++)
    }

    override suspend fun cancelConnect(
        name: String,
        sessionId: String,
        instanceId: String?,
    ): ChannelConnectPayload? {
        cancelCalls += CancelCall(name, sessionId, instanceId)
        return cancelResult
    }
}

private data class CancelCall(
    val name: String,
    val sessionId: String,
    val instanceId: String?,
)

private fun connection(
    sessionId: String,
    status: String,
    intervalMs: Long? = null,
    instanceId: String? = null,
): ChannelConnectPayload = ChannelConnectPayload(
    sessionId = sessionId,
    instanceId = instanceId,
    status = status,
    intervalMs = intervalMs,
)

@OptIn(ExperimentalCoroutinesApi::class)
class ChannelsMainDispatcherRule(
    private val dispatcher: TestDispatcher = StandardTestDispatcher(),
) : TestWatcher() {
    override fun starting(description: Description) {
        kotlinx.coroutines.Dispatchers.setMain(dispatcher)
    }

    override fun finished(description: Description) {
        kotlinx.coroutines.Dispatchers.resetMain()
    }
}
