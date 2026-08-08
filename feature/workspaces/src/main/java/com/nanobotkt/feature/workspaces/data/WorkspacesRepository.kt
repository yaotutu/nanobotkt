package com.nanobotkt.feature.workspaces.data

import com.nanobotkt.core.model.DefaultAccessMode
import com.nanobotkt.core.model.SettingsPayload
import com.nanobotkt.core.model.WorkspacesPayload
import com.nanobotkt.core.network.GatewayApiClient
import com.nanobotkt.core.workspace.WorkspaceAccessProvider
import java.util.concurrent.atomic.AtomicLong
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

interface WorkspacesRepository {
    val state: StateFlow<WorkspacesUiState>

    /** 清理当前会话的工作区快照，并使在途 refresh 失效。 */
    fun reset()

    suspend fun refresh()

    /** 更新服务端保存的默认工作区权限模式，而不是当前会话的 workspace scope。 */
    suspend fun updateDefaultAccessMode(mode: DefaultAccessMode)
    fun clearError()
}

data class WorkspacesUiState(
    val payload: WorkspacesPayload? = null,
    val loading: Boolean = false,
    val error: String? = null,
)

@Singleton
class DefaultWorkspacesRepository @Inject constructor(
    private val api: GatewayApiClient,
) : WorkspacesRepository, WorkspaceAccessProvider {
    private val mutableState = MutableStateFlow(WorkspacesUiState())
    override val state: StateFlow<WorkspacesUiState> = mutableState.asStateFlow()
    // 对外只暴露工作区访问快照，避免其他 feature 依赖本 feature 的 UI 状态模型。
    private val mutableWorkspaces = MutableStateFlow<WorkspacesPayload?>(null)
    override val workspaces: StateFlow<WorkspacesPayload?> = mutableWorkspaces.asStateFlow()
    private val sessionGeneration = AtomicLong(0L)
    private val refreshGeneration = AtomicLong(0L)

    override fun reset() {
        // 旧请求返回后同时检查 session 和 refresh 代次，确保 logout 后不会恢复旧数据。
        sessionGeneration.incrementAndGet()
        refreshGeneration.incrementAndGet()
        mutableState.value = WorkspacesUiState()
        mutableWorkspaces.value = null
    }

    override suspend fun updateDefaultAccessMode(mode: DefaultAccessMode) {
        val expectedSession = sessionGeneration.get()
        val generation = refreshGeneration.incrementAndGet()
        val old = mutableState.value
        if (sessionGeneration.get() != expectedSession || refreshGeneration.get() != generation) return
        mutableState.value = old.copy(loading = true, error = null)
        try {
            // 服务端该接口返回 SettingsPayload；写入完成后重新读取 /api/workspaces，
            // 确保 UI 展示的是服务端重新计算后的 default_scope，而不是本地猜测值。
            api.request<SettingsPayload>(
                path = "/api/settings/network-safety/update",
                deserializer = SettingsPayload.serializer(),
                query = mapOf("webui_default_access_mode" to mode.toWireValue()),
            )
            if (sessionGeneration.get() == expectedSession && refreshGeneration.get() == generation) {
                refresh()
            }
        } catch (error: CancellationException) {
            if (sessionGeneration.get() == expectedSession && refreshGeneration.get() == generation) {
                mutableState.value = old
            }
            throw error
        } catch (error: Exception) {
            if (sessionGeneration.get() == expectedSession && refreshGeneration.get() == generation) {
                mutableState.value = old.copy(
                    loading = false,
                    error = error.message ?: "workspaces_update_failed",
                )
            }
        }
    }

    override suspend fun refresh() {
        val expectedSession = sessionGeneration.get()
        val generation = refreshGeneration.incrementAndGet()
        val old = mutableState.value
        if (sessionGeneration.get() != expectedSession || refreshGeneration.get() != generation) return
        mutableState.value = old.copy(loading = true, error = null)
        try {
            val payload = api.get<WorkspacesPayload>("/api/workspaces")
            if (sessionGeneration.get() == expectedSession && refreshGeneration.get() == generation) {
                mutableState.value = mutableState.value.copy(
                    payload = payload,
                    loading = false,
                    error = null,
                )
                mutableWorkspaces.value = payload
            }
        } catch (error: CancellationException) {
            if (sessionGeneration.get() == expectedSession && refreshGeneration.get() == generation) {
                mutableState.value = old
            }
            throw error
        } catch (error: Exception) {
            if (sessionGeneration.get() == expectedSession && refreshGeneration.get() == generation) {
                mutableState.value = old.copy(
                    loading = false,
                    error = error.message ?: "workspaces_refresh_failed",
                )
            }
        }
    }

    override fun clearError() {
        mutableState.value = mutableState.value.copy(error = null)
    }
}


/** 将 Kotlin 枚举转换成 Nanobot WebUI 网络安全接口使用的稳定字符串。 */
private fun DefaultAccessMode.toWireValue(): String = when (this) {
    DefaultAccessMode.DEFAULT -> "default"
    DefaultAccessMode.FULL -> "full"
}
