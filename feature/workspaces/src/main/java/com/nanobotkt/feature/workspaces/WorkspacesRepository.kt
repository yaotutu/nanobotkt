package com.nanobotkt.feature.workspaces

import kotlinx.coroutines.CancellationException
import com.nanobotkt.core.model.WorkspacesPayload
import com.nanobotkt.core.network.GatewayApiClient
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import javax.inject.Inject
import javax.inject.Singleton

interface WorkspacesRepository { val state: StateFlow<WorkspacesUiState>; suspend fun refresh(); fun clearError() }
data class WorkspacesUiState(val payload: WorkspacesPayload? = null, val loading: Boolean = false, val error: String? = null)
@Singleton class DefaultWorkspacesRepository @Inject constructor(private val api: GatewayApiClient) : WorkspacesRepository {
    private val mutableState = MutableStateFlow(WorkspacesUiState())
    override val state = mutableState.asStateFlow()
    override suspend fun refresh() {
        val old = mutableState.value
        mutableState.value = old.copy(loading = true, error = null)
        try { mutableState.value = WorkspacesUiState(payload = api.get("/api/workspaces")) }
        catch (error: CancellationException) { mutableState.value = old; throw error }
        catch (error: Exception) { mutableState.value = old.copy(loading = false, error = error.message ?: "workspaces_refresh_failed") }
    }
    override fun clearError() { mutableState.value = mutableState.value.copy(error = null) }
}
