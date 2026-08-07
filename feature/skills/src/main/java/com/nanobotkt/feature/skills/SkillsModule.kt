package com.nanobotkt.feature.skills
import dagger.Binds
import dagger.Module
import dagger.hilt.InstallIn
import dagger.hilt.components.SingletonComponent
@Module @InstallIn(SingletonComponent::class) abstract class SkillsModule{@Binds abstract fun bindRepository(implementation:DefaultSkillsRepository):SkillsRepository}
