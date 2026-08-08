package com.nanobotkt.feature.security

import androidx.lifecycle.ViewModelStore
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.TestDispatcher
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TestWatcher
import org.junit.runner.Description

/**
 * SecurityViewModel 的生命周期与轮询测试。
 *
 * 使用 ViewModelStore 触发真实的 ViewModel 清理流程，而不是直接调用 protected
 * onCleared；这样可以验证 viewModelScope 中的周期轮询会随页面对应的 ViewModel 一起取消。
 */
@OptIn(ExperimentalCoroutinesApi::class)
class SecurityViewModelTest {
    @get:Rule
    val mainDispatcherRule = SecurityMainDispatcherRule()

    @Test
    fun manualRefreshRunsImmediatelyAndPeriodicPollingStillRuns() = runTest {
        val repository = FakeSecurityRepository()
        val viewModel = SecurityViewModel(repository)
        val store = ViewModelStore()
        store.put("security", viewModel)

        viewModel.startPolling()
        runCurrent() // 页面进入后启动轮询，先执行一次 refresh，再进入 5 秒 delay。
        assertEquals(1, repository.refreshCalls)

        viewModel.refresh()
        runCurrent()
        // 手动刷新必须立即委托给 repository，不能等待下一次周期 tick。
        assertEquals(2, repository.refreshCalls)

        advanceTimeBy(4_999)
        runCurrent()
        assertEquals(2, repository.refreshCalls)

        advanceTimeBy(1)
        runCurrent()
        // 手动刷新不应终止初始化的周期任务；到 5 秒时仍应发生一次轮询刷新。
        assertEquals(3, repository.refreshCalls)

        // 测试结束前主动清理 ViewModel，避免无限轮询协程把 runTest 留在活动状态。
        store.clear()
    }

    @Test
    fun clearingViewModelStoreStopsFuturePeriodicRefreshes() = runTest {
        val repository = FakeSecurityRepository()
        val viewModel = SecurityViewModel(repository)
        val store = ViewModelStore()
        store.put("security", viewModel)

        viewModel.startPolling()
        runCurrent()
        val refreshesBeforeLeave = repository.refreshCalls

        // 导航离开页面时由 SecurityScreen 的 DisposableEffect 调用 stopPolling；
        // 即使 ViewModel 仍保留在 ViewModelStore 中，也不能继续访问服务端。
        viewModel.stopPolling()
        advanceTimeBy(15_000)
        runCurrent()

        assertEquals(refreshesBeforeLeave, repository.refreshCalls)
        store.clear()
    }
}

private class FakeSecurityRepository : SecurityRepository {
    private val mutableState = MutableStateFlow(SecurityUiState())
    override val state: StateFlow<SecurityUiState> = mutableState.asStateFlow()

    var refreshCalls: Int = 0
        private set

    override fun reset() = Unit

    override suspend fun refresh() {
        refreshCalls += 1
    }

    override suspend fun action(action: String, code: String) = Unit
}

@OptIn(ExperimentalCoroutinesApi::class)
class SecurityMainDispatcherRule(
    private val dispatcher: TestDispatcher = StandardTestDispatcher(),
) : TestWatcher() {
    override fun starting(description: Description) {
        kotlinx.coroutines.Dispatchers.setMain(dispatcher)
    }

    override fun finished(description: Description) {
        kotlinx.coroutines.Dispatchers.resetMain()
    }
}
