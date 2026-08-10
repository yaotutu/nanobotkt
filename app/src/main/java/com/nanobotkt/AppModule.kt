package com.nanobotkt

import com.nanobotkt.core.network.GatewayServerUrl
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
}
