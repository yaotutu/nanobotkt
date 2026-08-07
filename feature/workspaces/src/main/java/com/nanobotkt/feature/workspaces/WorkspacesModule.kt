package com.nanobotkt.feature.workspaces
import dagger.Binds
import dagger.Module
import dagger.hilt.InstallIn
import dagger.hilt.components.SingletonComponent
@Module @InstallIn(SingletonComponent::class) abstract class WorkspacesModule { @Binds abstract fun bindRepository(implementation: DefaultWorkspacesRepository): WorkspacesRepository }
