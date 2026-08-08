package com.nanobotkt.feature.workspaces.di

import com.nanobotkt.feature.workspaces.data.DefaultWorkspacesRepository
import com.nanobotkt.feature.workspaces.data.WorkspacesRepository
import com.nanobotkt.core.workspace.WorkspaceAccessProvider
import dagger.Binds
import dagger.Module
import dagger.hilt.InstallIn
import dagger.hilt.components.SingletonComponent

@Module
@InstallIn(SingletonComponent::class)
abstract class WorkspacesModule {
    /** 对外提供完整的工作区管理能力，供 Workspaces 页面和 App Root 使用。 */
    @Binds
    abstract fun bindRepository(implementation: DefaultWorkspacesRepository): WorkspacesRepository

    /** 只向其他 feature 暴露工作区访问快照，隐藏 Workspaces UI 状态和写操作。 */
    @Binds
    abstract fun bindWorkspaceAccessProvider(implementation: DefaultWorkspacesRepository): WorkspaceAccessProvider
}
