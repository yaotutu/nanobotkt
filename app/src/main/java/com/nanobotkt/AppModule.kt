package com.nanobotkt

import com.nanobotkt.core.network.GatewayServerUrl
import com.nanobotkt.core.session.SessionStateOwner
import com.nanobotkt.feature.apps.AppsRepository
import com.nanobotkt.feature.automations.AutomationsRepository
import com.nanobotkt.feature.channels.ChannelsRepository
import com.nanobotkt.feature.chat.ChatRepository
import com.nanobotkt.feature.security.SecurityRepository
import com.nanobotkt.feature.settings.SettingsRepository
import com.nanobotkt.feature.sidebar.SidebarRepository
import com.nanobotkt.feature.skills.SkillsRepository
import com.nanobotkt.feature.workspaces.data.WorkspacesRepository
import dagger.Module
import dagger.Provides
import dagger.hilt.InstallIn
import dagger.hilt.components.SingletonComponent
import javax.inject.Singleton
import kotlin.jvm.JvmSuppressWildcards

@Module
@InstallIn(SingletonComponent::class)
object AppModule {
    @Provides
    @GatewayServerUrl
    fun provideGatewayServerUrl(): String = BuildConfig.NANOBOT_SERVER_URL

    /**
     * 在组合根集中注册所有跨 feature 的会话状态持有者。
     *
     * 这些 Repository 仍各自拥有清理实现和请求代次保护，但 AppViewModel 不再需要把每个
     * feature 的具体 Repository 都列为构造参数；未来新增需要 logout 清理的 feature 时，
     * 只需在这里接入一次，不会继续扩大 Root ViewModel 的业务耦合面。
     */
    @Provides
    @Singleton
    fun provideSessionStateOwners(
        chatRepository: ChatRepository,
        channelsRepository: ChannelsRepository,
        sidebarRepository: SidebarRepository,
        appsRepository: AppsRepository,
        skillsRepository: SkillsRepository,
        automationsRepository: AutomationsRepository,
        securityRepository: SecurityRepository,
        workspacesRepository: WorkspacesRepository,
        settingsRepository: SettingsRepository,
    ): List<@JvmSuppressWildcards SessionStateOwner> = listOf(
        // 保持退出登录时先清理业务快照、再关闭传输和注销认证的既有顺序。
        SessionStateOwner(chatRepository::reset),
        SessionStateOwner(channelsRepository::reset),
        SessionStateOwner(sidebarRepository::reset),
        SessionStateOwner(appsRepository::reset),
        SessionStateOwner(skillsRepository::reset),
        SessionStateOwner(automationsRepository::reset),
        SessionStateOwner(securityRepository::reset),
        SessionStateOwner(workspacesRepository::reset),
        SessionStateOwner(settingsRepository::reset),
    )
}
