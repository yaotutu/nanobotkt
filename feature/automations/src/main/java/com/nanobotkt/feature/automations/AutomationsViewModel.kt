package com.nanobotkt.feature.automations

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.nanobotkt.core.model.AutomationUpdatePayload
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import javax.inject.Inject

@HiltViewModel
class AutomationsViewModel @Inject constructor(
    private val repository: AutomationsRepository,
) : ViewModel() {
    val state = repository.state

    init {
        // 首次加载由 ViewModel 发起；持续轮询放在 Screen 的可见生命周期内，避免离开页面后继续请求。
        refresh()
    }

    fun refresh() = viewModelScope.launch { repository.refresh() }

    fun action(action: String, id: String) = viewModelScope.launch {
        repository.action(action, id)
        if (action == "run") {
            // 对齐 WebUI 的异步任务反馈节奏，补两次短延迟刷新，不触发额外远程写操作。
            delay(1_200)
            repository.refresh()
            delay(2_800)
            repository.refresh()
        }
    }

    /** 将完整的编辑 payload 交给 Repository，避免 UI 只能修改名称。 */
    fun update(id: String, values: AutomationUpdatePayload) = viewModelScope.launch {
        repository.update(id, values)
    }

    /** 兼容已有调用方；新 UI 使用 update() 支持 message 和 schedule。 */
    fun rename(id: String, name: String) = update(
        id,
        AutomationUpdatePayload(name = name),
    )
}
