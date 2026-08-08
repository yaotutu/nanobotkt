package com.nanobotkt.feature.workspaces

import com.nanobotkt.core.model.DefaultAccessMode
import com.nanobotkt.feature.workspaces.data.WorkspacesRepository
import com.nanobotkt.feature.workspaces.data.WorkspacesUiState
import com.nanobotkt.feature.workspaces.ui.WorkspacesViewModel
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
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TestWatcher
import org.junit.runner.Description

@OptIn(ExperimentalCoroutinesApi::class)
class WorkspacesViewModelTest {
    @get:Rule
    val mainDispatcherRule = WorkspacesMainDispatcherRule()

    @Test
    fun initAndExplicitRefreshForwardToRepository() = runTest {
        val repository = FakeWorkspacesRepository()
        val viewModel = WorkspacesViewModel(repository)

        runCurrent()
        viewModel.refresh()
        runCurrent()

        assertEquals(2, repository.refreshCalls)
    }

    @Test
    fun updateDefaultAccessModeForwardsToRepository() = runTest {
        val repository = FakeWorkspacesRepository()
        val viewModel = WorkspacesViewModel(repository)

        viewModel.updateDefaultAccessMode(DefaultAccessMode.FULL)
        runCurrent()

        assertEquals(DefaultAccessMode.FULL, repository.updatedMode)
    }

    @Test
    fun clearErrorForwardsWithoutChangingOtherRepositoryResponsibilities() = runTest {
        val repository = FakeWorkspacesRepository()
        val viewModel = WorkspacesViewModel(repository)

        viewModel.clearError()

        assertEquals(1, repository.clearErrorCalls)
        assertEquals(0, repository.refreshCalls)
    }
}

private class FakeWorkspacesRepository : WorkspacesRepository {
    private val mutableState = MutableStateFlow(WorkspacesUiState())
    override val state: StateFlow<WorkspacesUiState> = mutableState.asStateFlow()
    var refreshCalls = 0
    var clearErrorCalls = 0
    var updatedMode: DefaultAccessMode? = null

    override fun reset() = Unit

    override suspend fun refresh() {
        refreshCalls += 1
    }

    override suspend fun updateDefaultAccessMode(mode: DefaultAccessMode) {
        updatedMode = mode
    }

    override fun clearError() {
        clearErrorCalls += 1
    }
}

@OptIn(ExperimentalCoroutinesApi::class)
/**
 * 为 Workspaces ViewModel 测试提供可控的主线程调度器。
 */
class WorkspacesMainDispatcherRule(
    private val dispatcher: TestDispatcher = StandardTestDispatcher(),
) : TestWatcher() {
    override fun starting(description: Description) {
        kotlinx.coroutines.Dispatchers.setMain(dispatcher)
    }

    override fun finished(description: Description) {
        kotlinx.coroutines.Dispatchers.resetMain()
    }
}
