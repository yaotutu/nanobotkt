package com.nanobotkt.core.workspace

import com.nanobotkt.core.model.WorkspacesPayload
import kotlinx.coroutines.flow.StateFlow

/**
 * 提供聊天等其他业务能力所需的工作区访问快照。
 *
 * 这个接口只描述“能力”，不暴露 Workspaces feature 的 UI 状态、默认权限编辑等细节。
 * 这样 Chat 只依赖稳定的共享契约，而不会反向依赖 WorkspacesRepository 的界面编排职责。
 */
interface WorkspaceAccessProvider {
    /** 当前服务端工作区快照；退出登录和刷新失败时允许保持为空或上一次成功值。 */
    val workspaces: StateFlow<WorkspacesPayload?>

    /** 请求最新工作区快照。具体加载状态和错误展示由 Workspaces feature 自己负责。 */
    suspend fun refresh()
}
