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
import com.nanobotkt.feature.auth.ServerConnectionError
import com.nanobotkt.feature.auth.ServerSwitchResult
import com.nanobotkt.feature.settings.SETTINGS_SECTION_OVERVIEW
import com.nanobotkt.feature.settings.SETTINGS_SECTION_SYSTEM
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.CancellationException
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

/** 成功切换服务器后保留 Gateway & System 页面，但彻底断开旧聊天选择。 */
internal fun RootUiState.afterServerSwitch(): RootUiState = copy(
    selectedKey = null,
    destination = AppDestination.SETTINGS,
    draftingNewTopic = true,
    settingsSection = SETTINGS_SECTION_SYSTEM,
    returnDestination = AppDestination.CHAT,
)

/** Settings 页面消费的轻量切换状态；Secret 从不进入 UI state 或 SavedStateHandle。 */
internal data class GatewaySwitchUiState(
    val switching: Boolean = false,
    val feedback: String? = null,
    val succeeded: Boolean = false,
)

/**
 * Settings 离开或重新进入 Gateway 页面时，只清理由上一轮产生的静态反馈。
 *
 * 验证中的状态必须原样保留：用户可能在候选请求尚未结束时返回 Settings Home，
 * 如果此时把 switching 清掉，就会允许第二次提交与首个请求竞争持久化和清理顺序。
 */
internal fun GatewaySwitchUiState.dismissFeedbackIfIdle(): GatewaySwitchUiState =
    if (switching) this else GatewaySwitchUiState()

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
 * 候选服务器已通过验证后，同步清理旧服务器关联状态。
 *
 * 顺序属于安全契约：先提升业务 Repository 代次，再删除附件恢复登记并关闭旧 Socket；
 * Root 最后切回 Gateway & System。认证仓库随后才发布新端点的 Ready。
 */
