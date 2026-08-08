package com.nanobotkt.feature.workspaces.ui

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.nanobotkt.core.model.DefaultAccessMode
import com.nanobotkt.feature.workspaces.data.WorkspacesRepository
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.launch
import javax.inject.Inject

@HiltViewModel
class WorkspacesViewModel @Inject constructor(
    private val repository: WorkspacesRepository,
) : ViewModel() {
    val state = repository.state

    init {
        refresh()
    }

    fun refresh() = viewModelScope.launch {
        repository.refresh()
    }

    fun updateDefaultAccessMode(mode: DefaultAccessMode) = viewModelScope.launch {
        repository.updateDefaultAccessMode(mode)
    }

    fun clearError() = repository.clearError()
}
