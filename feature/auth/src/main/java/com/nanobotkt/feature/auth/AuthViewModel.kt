package com.nanobotkt.feature.auth

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import dagger.hilt.android.lifecycle.HiltViewModel
import javax.inject.Inject
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch

@HiltViewModel
class AuthViewModel @Inject constructor(private val repository: AuthSessionRepository) : ViewModel() {
    val state: StateFlow<AuthState> = repository.state

    init {
        repository.start()
    }

    /** 地址与 Secret 必须作为同一次显式提交进入认证仓库，避免跨端点复用旧凭据。 */
    fun authenticate(serverUrl: String, secret: String) {
        viewModelScope.launch { repository.authenticate(serverUrl, secret) }
    }

    fun retry() {
        viewModelScope.launch { repository.retry() }
    }

    fun changeServer() = repository.showAuthentication()
}