internal fun runServerSwitchCleanup(
    resetSessionState: () -> Unit,
    clearAttachments: () -> Unit,
    closeTransport: () -> Unit,
    resetRootUiState: () -> Unit,
) {
    resetSessionState()
    clearAttachments()
    closeTransport()
    resetRootUiState()
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
    private val mutableGatewaySwitchState = MutableStateFlow(GatewaySwitchUiState())
    internal val gatewaySwitchState: StateFlow<GatewaySwitchUiState> =
        mutableGatewaySwitchState.asStateFlow()

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

    fun authenticate(serverUrl: String, secret: String) =
        viewModelScope.launch { authRepository.authenticate(serverUrl, secret) }

    fun retry() = viewModelScope.launch { authRepository.retry() }

    /** 不可达页面回到地址编辑表单，不删除当前服务器已保存的 Secret。 */
    fun changeServer() = authRepository.showAuthentication()

    /**
     * 验证候选 Gateway，并只在验证成功后同步清理旧服务器状态和激活新连接。
     * 重复点击由 switching guard 拦截，避免两个候选请求竞争最后一次持久化结果。
     */
    fun switchServer(serverUrl: String, secret: String) {
        if (mutableGatewaySwitchState.value.switching) return
        mutableGatewaySwitchState.value = GatewaySwitchUiState(switching = true)
        viewModelScope.launch {
            try {
                val result = authRepository.switchServer(serverUrl, secret) {
                    runServerSwitchCleanup(
                        resetSessionState = sessionCleanup::resetAll,
                        clearAttachments = transport::clearAttachments,
                        closeTransport = transport::close,
                        resetRootUiState = ::resetRootUiStateForServerSwitch,
                    )
                }
                mutableGatewaySwitchState.value = when (result) {
                    is ServerSwitchResult.Success -> GatewaySwitchUiState(
                        feedback = "Connected to ${result.serverUrl}",
                        succeeded = true,
                    )
                    is ServerSwitchResult.Failure -> GatewaySwitchUiState(
                        feedback = result.error.gatewaySwitchMessage(),
                    )
                }
            } catch (error: CancellationException) {
                // Auth 层必须继续收到取消信号以停止候选请求；同时先撤销 switching，避免
                // 非 ViewModel 销毁场景下页面永久停在“Validating”且无法再次提交。
                mutableGatewaySwitchState.value = GatewaySwitchUiState(
                    feedback = "Server validation was cancelled.",
                )
                throw error
            }
        }
    }
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

    /** 打开普通根页面；这些旧入口默认返回聊天页。 */
    internal fun navigate(destination: AppDestination) = updateRootUiState {
        copy(destination = destination, returnDestination = AppDestination.CHAT)
    }

    /** 从聊天页直接进入 Settings Home 或某个快捷设置，返回时仍回到聊天页。 */
    internal fun openSettings(section: String = SETTINGS_SECTION_OVERVIEW) {
        dismissGatewaySwitchFeedbackOnEntry(section)
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
        dismissGatewaySwitchFeedbackOnEntry(section)
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
        dismissGatewaySwitchFeedbackOnEntry(section)
        updateRootUiState { copy(settingsSection = section) }
    }

    /** 依据持久化的来源执行系统返回和页面返回，形成 Chat → Settings → Child 的稳定层级。 */
    internal fun navigateBack() {
        // 失败或成功提示只描述当前这次 Gateway 页面访问。离页后立即清除，避免用户
        // 再次打开页面时，在已恢复的当前地址和空 Secret 下看到上一轮候选错误。
        if (
            mutableRootUiState.value.destination == AppDestination.SETTINGS &&
            mutableRootUiState.value.settingsSection == SETTINGS_SECTION_SYSTEM
        ) {
            dismissGatewaySwitchFeedbackIfIdle()
        }
        updateRootUiState { navigateBackState() }
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

    private fun resetRootUiStateForServerSwitch() {
        val reset = mutableRootUiState.value.afterServerSwitch()
        mutableRootUiState.value = reset
        savedStateHandle.writeRootUiState(reset)
    }

    /**
     * 用户主动进入 Gateway 页面时丢弃已结束请求的旧反馈。
     *
     * 若请求仍在验证中则保留 switching，页面会继续显示进行态并等待唯一请求结束；
     * 成功切换通过 resetRootUiStateForServerSwitch 进入 System，不走这里，因此不会把
     * 刚产生的成功提示提前清掉。
     */
    private fun dismissGatewaySwitchFeedbackOnEntry(section: String) {
        if (section == SETTINGS_SECTION_SYSTEM) dismissGatewaySwitchFeedbackIfIdle()
    }

    private fun dismissGatewaySwitchFeedbackIfIdle() {
        val current = mutableGatewaySwitchState.value
        val updated = current.dismissFeedbackIfIdle()
        if (updated != current) mutableGatewaySwitchState.value = updated
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

/** 认证错误到 Settings 反馈的稳定映射；不把底层异常或 Secret 暴露给 UI。 */
private fun ServerConnectionError.gatewaySwitchMessage(): String = when (this) {
    is ServerConnectionError.InvalidAddress -> when (reason) {
        com.nanobotkt.core.network.GatewayServerAddressError.EMPTY -> "Enter a server address."
        com.nanobotkt.core.network.GatewayServerAddressError.MISSING_SCHEME ->
            "Include http:// or https:// in the server address."
        com.nanobotkt.core.network.GatewayServerAddressError.UNSUPPORTED_SCHEME ->
            "Only http:// and https:// server addresses are supported."
        com.nanobotkt.core.network.GatewayServerAddressError.EMBEDDED_CREDENTIALS ->
            "Do not include credentials in the server address."
        com.nanobotkt.core.network.GatewayServerAddressError.QUERY_NOT_ALLOWED,
        com.nanobotkt.core.network.GatewayServerAddressError.FRAGMENT_NOT_ALLOWED,
        -> "Query parameters and fragments are not allowed in the server address."
        com.nanobotkt.core.network.GatewayServerAddressError.INVALID_URL,
        com.nanobotkt.core.network.GatewayServerAddressError.MISSING_HOST,
        -> "Enter a valid server address."
    }
    ServerConnectionError.AuthenticationRequired ->
        "The server rejected this Bootstrap Secret."
    ServerConnectionError.Timeout -> "The server validation timed out."
    ServerConnectionError.NetworkUnavailable ->
        "Could not reach this server. The current connection was kept."
    ServerConnectionError.HtmlResponse ->
        "This address returned a web page instead of a Gateway response."
    ServerConnectionError.NonJsonResponse ->
        "This address did not return a valid Gateway response."
    is ServerConnectionError.Http ->
        // 候选服务器的响应正文是不可信输入，可能反射请求头甚至 Secret；UI 只显示状态码。
        "Server validation failed with HTTP $status."
    ServerConnectionError.StorageFailure ->
        "The server was validated, but the connection settings could not be saved."
    ServerConnectionError.Cancelled -> "Server switch was cancelled."
    // 未分类异常同样不直接展示 message，避免第三方库把 URL、响应片段或凭据带到屏幕。
    is ServerConnectionError.Unknown -> "Could not validate this server."
}
