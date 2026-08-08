package com.nanobotkt.feature.skills

import com.nanobotkt.core.model.SkillDetail
import com.nanobotkt.core.model.SkillsPayload
import com.nanobotkt.core.network.GatewayApiClient
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import java.net.URLEncoder
import java.util.concurrent.atomic.AtomicLong
import javax.inject.Inject
import javax.inject.Singleton

interface SkillsRepository {
    val state: StateFlow<SkillsUiState>

    /** 清理当前会话的技能列表和详情，并使所有在途请求失效。 */
    fun reset()

    suspend fun refresh()
    suspend fun select(name: String)
    fun clearSelection()
}

data class SkillsUiState(
    val skills: SkillsPayload? = null,
    val selected: SkillDetail? = null,
    val loading: Boolean = false,
    val detailLoading: Boolean = false,
    val error: String? = null,
)

@Singleton
class DefaultSkillsRepository @Inject constructor(
    private val api: GatewayApiClient,
) : SkillsRepository {
    private val mutable = MutableStateFlow(SkillsUiState())
    override val state: StateFlow<SkillsUiState> = mutable.asStateFlow()

    private val sessionGeneration = AtomicLong(0L)
    private val selectionGeneration = AtomicLong(0L)
    private val refreshGeneration = AtomicLong(0L)

    override fun reset() {
        // reset 不能只清空 StateFlow；还要提升所有请求代次，防止 logout 前的
        // refresh 或详情响应在稍后返回时重新污染新会话。
        sessionGeneration.incrementAndGet()
        selectionGeneration.incrementAndGet()
        refreshGeneration.incrementAndGet()
        mutable.value = SkillsUiState()
    }

    override suspend fun refresh() {
        val expectedSession = sessionGeneration.get()
        val generation = refreshGeneration.incrementAndGet()
        val oldSkills = mutable.value.skills
        updateIfCurrent(expectedSession, generation) {
            it.copy(loading = true, error = null)
        }
        try {
            val skills = api.get<SkillsPayload>("/api/webui/skills")
            updateIfCurrent(expectedSession, generation) {
                it.copy(skills = skills, loading = false, error = null)
            }
        } catch (error: CancellationException) {
            updateIfCurrent(expectedSession, generation) {
                it.copy(skills = oldSkills, loading = false, error = null)
            }
            throw error
        } catch (error: Exception) {
            updateIfCurrent(expectedSession, generation) {
                it.copy(loading = false, error = error.message ?: "skills_refresh_failed")
            }
        }
    }

    override suspend fun select(name: String) {
        val expectedSession = sessionGeneration.get()
        val generation = selectionGeneration.incrementAndGet()
        // 切换详情时先清掉旧内容，避免旧 Skill 内容和新请求的 loading 状态同时出现。
        updateIfSession(expectedSession) {
            it.copy(selected = null, detailLoading = true, error = null)
        }
        try {
            val encoded = URLEncoder.encode(name, "UTF-8").replace("+", "%20")
            val detail = api.get<SkillDetail>("/api/webui/skills/$encoded")
            if (sessionGeneration.get() != expectedSession || selectionGeneration.get() != generation) return
            updateIfSession(expectedSession) {
                it.copy(selected = detail, detailLoading = false, error = null)
            }
        } catch (error: CancellationException) {
            if (sessionGeneration.get() == expectedSession && selectionGeneration.get() == generation) {
                updateIfSession(expectedSession) { it.copy(detailLoading = false) }
            }
            throw error
        } catch (error: Exception) {
            if (sessionGeneration.get() == expectedSession && selectionGeneration.get() == generation) {
                updateIfSession(expectedSession) {
                    it.copy(detailLoading = false, error = error.message ?: "skill_detail_failed")
                }
            }
        }
    }

    override fun clearSelection() {
        // 关闭详情也会使正在进行的详情请求失效，防止它返回后重新打开详情页。
        selectionGeneration.incrementAndGet()
        // 关闭详情时同时清理详情请求留下的错误，避免旧错误在列表页继续显示，
        // 也避免用户重新打开另一个 Skill 时继承上一条详情请求的失败状态。
        mutable.value = mutable.value.copy(
            selected = null,
            detailLoading = false,
            error = null,
        )
    }

    private fun updateIfCurrent(
        expectedSession: Long,
        expectedRefresh: Long,
        transform: (SkillsUiState) -> SkillsUiState,
    ) {
        if (sessionGeneration.get() == expectedSession && refreshGeneration.get() == expectedRefresh) {
            mutable.value = transform(mutable.value)
        }
    }

    private fun updateIfSession(
        expectedSession: Long,
        transform: (SkillsUiState) -> SkillsUiState,
    ) {
        if (sessionGeneration.get() == expectedSession) {
            mutable.value = transform(mutable.value)
        }
    }
}
