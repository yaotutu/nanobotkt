package com.nanobotkt

import com.nanobotkt.core.persistence.ComposerDraftStore
import com.nanobotkt.feature.apps.AppsRepository
import com.nanobotkt.feature.automations.AutomationsRepository
import com.nanobotkt.feature.channels.ChannelsRepository
import com.nanobotkt.feature.chat.ChatRepository
import com.nanobotkt.feature.security.SecurityRepository
import com.nanobotkt.feature.settings.SettingsRepository
import com.nanobotkt.feature.sidebar.SidebarRepository
import com.nanobotkt.feature.skills.SkillsRepository
import com.nanobotkt.feature.workspaces.data.WorkspacesRepository
import javax.inject.Inject
import javax.inject.Singleton

/**
 * 在应用组合根集中清理所有与登录主体绑定的业务状态。
 *
 * 这里刻意使用显式依赖和固定调用顺序，而不是为单次 logout 引入跨模块契约、
 * Hilt multibinding 或通用生命周期框架。Repository 继续各自负责提升请求代次，
 * 本协调器只负责保证退出登录时不会遗漏某个 feature 的内存快照。
 */
@Singleton
class SessionCleanup @Inject constructor(
    private val chatRepository: ChatRepository,
    private val channelsRepository: ChannelsRepository,
    private val sidebarRepository: SidebarRepository,
    private val appsRepository: AppsRepository,
    private val skillsRepository: SkillsRepository,
    private val automationsRepository: AutomationsRepository,
    private val securityRepository: SecurityRepository,
    private val workspacesRepository: WorkspacesRepository,
    private val settingsRepository: SettingsRepository,
    private val composerDraftStore: ComposerDraftStore,
) {
    /**
     * 在认证状态进入 Ready 后启动需要 Token 的会话级加载。
     *
     * 当前只有 Chat 的 Composer 目录需要显式入口；其他 feature 仍由各自页面触发刷新。
     * 把调用保留在 app 组合根，避免 feature:auth 反向依赖 feature:chat。
     */
    fun onAuthenticated(sessionEpoch: Long) {
        chatRepository.onAuthenticated(sessionEpoch)
    }

    /**
     * 删除与当前认证主体绑定的持久化 Composer Draft。
     *
     * 该操作必须在认证仓库真正 logout 前完成：数据库中的消息正文、引用和附件 data URL 都属于
     * 当前账号的本地私有数据，若保留到下一账号会造成跨账号内容泄漏。方法保持 suspend，避免在
     * 主线程使用 runBlocking；调用方通过 finally 保证即使数据库清理失败也仍会完成认证注销。
     */
    suspend fun clearPersistedChatInput() {
        composerDraftStore.deleteAll()
    }

    /**
     * 同步使旧会话的全部业务状态失效。
     *
     * reset() 不能被异步调度：认证注销开始前必须先提升所有 Repository 的会话代次，
     * 否则 logout 前已经发出的慢请求可能在新账号登录后把旧账号数据写回 StateFlow。
     */
    fun resetAll() {
        chatRepository.reset()
        channelsRepository.reset()
        sidebarRepository.reset()
        appsRepository.reset()
        skillsRepository.reset()
        automationsRepository.reset()
        securityRepository.reset()
        workspacesRepository.reset()
        settingsRepository.reset()
    }
}
