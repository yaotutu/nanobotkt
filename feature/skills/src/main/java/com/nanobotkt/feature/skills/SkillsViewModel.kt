package com.nanobotkt.feature.skills

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.nanobotkt.core.model.SkillDetail
import dagger.hilt.android.lifecycle.HiltViewModel
import javax.inject.Inject
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch

/** 详情选择只在当前 Skills 页面有效，不进入 Singleton Repository。 */
private data class SkillDetailState(
    val sessionGeneration: Long,
    val selected: SkillDetail? = null,
    val loading: Boolean = false,
    val error: String? = null,
)

@HiltViewModel
class SkillsViewModel @Inject constructor(private val repository: SkillsRepository) : ViewModel() {
    private val detail =
        MutableStateFlow(
            SkillDetailState(sessionGeneration = repository.state.value.sessionGeneration)
        )
    private var detailJob: Job? = null

    /** Repository reset 后 generation 会变化；组合时立即屏蔽旧详情，即使取消信号和 HTTP 回调 同时到达，也不会把上一账号的详情重新显示出来。 */
    val state: StateFlow<SkillsUiState> =
        combine(repository.state, detail) { repositoryState, detailState ->
                val currentDetail =
                    detailState.takeIf { it.sessionGeneration == repositoryState.sessionGeneration }
                SkillsUiState(
                    skills = repositoryState.skills,
                    selected = currentDetail?.selected,
                    loading = repositoryState.loading,
                    detailLoading = currentDetail?.loading == true,
                    error = currentDetail?.error ?: repositoryState.error,
                )
            }
            .stateIn(
                scope = viewModelScope,
                started = SharingStarted.Eagerly,
                initialValue =
                    SkillsUiState(
                        skills = repository.state.value.skills,
                        loading = repository.state.value.loading,
                        error = repository.state.value.error,
                    ),
            )

    init {
        refresh()
    }

    fun refresh() {
        viewModelScope.launch { repository.refresh() }
    }

    fun select(name: String) {
        // 新选择先取消旧请求并清空旧详情；Job 取消与 sessionGeneration 双重保护，
        // 分别覆盖同一页面快速切换和 logout 后旧请求迟到两类竞态。
        detailJob?.cancel()
        val expectedSession = repository.state.value.sessionGeneration
        detail.value = SkillDetailState(sessionGeneration = expectedSession, loading = true)
        detailJob =
            viewModelScope.launch {
                try {
                    val selected = repository.loadDetail(name)
                    if (repository.state.value.sessionGeneration != expectedSession) return@launch
                    detail.value =
                        SkillDetailState(sessionGeneration = expectedSession, selected = selected)
                } catch (error: CancellationException) {
                    throw error
                } catch (error: Exception) {
                    if (repository.state.value.sessionGeneration != expectedSession) return@launch
                    detail.value =
                        SkillDetailState(
                            sessionGeneration = expectedSession,
                            error = error.message ?: "skill_detail_failed",
                        )
                }
            }
    }

    fun closeDetail() {
        // 关闭弹窗会使当前详情请求失效，避免迟到响应重新打开已关闭的详情。
        detailJob?.cancel()
        detailJob = null
        detail.value =
            SkillDetailState(sessionGeneration = repository.state.value.sessionGeneration)
    }
}
