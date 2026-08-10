package com.nanobotkt.feature.skills

import com.nanobotkt.core.model.SkillDetail
import com.nanobotkt.core.model.SkillsPayload
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.TestDispatcher
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
class SkillsViewModelTest {
    @get:Rule val mainDispatcherRule = SkillsMainDispatcherRule()

    @Test
    fun initRefreshesSkillsAndRefreshCanBeTriggeredAgain() = runTest {
        val repository = FakeSkillsRepository()
        val viewModel = SkillsViewModel(repository)

        runCurrent()
        viewModel.refresh()
        runCurrent()

        assertEquals(2, repository.refreshCalls)
    }

    @Test
    fun selectPublishesDetailAndCloseClearsOnlyPageSelection() = runTest {
        val repository = FakeSkillsRepository()
        val viewModel = SkillsViewModel(repository)
        runCurrent()

        viewModel.select("codex-subagent")
        runCurrent()
        assertTrue(viewModel.state.value.detailLoading)
        repository.completeDetail("codex-subagent", SkillDetail(name = "codex-subagent"))
        runCurrent()
        assertEquals("codex-subagent", viewModel.state.value.selected?.name)

        viewModel.closeDetail()
        runCurrent()
        assertNull(viewModel.state.value.selected)
        assertFalse(viewModel.state.value.detailLoading)
        // 关闭详情不应清空 Singleton Repository 中仍可复用的技能列表。
        assertEquals(SkillsPayload(), repository.state.value.skills)
    }

    @Test
    fun newerSelectionCannotBeOverwrittenByCancelledOlderRequest() = runTest {
        val repository = FakeSkillsRepository()
        val viewModel = SkillsViewModel(repository)
        runCurrent()

        viewModel.select("slow")
        runCurrent()
        viewModel.select("new")
        runCurrent()

        repository.completeDetail("slow", SkillDetail(name = "slow"))
        repository.completeDetail("new", SkillDetail(name = "new"))
        runCurrent()

        assertEquals("new", viewModel.state.value.selected?.name)
        assertFalse(viewModel.state.value.detailLoading)
    }

    @Test
    fun closeDetailPreventsLateResponseFromReopeningDialog() = runTest {
        val repository = FakeSkillsRepository()
        val viewModel = SkillsViewModel(repository)
        runCurrent()

        viewModel.select("slow")
        runCurrent()
        viewModel.closeDetail()
        repository.completeDetail("slow", SkillDetail(name = "slow"))
        runCurrent()

        assertNull(viewModel.state.value.selected)
        assertFalse(viewModel.state.value.detailLoading)
    }

    @Test
    fun repositoryResetImmediatelyMasksDetailFromPreviousLogin() = runTest {
        val repository = FakeSkillsRepository()
        val viewModel = SkillsViewModel(repository)
        runCurrent()

        viewModel.select("old-account")
        runCurrent()
        repository.reset()
        runCurrent()
        repository.completeDetail("old-account", SkillDetail(name = "old-account"))
        runCurrent()

        // generation 不同后，旧详情和 loading 都不能跨登录主体显示。
        assertEquals(SkillsUiState(), viewModel.state.value)
    }

    @Test
    fun detailFailureStopsLoadingAndExposesError() = runTest {
        val repository = FakeSkillsRepository()
        val viewModel = SkillsViewModel(repository)
        runCurrent()

        viewModel.select("broken")
        runCurrent()
        repository.failDetail("broken", IllegalStateException("detail unavailable"))
        runCurrent()

        assertNull(viewModel.state.value.selected)
        assertFalse(viewModel.state.value.detailLoading)
        assertEquals("detail unavailable", viewModel.state.value.error)
    }
}

private class FakeSkillsRepository : SkillsRepository {
    private val mutableState = MutableStateFlow(SkillsRepositoryState())
    override val state: StateFlow<SkillsRepositoryState> = mutableState.asStateFlow()
    private val details = mutableMapOf<String, CompletableDeferred<SkillDetail>>()
    var refreshCalls = 0

    override fun reset() {
        mutableState.value =
            SkillsRepositoryState(sessionGeneration = mutableState.value.sessionGeneration + 1)
    }

    override suspend fun refresh() {
        refreshCalls += 1
        mutableState.value = mutableState.value.copy(skills = SkillsPayload())
    }

    override suspend fun loadDetail(name: String): SkillDetail =
        details.getOrPut(name) { CompletableDeferred() }.await()

    fun completeDetail(name: String, detail: SkillDetail) {
        details.getOrPut(name) { CompletableDeferred() }.complete(detail)
    }

    fun failDetail(name: String, error: Throwable) {
        details.getOrPut(name) { CompletableDeferred() }.completeExceptionally(error)
    }
}

/** 为 ViewModel 测试提供可控主线程，保证 Job 取消和 StateFlow 组合顺序可复现。 */
@OptIn(ExperimentalCoroutinesApi::class)
class SkillsMainDispatcherRule(private val dispatcher: TestDispatcher = StandardTestDispatcher()) :
    TestWatcher() {
    override fun starting(description: Description) {
        kotlinx.coroutines.Dispatchers.setMain(dispatcher)
    }

    override fun finished(description: Description) {
        kotlinx.coroutines.Dispatchers.resetMain()
    }
}
