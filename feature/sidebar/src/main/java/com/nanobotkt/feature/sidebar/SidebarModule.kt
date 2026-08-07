package com.nanobotkt.feature.sidebar

import dagger.Binds
import dagger.Module
import dagger.hilt.InstallIn
import dagger.hilt.components.SingletonComponent

@Module
@InstallIn(SingletonComponent::class)
abstract class SidebarModule {
    @Binds abstract fun bindSidebarRepository(implementation: DefaultSidebarRepository): SidebarRepository
}
