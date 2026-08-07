package com.nanobotkt.feature.automations
import dagger.Binds
import dagger.Module
import dagger.hilt.InstallIn
import dagger.hilt.components.SingletonComponent
@Module @InstallIn(SingletonComponent::class) abstract class AutomationsModule{@Binds abstract fun bindRepository(implementation:DefaultAutomationsRepository):AutomationsRepository}
