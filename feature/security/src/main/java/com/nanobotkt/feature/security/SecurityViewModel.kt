package com.nanobotkt.feature.security

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import javax.inject.Inject

@HiltViewModel
class SecurityViewModel @Inject constructor(
    private val repository: SecurityRepository,
) : ViewModel() {
    val state = repository.state

    private var pollingJob: Job? = null

    /**
     * 仅在 Security 页面处于组合树中时启动轮询。
     *
     * ViewModel 的生命周期可能长于一次导航页面；如果把永久轮询放在 init 中，
     * 用户离开 Security 后仍会继续请求 pairing 接口。页面通过 DisposableEffect
     * 在进入/离开时成对调用 startPolling/stopPolling，ViewModel 清理时仍由
     * viewModelScope 负责兜底取消。
     */
    fun startPolling() {
        if (pollingJob?.isActive == true) return
        pollingJob = viewModelScope.launch {
            while (isActive) {
                repository.refresh()
                delay(5_000L)
            }
        }
    }

    fun stopPolling() {
        pollingJob?.cancel()
        pollingJob = null
    }

    fun refresh() = viewModelScope.launch { repository.refresh() }

    fun approve(code: String) = viewModelScope.launch { repository.action("approve", code) }

    fun deny(code: String) = viewModelScope.launch { repository.action("deny", code) }

    override fun onCleared() {
        stopPolling()
        super.onCleared()
    }
}
