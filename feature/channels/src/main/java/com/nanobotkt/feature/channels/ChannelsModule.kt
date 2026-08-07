package com.nanobotkt.feature.channels
import dagger.Binds
import dagger.Module
import dagger.hilt.InstallIn
import dagger.hilt.components.SingletonComponent
@Module @InstallIn(SingletonComponent::class) abstract class ChannelsModule{@Binds abstract fun bindRepository(implementation:DefaultChannelsRepository):ChannelsRepository}
