package com.nanobotkt.feature.channels

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.nanobotkt.core.model.ChannelConnectPayload
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import javax.inject.Inject

@HiltViewModel
class ChannelsViewModel @Inject constructor(
    private val repository: ChannelsRepository,
) : ViewModel() {
    val state = repository.state

    /**
     * 每个频道实例只允许存在一个连接轮询 Job。
     *
     * Cancel 必须能够取消正在 delay 或 poll 的 connect coroutine，否则取消请求返回后，
     * 原来的轮询仍可能继续访问服务端并覆盖已取消的连接状态。
     */
    private val connectJobs = mutableMapOf<String, Job>()

    init {
        refresh()
    }

    fun refresh() = viewModelScope.launch { repository.refresh() }

    fun enabled(name: String, value: Boolean, instanceId: String? = null) = viewModelScope.launch {
        repository.setEnabled(name, value, instanceId)
    }

    fun configure(
        name: String,
        values: Map<String, String>,
        enable: Boolean? = null,
        instanceId: String? = null,
    ) = viewModelScope.launch {
        repository.configure(name, values, enable, instanceId)
    }

    /** 按官方 WebUI 的两阶段语义保存并启用频道。 */
    fun saveAndEnable(name: String, values: Map<String, String>, instanceId: String? = null) =
        viewModelScope.launch {
            repository.validateAndConfigure(name, values, instanceId)
        }

    fun validate(name: String, values: Map<String, String>, instanceId: String? = null) =
        viewModelScope.launch { repository.validate(name, values, instanceId) }

    fun connect(name: String, instanceId: String? = null) {
        val key = channelConnectionKey(name, instanceId)
        val job = viewModelScope.launch(start = CoroutineStart.LAZY) {
            // 轮询始终使用本次 start 返回的 session_id，不能从共享 state 重新读取其他 channel 的 session。
            var connection = repository.startConnect(name, instanceId) ?: return@launch
            while (isActive && connection.status == "pending") {
                delay(connection.intervalMs?.coerceIn(500, 5_000) ?: 1_500)
                connection = repository.pollConnect(name, connection.sessionId, instanceId) ?: break
            }
        }
        synchronized(connectJobs) {
            // 防止重复点击创建多个同时轮询同一连接的 Job。
            if (connectJobs[key]?.let { !it.isCompleted } == true) return
            connectJobs[key] = job
            job.invokeOnCompletion {
                synchronized(connectJobs) {
                    if (connectJobs[key] === job) connectJobs.remove(key)
                }
            }
        }
        job.start()
    }

    fun cancel(name: String, sessionId: String, instanceId: String? = null) = viewModelScope.launch {
        // 先停止本地轮询，再调用服务端 cancel；否则旧的 poll 可能在 cancel 返回后继续覆盖状态。
        val key = channelConnectionKey(name, instanceId)
        synchronized(connectJobs) { connectJobs[key]?.cancel() }
        repository.cancelConnect(name, sessionId, instanceId)
    }
}
