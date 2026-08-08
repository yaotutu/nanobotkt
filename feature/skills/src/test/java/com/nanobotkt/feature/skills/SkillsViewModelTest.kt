package com.nanobotkt.feature.skills

import com.nanobotkt.core.model.SkillDetail
import com.nanobotkt.core.model.SkillsPayload
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
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TestWatcher
import org.junit.runner.Description

@OptIn(ExperimentalCoroutinesApi::class)
class SkillsViewModelTest {
    @get:Rule
    val mainDispatcherRule = SkillsMainDispatcherRule()

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
    fun selectForwardsSkillNameAndCloseDetailClearsSelection() = runTest {
        val repository = FakeSkillsRepository()
        val viewModel = SkillsViewModel(repository)
        runCurrent()

        viewModel.select("codex-subagent")
        runCurrent()
        assertEquals(listOf("codex-subagent"), repository.selectedNames)

        viewModel.closeDetail()
        assertEquals(1, repository.clearSelectionCalls)
    }
}

private class FakeSkillsRepository : SkillsRepository {
    private val mutableState = MutableStateFlow(SkillsUiState())
    override val state: StateFlow<SkillsUiState> = mutableState.asStateFlow()
    var refreshCalls = 0
    val selectedNames = mutableListOf<String>()
    var clearSelectionCalls = 0

    override fun reset() = Unit

    override suspend fun refresh() {
        refreshCalls += 1
        mutableState.value = mutableState.value.copy(skills = SkillsPayload())
    }

    override suspend fun select(name: String) {
        selectedNames += name
        mutableState.value = mutableState.value.copy(
            selected = SkillDetail(name = name),
            detailLoading = false,
        )
    }

    override fun clearSelection() {
        clearSelectionCalls += 1
        mutableState.value = mutableState.value.copy(selected = null, detailLoading = false)
    }
}

@OptIn(ExperimentalCoroutinesApi::class)
/**
 * 为 ViewModel 测试提供可控的主线程调度器，避免测试依赖真实 Android 主线程。
 */
class SkillsMainDispatcherRule(
    private val dispatcher: TestDispatcher = StandardTestDispatcher(),
) : TestWatcher() {
    override fun starting(description: Description) {
        kotlinx.coroutines.Dispatchers.setMain(dispatcher)
    }

    override fun finished(description: Description) {
        kotlinx.coroutines.Dispatchers.resetMain()
    }
}
