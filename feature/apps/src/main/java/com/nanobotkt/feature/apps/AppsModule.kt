package com.nanobotkt.feature.apps
import dagger.Binds
import dagger.Module
import dagger.hilt.InstallIn
import dagger.hilt.components.SingletonComponent
@Module @InstallIn(SingletonComponent::class) abstract class AppsModule{@Binds abstract fun bindRepository(implementation:DefaultAppsRepository):AppsRepository}
