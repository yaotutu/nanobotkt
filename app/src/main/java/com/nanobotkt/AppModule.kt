package com.nanobotkt

import com.nanobotkt.core.network.GatewayServerUrl
import dagger.Module
import dagger.Provides
import dagger.hilt.InstallIn
import dagger.hilt.components.SingletonComponent

@Module
@InstallIn(SingletonComponent::class)
object AppModule {
    /** BuildConfig 只提供首次运行默认值；认证后可由用户选择的 Gateway 覆盖。 */
    @Provides
    @GatewayServerUrl
    fun provideGatewayServerUrl(): String = BuildConfig.NANOBOT_SERVER_URL
}
