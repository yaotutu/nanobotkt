package com.nanobotkt

import androidx.appcompat.app.AppCompatDelegate
import androidx.core.os.LocaleListCompat
import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.nanobotkt.core.persistence.ThemePreference
import com.nanobotkt.core.persistence.UserPreferences
import com.nanobotkt.core.persistence.UserPreferencesRepository
import com.nanobotkt.core.transport.NanobotTransport
import com.nanobotkt.core.transport.TransportState
import com.nanobotkt.feature.auth.AuthSessionRepository
import com.nanobotkt.feature.auth.AuthState
import com.nanobotkt.feature.settings.SETTINGS_SECTION_OVERVIEW
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import javax.inject.Inject

internal enum class AppDestination { CHAT, CONVERSATIONS, WORKSPACES, APPS, SKILLS, AUTOMATIONS, CHANNELS, SECURITY, SETTINGS }

internal data class RootUiState(
    val selectedKey: String? = null,
    val destination: AppDestination = AppDestination.CHAT,
    val draftingNewTopic: Boolean = false,
    val settingsSection: String = SETTINGS_SECTION_OVERVIEW,
    /**
     * 当前非聊天页面的返回目标。
     *
     * Settings Home 打开的独立 feature 页面需要回到 Settings，而聊天页的模型快捷入口
     * 需要直接回到 Chat。把来源写入 SavedStateHandle，保证系统回收进程后返回行为不漂移。
     */
    val returnDestination: AppDestination = AppDestination.CHAT,
)

private const val ROOT_SELECTED_KEY = "root.selectedKey"
private const val ROOT_DESTINATION = "root.destination"
private const val ROOT_DRAFTING_NEW_TOPIC = "root.draftingNewTopic"
private const val ROOT_SETTINGS_SECTION = "root.settingsSection"
private const val ROOT_RETURN_DESTINATION = "root.returnDestination"

internal fun SavedStateHandle.readRootUiState(): RootUiState = RootUiState(
    selectedKey = get(ROOT_SELECTED_KEY),
    destination = get<String>(ROOT_DESTINATION)
        ?.let { saved -> AppDestination.entries.firstOrNull { it.name == saved } }
        ?: AppDestination.CHAT,
    draftingNewTopic = get<Boolean>(ROOT_DRAFTING_NEW_TOPIC) ?: false,
    settingsSection = get<String>(ROOT_SETTINGS_SECTION)
        ?.takeIf(String::isNotBlank)
        ?: SETTINGS_SECTION_OVERVIEW,
    returnDestination = get<String>(ROOT_RETURN_DESTINATION)
        ?.let { saved -> AppDestination.entries.firstOrNull { it.name == saved } }
        ?.takeIf { it == AppDestination.CHAT || it == AppDestination.SETTINGS }
        ?: AppDestination.CHAT,
)

/**
 * 计算根页面返回状态，保持实现为纯函数，便于锁定进程恢复后的导航语义。
 *
 * - Settings 内部详情以 Settings 自身作为 returnDestination，第一次返回只回首页；
 * - Apps、Skills 等独立 feature 以 Settings 作为来源，返回后恢复 Settings Home；
 * - 从 Chat 直接打开的设置详情以 Chat 为来源，不额外经过 Settings Home。
 */
internal fun RootUiState.navigateBackState(): RootUiState = when {
    destination == AppDestination.CHAT -> this
    destination == AppDestination.SETTINGS && returnDestination == AppDestination.SETTINGS ->
        copy(
            settingsSection = SETTINGS_SECTION_OVERVIEW,
            returnDestination = AppDestination.CHAT,
        )
    returnDestination == AppDestination.SETTINGS ->
        copy(
            destination = AppDestination.SETTINGS,
            settingsSection = SETTINGS_SECTION_OVERVIEW,
            returnDestination = AppDestination.CHAT,
        )
    else ->
        copy(
            destination = AppDestination.CHAT,
            returnDestination = AppDestination.CHAT,
        )
}

