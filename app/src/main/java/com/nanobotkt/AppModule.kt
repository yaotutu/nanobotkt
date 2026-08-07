package com.nanobotkt

import com.nanobotkt.core.network.GatewayServerUrl
import dagger.Module
import dagger.Provides
import dagger.hilt.InstallIn
import dagger.hilt.components.SingletonComponent

@Module
@InstallIn(SingletonComponent::class)
object AppModule {
    @Provides
    @GatewayServerUrl
    fun provideGatewayServerUrl(): String = BuildConfig.NANOBOT_SERVER_URL
}
