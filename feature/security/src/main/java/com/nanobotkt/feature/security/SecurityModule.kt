package com.nanobotkt.feature.security

import dagger.Binds
import dagger.Module
import dagger.hilt.InstallIn
import dagger.hilt.components.SingletonComponent

@Module
@InstallIn(SingletonComponent::class)
abstract class SecurityModule {
    /** 将 feature 内的默认实现绑定到稳定的 Repository 契约。 */
    @Binds
    abstract fun bindRepository(implementation: DefaultSecurityRepository): SecurityRepository
}