/**
 * 执行退出登录时的同步清理，并把认证仓库的异步注销排到清理之后。
 *
 * 将这段编排单独抽成无 Android 依赖的函数，既保持 AppViewModel 的真实执行顺序，
 * 也让单元测试能够稳定验证“旧账号状态先失效、认证注销后触发”的不变量，避免为了
 * 测试而 mock 具体的 Android Repository 和 WebSocket Transport 实现。
 */
internal fun scheduleLogoutCleanup(
    scope: CoroutineScope,
    resetRootUiState: () -> Unit,
    resetSessionState: () -> Unit,
    clearAttachments: () -> Unit,
    closeTransport: () -> Unit,
    logout: suspend () -> Unit,
) {
    resetRootUiState()
    resetSessionState()
    clearAttachments()
    closeTransport()
    scope.launch { logout() }
}

private fun SavedStateHandle.writeRootUiState(value: RootUiState) {
    this[ROOT_SELECTED_KEY] = value.selectedKey
    this[ROOT_DESTINATION] = value.destination.name
    this[ROOT_DRAFTING_NEW_TOPIC] = value.draftingNewTopic
    this[ROOT_SETTINGS_SECTION] = value.settingsSection
    this[ROOT_RETURN_DESTINATION] = value.returnDestination.name
}

