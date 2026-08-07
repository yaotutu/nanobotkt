package com.nanobotkt.feature.sidebar

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch
import javax.inject.Inject

@HiltViewModel
class SidebarViewModel @Inject constructor(
    private val repository: SidebarRepository,
) : ViewModel() {
    val state: StateFlow<SidebarUiState> = repository.state

    fun refresh() = viewModelScope.launch { repository.refresh() }
    fun togglePinned(key: String) = viewModelScope.launch { repository.togglePinned(key) }
    fun toggleArchived(key: String) = viewModelScope.launch { repository.toggleArchived(key) }
    fun rename(key: String, title: String) = viewModelScope.launch { repository.renameSession(key, title) }
    fun showArchived(show: Boolean) = viewModelScope.launch { repository.setShowArchived(show) }
    fun toggleGroup(groupId: String) = viewModelScope.launch { repository.toggleGroup(groupId) }
    fun renameProject(projectKey: String, title: String) = viewModelScope.launch { repository.renameProject(projectKey, title) }
    fun delete(key: String, deleteAutomations: Boolean = false) = viewModelScope.launch { repository.deleteSession(key, deleteAutomations) }
    fun clearError() = repository.clearError()
}
