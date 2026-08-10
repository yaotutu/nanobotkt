package com.nanobotkt.feature.skills

import com.nanobotkt.core.model.SkillDetail
import com.nanobotkt.core.model.SkillsPayload
import com.nanobotkt.core.network.GatewayApiClient
import java.net.URLEncoder
import java.util.concurrent.atomic.AtomicLong
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * Skills 的数据访问边界。
 *
 * Repository 只持有可跨页面复用的技能列表和登录会话代次；当前选中的详情属于页面状态， 由 [SkillsViewModel] 管理。这样关闭弹窗、切换选择等纯 UI 行为不会反向修改
 * Singleton。
 */
interface SkillsRepository {
    val state: StateFlow<SkillsRepositoryState>

    /** 清理当前登录主体的列表快照，并使 logout 前发起的刷新结果失效。 */
    fun reset()

    suspend fun refresh()

    /** 按名称读取详情；调用方负责决定结果是否仍属于当前页面选择。 */
    suspend fun loadDetail(name: String): SkillDetail
}

/** Repository 公开的数据状态不包含页面选择，`sessionGeneration` 仅用于隔离登录会话。 */
data class SkillsRepositoryState(
    val skills: SkillsPayload? = null,
    val loading: Boolean = false,
    val error: String? = null,
    val sessionGeneration: Long = 0L,
)

/** Skills 页面最终渲染状态，由列表数据和 ViewModel 的详情状态组合得到。 */
data class SkillsUiState(
    val skills: SkillsPayload? = null,
    val selected: SkillDetail? = null,
    val loading: Boolean = false,
    val detailLoading: Boolean = false,
    val error: String? = null,
)

@Singleton
class DefaultSkillsRepository @Inject constructor(private val api: GatewayApiClient) :
    SkillsRepository {
    private val mutable = MutableStateFlow(SkillsRepositoryState())
    override val state: StateFlow<SkillsRepositoryState> = mutable.asStateFlow()

    private val sessionGeneration = AtomicLong(0L)
    private val refreshGeneration = AtomicLong(0L)

    override fun reset() {
        // StateFlow 清空和代次提升必须是同一个同步动作。旧 refresh 即使随后成功或失败，
        // 也无法跨过 expectedSession/expectedRefresh 检查污染新登录主体。
        val nextSession = sessionGeneration.incrementAndGet()
        refreshGeneration.incrementAndGet()
        mutable.value = SkillsRepositoryState(sessionGeneration = nextSession)
    }

    override suspend fun refresh() {
        val expectedSession = sessionGeneration.get()
        val expectedRefresh = refreshGeneration.incrementAndGet()
        val oldSkills = mutable.value.skills
        updateIfCurrent(expectedSession, expectedRefresh) { it.copy(loading = true, error = null) }
        try {
            val skills = api.get<SkillsPayload>("/api/webui/skills")
            updateIfCurrent(expectedSession, expectedRefresh) {
                it.copy(skills = skills, loading = false, error = null)
            }
        } catch (error: CancellationException) {
            // 主动取消刷新不应留下永久 loading，也不应丢弃上一次成功快照。
            updateIfCurrent(expectedSession, expectedRefresh) {
                it.copy(skills = oldSkills, loading = false, error = null)
            }
            throw error
        } catch (error: Exception) {
            updateIfCurrent(expectedSession, expectedRefresh) {
                it.copy(loading = false, error = error.message ?: "skills_refresh_failed")
            }
        }
    }

    override suspend fun loadDetail(name: String): SkillDetail {
        // Skill 名称是单个 path segment；不能让斜杠或空格改变 Gateway 路由边界。
        val encoded = URLEncoder.encode(name, "UTF-8").replace("+", "%20")
        return api.get("/api/webui/skills/$encoded")
    }

    private fun updateIfCurrent(
        expectedSession: Long,
        expectedRefresh: Long,
        transform: (SkillsRepositoryState) -> SkillsRepositoryState,
    ) {
        if (
            sessionGeneration.get() == expectedSession && refreshGeneration.get() == expectedRefresh
        ) {
            mutable.value = transform(mutable.value)
        }
    }
}