@HiltViewModel
class AppViewModel @Inject constructor(
    private val authRepository: AuthSessionRepository,
    private val preferencesRepository: UserPreferencesRepository,
    private val transport: NanobotTransport,
    private val savedStateHandle: SavedStateHandle,
    private val sessionCleanup: SessionCleanup,
) : ViewModel() {
    val authState: StateFlow<AuthState> = authRepository.state
    val preferences: StateFlow<UserPreferences> = preferencesRepository.preferences.stateIn(
        viewModelScope,
        SharingStarted.WhileSubscribed(5_000),
        UserPreferences(),
    )
    val transportState: StateFlow<TransportState> = transport.state

    /**
     * 当前认证会话真正使用的 Gateway 入口。
     *
     * Settings 摘要必须展示客户端连接入口，而不能展示服务端 payload 中的内部监听地址；
     * 后者可能是 127.0.0.1 等仅对服务端进程有意义的地址，会误导 Android 端排障。
     */
    val gatewayServerUrl: String
        get() = authRepository.baseUrl

    private val mutableRootUiState = MutableStateFlow(savedStateHandle.readRootUiState())
    internal val rootUiState: StateFlow<RootUiState> = mutableRootUiState.asStateFlow()

    init {
        authRepository.start()
        viewModelScope.launch {
            authRepository.state.collectLatest { state ->
                if (state is AuthState.Ready) {
                    // 必须先发布认证会话边界，再恢复实时连接。Composer/Workspace 的 HTTP
                    // 加载依赖已建立的登录会话；短期 Token 续期由凭据系统内部处理，不再改变 epoch。
                    sessionCleanup.onAuthenticated(state.sessionEpoch)
                    // Ready 是唯一可以激活实时通信的认证边界；Activity 前台事件只负责恢复
                    // 已激活会话，不能在登录页或 logout 后隐式创建连接。
                    transport.connect()
                } else {
                    transport.close()
                }
            }
        }
        viewModelScope.launch {
            preferencesRepository.preferences
                .map { preferences -> preferences.languageTag }
                .distinctUntilChanged()
                .collectLatest(::applyApplicationLocale)
        }
    }

    fun authenticate(secret: String) = viewModelScope.launch { authRepository.authenticate(secret) }

    fun retry() = viewModelScope.launch { authRepository.retry() }

    fun logout() {
        scheduleLogoutCleanup(
            scope = viewModelScope,
            resetRootUiState = ::resetRootUiState,
            // app 组合根显式清理所有登录态 Repository，避免为一次 logout 引入
            // 独立契约模块或隐式 multibinding。
            resetSessionState = sessionCleanup::resetAll,
            // Logout 会改变认证主体；除了关闭连接，还必须清掉旧账号的 chat attach
            // 登记，避免新账号建立 WebSocket 时自动恢复旧账号的会话。
            clearAttachments = transport::clearAttachments,
            closeTransport = transport::close,
            logout = authRepository::logout,
        )
    }
    /** Settings 的手工重连只替换当前登录会话的 Socket，不得借此激活已注销会话。 */
    fun reconnect() = transport.reconnect()
    fun toggleTheme() = viewModelScope.launch {
        val next = if (preferences.value.theme == ThemePreference.DARK) {
            ThemePreference.LIGHT
        } else {
            ThemePreference.DARK
        }
        preferencesRepository.setTheme(next)
    }
    fun onForeground() = transport.resume()
    fun onBackground() = transport.onBackground()
    fun setNetworkAvailable(available: Boolean) = transport.setNetworkAvailable(available)

    /** 打开普通根页面；这些旧入口默认返回聊天页。 */
    internal fun navigate(destination: AppDestination) = updateRootUiState {
        copy(destination = destination, returnDestination = AppDestination.CHAT)
    }

    /** 从聊天页直接进入 Settings Home 或某个快捷设置，返回时仍回到聊天页。 */
    internal fun openSettings(section: String = SETTINGS_SECTION_OVERVIEW) {
        updateRootUiState {
            copy(
                destination = AppDestination.SETTINGS,
                settingsSection = section,
                returnDestination = AppDestination.CHAT,
            )
        }
    }

    /** 从 Settings Home 进入内部设置详情；返回键先回到 Settings Home。 */
    internal fun openSettingsSection(section: String) {
        updateRootUiState {
            copy(
                destination = AppDestination.SETTINGS,
                settingsSection = section,
                returnDestination = AppDestination.SETTINGS,
            )
        }
    }

    /**
     * 从 Settings Home 打开独立 feature 页面。
     *
     * app 继续承担组合根职责，Settings feature 只发出导航事件，不直接依赖 Apps、Skills
     * 等兄弟 feature；同时把返回来源持久化，避免进程恢复后直接跳回 Chat。
     */
    internal fun openSettingsChild(destination: AppDestination) = updateRootUiState {
        // 使用显式白名单，而不是简单排除 Chat/Settings，防止兼容保留的 Conversations
        // 被误当成设置子页面，进而形成“会话列表返回设置”的错误导航层级。
        require(
            destination in
                setOf(
                    AppDestination.WORKSPACES,
                    AppDestination.APPS,
                    AppDestination.SKILLS,
                    AppDestination.AUTOMATIONS,
                    AppDestination.CHANNELS,
                    AppDestination.SECURITY,
                ),
        ) {
            "Settings child must be a destination exposed by Settings Home."
        }
        copy(
            destination = destination,
            settingsSection = SETTINGS_SECTION_OVERVIEW,
            returnDestination = AppDestination.SETTINGS,
        )
    }

    /** 设置详情之间的内部跳转保留最初进入来源。 */
    internal fun setSettingsSection(section: String) {
        updateRootUiState { copy(settingsSection = section) }
    }

    /** 依据持久化的来源执行系统返回和页面返回，形成 Chat → Settings → Child 的稳定层级。 */
    internal fun navigateBack() = updateRootUiState { navigateBackState() }

    internal fun selectSession(key: String) = updateRootUiState {
        copy(selectedKey = key, draftingNewTopic = false)
    }

    internal fun beginNewTopic() = updateRootUiState {
        copy(selectedKey = null, draftingNewTopic = true)
    }

    internal fun updateSessionSelection(selection: SessionSelection) = updateRootUiState {
        copy(
            selectedKey = selection.selectedKey,
            draftingNewTopic = selection.draftingNewTopic,
        )
    }

    private fun resetRootUiState() {
        val reset = RootUiState()
        mutableRootUiState.value = reset
        savedStateHandle.writeRootUiState(reset)
    }

    private inline fun updateRootUiState(transform: RootUiState.() -> RootUiState) {
        val updated = mutableRootUiState.value.transform()
        if (updated == mutableRootUiState.value) return
        mutableRootUiState.value = updated
        savedStateHandle.writeRootUiState(updated)
    }

    private fun applyApplicationLocale(languageTag: String?) {
        val requested = LocaleListCompat.forLanguageTags(languageTag.orEmpty())
        if (AppCompatDelegate.getApplicationLocales().toLanguageTags() != requested.toLanguageTags()) {
            AppCompatDelegate.setApplicationLocales(requested)
        }
    }
}
