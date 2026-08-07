package com.nanobotkt.feature.auth

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch
import javax.inject.Inject

@HiltViewModel
class AuthViewModel @Inject constructor(private val repository: AuthSessionRepository) : ViewModel() {
    val state: StateFlow<AuthState> = repository.state
    init { repository.start() }
    fun authenticate(secret: String) { viewModelScope.launch { repository.authenticate(secret) } }
    fun retry() { viewModelScope.launch { repository.retry() } }
}
