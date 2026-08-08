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
import com.nanobotkt.feature.apps.AppsRepository
import com.nanobotkt.feature.automations.AutomationsRepository
import com.nanobotkt.feature.chat.ChatRepository
import com.nanobotkt.feature.channels.ChannelsRepository
import com.nanobotkt.feature.security.SecurityRepository
import com.nanobotkt.feature.skills.SkillsRepository
import com.nanobotkt.feature.settings.SETTINGS_SECTION_OVERVIEW
import com.nanobotkt.feature.settings.SettingsRepository
import com.nanobotkt.feature.sidebar.SidebarRepository
import com.nanobotkt.feature.workspaces.data.WorkspacesRepository
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

internal enum class AppDestination { CHAT, WORKSPACES, APPS, SKILLS, AUTOMATIONS, CHANNELS, SECURITY, SETTINGS }

internal data class RootUiState(
    val selectedKey: String? = null,
    val destination: AppDestination = AppDestination.CHAT,
    val draftingNewTopic: Boolean = false,
    val settingsSection: String = SETTINGS_SECTION_OVERVIEW,
)

private const val ROOT_SELECTED_KEY = "root.selectedKey"
private const val ROOT_DESTINATION = "root.destination"
private const val ROOT_DRAFTING_NEW_TOPIC = "root.draftingNewTopic"
private const val ROOT_SETTINGS_SECTION = "root.settingsSection"

internal fun SavedStateHandle.readRootUiState(): RootUiState = RootUiState(
    selectedKey = get(ROOT_SELECTED_KEY),
    destination = get<String>(ROOT_DESTINATION)
        ?.let { saved -> AppDestination.entries.firstOrNull { it.name == saved } }
        ?: AppDestination.CHAT,
    draftingNewTopic = get<Boolean>(ROOT_DRAFTING_NEW_TOPIC) ?: false,
    settingsSection = get<String>(ROOT_SETTINGS_SECTION)
        ?.takeIf(String::isNotBlank)
        ?: SETTINGS_SECTION_OVERVIEW,
)

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
    resetRepositories: List<() -> Unit>,
    clearAttachments: () -> Unit,
    closeTransport: () -> Unit,
    logout: suspend () -> Unit,
) {
    resetRootUiState()
    resetRepositories.forEach { reset -> reset() }
    clearAttachments()
    closeTransport()
    scope.launch { logout() }
}

private fun SavedStateHandle.writeRootUiState(value: RootUiState) {
    this[ROOT_SELECTED_KEY] = value.selectedKey
    this[ROOT_DESTINATION] = value.destination.name
    this[ROOT_DRAFTING_NEW_TOPIC] = value.draftingNewTopic
    this[ROOT_SETTINGS_SECTION] = value.settingsSection
}

@HiltViewModel
class AppViewModel @Inject constructor(
    private val authRepository: AuthSessionRepository,
    private val preferencesRepository: UserPreferencesRepository,
    private val transport: NanobotTransport,
    private val chatRepository: ChatRepository,
    private val channelsRepository: ChannelsRepository,
    private val appsRepository: AppsRepository,
    private val skillsRepository: SkillsRepository,
    private val automationsRepository: AutomationsRepository,
    private val securityRepository: SecurityRepository,
    private val workspacesRepository: WorkspacesRepository,
    private val sidebarRepository: SidebarRepository,
    private val settingsRepository: SettingsRepository,
    private val savedStateHandle: SavedStateHandle,
) : ViewModel() {
    val authState: StateFlow<AuthState> = authRepository.state
    val preferences: StateFlow<UserPreferences> = preferencesRepository.preferences.stateIn(
        viewModelScope,
        SharingStarted.WhileSubscribed(5_000),
        UserPreferences(),
    )
    val transportState: StateFlow<TransportState> = transport.state

    private val mutableRootUiState = MutableStateFlow(savedStateHandle.readRootUiState())
    internal val rootUiState: StateFlow<RootUiState> = mutableRootUiState.asStateFlow()

    init {
        authRepository.start()
        viewModelScope.launch {
            authRepository.state.collectLatest { state ->
                if (state is AuthState.Ready) transport.resume() else transport.close()
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
            resetRepositories = listOf(
                // 先清理长生命周期 Repository，再注销认证，避免旧账号的异步响应在
                // logout 之后重新写回 Chat/Sidebar/Settings 状态。
                chatRepository::reset,
                channelsRepository::reset,
                sidebarRepository::reset,
                // 这些 Repository 都是 Singleton，必须在 logout 时同步失效当前会话；
                // 否则旧账号的在途请求可能在退出后重新填充对应页面。
                appsRepository::reset,
                skillsRepository::reset,
                automationsRepository::reset,
                securityRepository::reset,
                workspacesRepository::reset,
                settingsRepository::reset,
            ),
            // Logout 会改变认证主体；除了关闭连接，还必须清掉旧账号的 chat attach
            // 登记，避免新账号建立 WebSocket 时自动恢复旧账号的会话。
            clearAttachments = transport::clearAttachments,
            closeTransport = transport::close,
            logout = authRepository::logout,
        )
    }
    fun reconnect() = transport.resume()
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

    internal fun navigate(destination: AppDestination) = updateRootUiState {
        copy(destination = destination)
    }

    internal fun openSettings(section: String) = updateRootUiState {
        copy(destination = AppDestination.SETTINGS, settingsSection = section)
    }

    internal fun setSettingsSection(section: String) = updateRootUiState {
        copy(settingsSection = section)
    }

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
