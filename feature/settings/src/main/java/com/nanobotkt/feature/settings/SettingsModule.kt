package com.nanobotkt.feature.settings

import dagger.Binds
import dagger.Module
import dagger.hilt.InstallIn
import dagger.hilt.components.SingletonComponent

@Module
@InstallIn(SingletonComponent::class)
abstract class SettingsModule {
    /** 将 feature 内的默认实现绑定到稳定的 Repository 契约。 */
    @Binds
    abstract fun bindRepository(implementation: DefaultSettingsRepository): SettingsRepository

    /** App 更新功能保持在 Settings feature 内，通过小型契约隔离 Android 与持久化细节。 */
    @Binds
    abstract fun bindAppUpdateRepository(implementation: DefaultAppUpdateRepository): AppUpdateRepository

    @Binds
    abstract fun bindAppUpdateCheckStore(implementation: DataStoreAppUpdateCheckStore): AppUpdateCheckStore

    @Binds
    abstract fun bindAppUpdateStorage(implementation: CacheAppUpdateStorage): AppUpdateStorage

    @Binds
    abstract fun bindAppUpdateTimeSource(implementation: SystemAppUpdateTimeSource): AppUpdateTimeSource

    @Binds
    abstract fun bindAppUpdateInstaller(
        implementation: AndroidAppUpdateInstallCoordinator,
    ): AppUpdateInstallCoordinator
}
