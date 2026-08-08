package com.nanobotkt.feature.automations

import dagger.Binds
import dagger.Module
import dagger.hilt.InstallIn
import dagger.hilt.components.SingletonComponent

@Module
@InstallIn(SingletonComponent::class)
abstract class AutomationsModule {
    /** 将 feature 内的默认实现绑定到稳定的 Repository 契约。 */
    @Binds
    abstract fun bindRepository(implementation: DefaultAutomationsRepository): AutomationsRepository
}
