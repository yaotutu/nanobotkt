package com.nanobotkt.feature.workspaces
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.launch
import javax.inject.Inject
@HiltViewModel class WorkspacesViewModel @Inject constructor(private val repository: WorkspacesRepository): ViewModel() {
    val state = repository.state
    init { refresh() }
    fun refresh() = viewModelScope.launch { repository.refresh() }
    fun clearError() = repository.clearError()
}
