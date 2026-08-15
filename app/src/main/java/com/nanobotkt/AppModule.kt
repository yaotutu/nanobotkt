package com.nanobotkt

import com.nanobotkt.core.network.GatewayServerUrl
import com.nanobotkt.feature.settings.AppReleaseChannel
import com.nanobotkt.feature.settings.AppUpdateBuildInfo
import dagger.Module
import dagger.Provides
import dagger.hilt.InstallIn
import dagger.hilt.components.SingletonComponent

@Module
@InstallIn(SingletonComponent::class)
object AppModule {
    /** Gateway 地址仍由 app 的 BuildConfig 提供，feature/core 不感知构建变体。 */
    @Provides
    @GatewayServerUrl
    fun provideGatewayServerUrl(): String = BuildConfig.NANOBOT_SERVER_URL

    /**
     * Settings feature 只能消费真实构建版本，不能自行读取 version.properties 或维护常量。
     * debug 不是用户发布渠道；本地调试时按正式 Release 查询，dev 构建则只查询 dev-latest。
     */
    @Provides
    fun provideAppUpdateBuildInfo(): AppUpdateBuildInfo = AppUpdateBuildInfo(
        versionName = BuildConfig.VERSION_NAME,
        versionCode = BuildConfig.VERSION_CODE,
        channel = if (BuildConfig.BUILD_TYPE == "dev") {
            AppReleaseChannel.DEV
        } else {
            AppReleaseChannel.RELEASE
        },
    )
}
