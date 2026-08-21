package com.nanobotkt.feature.settings

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.nanobotkt.core.model.ApiServicePayload
import com.nanobotkt.core.persistence.DensityPreference
import com.nanobotkt.core.persistence.FileEditDisplay
import com.nanobotkt.core.persistence.ThemePreference
import com.nanobotkt.core.persistence.UserPreferences
import com.nanobotkt.core.persistence.UserPreferencesRepository
import dagger.hilt.android.lifecycle.HiltViewModel
import javax.inject.Inject
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.receiveAsFlow
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch

@HiltViewModel
class SettingsViewModel @Inject constructor(
    private val repository: SettingsRepository,
    private val preferences: UserPreferencesRepository,
    private val appUpdateRepository: AppUpdateRepository,
) : ViewModel() {
    val state = repository.state
    val appearance = preferences.preferences.stateIn(
        scope = viewModelScope,
        started = SharingStarted.WhileSubscribed(5_000),
        initialValue = UserPreferences(),
    )

    /**
     * 更新业务状态由 Repository 单向维护；对话框显隐属于 Settings 的展示状态，
     * 放在 ViewModel 中可跨旋转保留，且不会让 Composable 自行启动网络或下载任务。
     */
    val appUpdateState = appUpdateRepository.state
    private val mutableAppUpdateDialogVisible = MutableStateFlow(false)
    val appUpdateDialogVisible = mutableAppUpdateDialogVisible.asStateFlow()

    /**
     * 安装权限页和系统安装器属于一次性导航效果。使用带缓冲的 Channel 可覆盖旋转切换期间
     * 短暂没有 collector 的窗口，同时不会像持久状态那样在重组后重复启动系统 Activity。
     */
    private val appUpdateEffectChannel = Channel<AppUpdateEffect>(capacity = Channel.BUFFERED)
    val appUpdateEffects = appUpdateEffectChannel.receiveAsFlow()

    fun refresh() = viewModelScope.launch { repository.refresh() }
    fun update(update: SettingsUpdate) = viewModelScope.launch { repository.update(update) }
    fun provider(update: ProviderUpdate) = viewModelScope.launch { repository.updateProvider(update) }
    fun createModelConfiguration(create: ModelConfigurationCreate) = viewModelScope.launch {
        repository.createModelConfiguration(create)
    }
    fun updateModelConfiguration(update: ModelConfigurationUpdate) = viewModelScope.launch {
        repository.updateModelConfiguration(update)
    }
    fun deleteModelConfiguration(name: String) = viewModelScope.launch {
        repository.deleteModelConfiguration(name)
    }
    fun migrateModelConfigurations() = viewModelScope.launch {
        repository.migrateModelConfigurations()
    }

    fun updateModelCallOrder(update: ModelCallOrderUpdate) = viewModelScope.launch {
        repository.updateModelCallOrder(update)
    }
    fun createProvider(create: CustomProviderCreate) = viewModelScope.launch {
        repository.createProvider(create)
    }
    fun providerModels(name: String) = viewModelScope.launch { repository.providerModels(name) }
    fun oauth(name: String) = viewModelScope.launch { repository.oauthLogin(name) }
    fun oauthComplete(name: String, flowId: String, code: String?) = viewModelScope.launch {
        repository.oauthComplete(name, flowId, code)
    }
    fun oauthLogout(name: String) = viewModelScope.launch { repository.oauthLogout(name) }
    fun checkVersion() = viewModelScope.launch { repository.checkVersion() }

    /** 进入 Settings 时执行轻量自动检查；Repository 负责一天一次节流和静默失败。 */
    fun autoCheckAppUpdate() = viewModelScope.launch {
        appUpdateRepository.check(manual = false)
    }

    /**
     * 用户点击“检查更新”时先展示对话框，再按当前稳定状态决定是否发起真实请求。
     * 已发现更新、正在下载或已下载时只恢复对应界面，避免重复请求和重复下载。
     */
    fun openAppUpdate() {
        mutableAppUpdateDialogVisible.value = true
        when (val status = appUpdateState.value.status) {
            AppUpdateStatus.Idle,
            AppUpdateStatus.UpToDate,
            -> checkAppUpdate()
            is AppUpdateStatus.Error -> if (status.retryAction == AppUpdateRetryAction.CHECK) {
                checkAppUpdate()
            }
            else -> Unit
        }
    }

    fun checkAppUpdate() = viewModelScope.launch {
        appUpdateRepository.check(manual = true)
    }

    fun downloadAppUpdate() = viewModelScope.launch {
        appUpdateRepository.download()
    }

    /**
     * 429/403 限流兜底只由 Error 状态中的显式按钮触发。Repository 会再次校验状态并固定下载
     * dev-latest；下载成功后立即复用正常安装分发，仍由系统权限页和安装器完成最终用户确认。
     * 下载失败时 requestInstall() 返回 null，因此不会用旧文件或不完整文件启动安装器。
     */
    fun forceDownloadLatestDev() = viewModelScope.launch {
        appUpdateRepository.forceDownloadLatestDev()
        dispatchInstallRequest(appUpdateRepository.requestInstall())
    }

    fun installAppUpdate() = viewModelScope.launch {
        dispatchInstallRequest(appUpdateRepository.requestInstall())
    }

    /**
     * 从“允许安装未知应用”设置页返回后重新检查真实权限。若用户未授权则保持 Downloaded，
     * 不立即再次打开设置页形成循环；用户仍可再次点击安装重试。
     */
    fun onInstallPermissionReturned() = viewModelScope.launch {
        when (val request = appUpdateRepository.requestInstall()) {
            is AppUpdateInstallRequest.LaunchInstaller -> dispatchInstallRequest(request)
            is AppUpdateInstallRequest.RequestPermission,
            null,
            -> Unit
        }
    }

    /** 系统安装器返回不代表安装成功；Repository 会恢复为可再次安装的 Downloaded 状态。 */
    fun onPackageInstallerReturned() {
        appUpdateRepository.onInstallerReturned()
    }

    fun retryAppUpdate() {
        when (val status = appUpdateState.value.status) {
            is AppUpdateStatus.Error -> when (status.retryAction) {
                AppUpdateRetryAction.CHECK -> checkAppUpdate()
                AppUpdateRetryAction.DOWNLOAD -> downloadAppUpdate()
                AppUpdateRetryAction.INSTALL -> installAppUpdate()
            }
            else -> Unit
        }
    }

    /** 检查、下载和启动安装器期间锁定对话框，避免用户误以为任务已取消后重复点击。 */
    fun dismissAppUpdateDialog() {
        when (appUpdateState.value.status) {
            AppUpdateStatus.Checking,
            is AppUpdateStatus.Downloading,
            is AppUpdateStatus.Installing,
            -> Unit
            else -> mutableAppUpdateDialogVisible.value = false
        }
    }

    private suspend fun dispatchInstallRequest(request: AppUpdateInstallRequest?) {
        when (request) {
            is AppUpdateInstallRequest.RequestPermission -> {
                appUpdateEffectChannel.send(AppUpdateEffect.RequestInstallPermission(request.intent))
            }
            is AppUpdateInstallRequest.LaunchInstaller -> {
                appUpdateEffectChannel.send(AppUpdateEffect.LaunchPackageInstaller(request.intent))
            }
            null -> Unit
        }
    }

    fun apiService(
        start: Boolean,
        host: String? = null,
        port: Int? = null,
        timeout: Int? = null,
        key: String? = null,
    ) = viewModelScope.launch {
        if (start) {
            val request = resolveApiServiceStartRequest(
                current = state.value.apiService,
                host = host,
                port = port,
                timeout = timeout,
                key = key,
            )
            repository.startApiService(
                host = request.host,
                port = request.port,
                timeout = request.timeout,
                apiKey = request.apiKey,
            )
        } else {
            repository.stopApiService()
        }
    }

    fun network(local: Boolean, mode: String) = viewModelScope.launch {
        repository.networkSafety(local, mode)
    }

    fun updateWebSearch(update: WebSearchSettingsUpdate) = viewModelScope.launch {
        repository.updateWebSearch(update)
    }

    fun updateImage(update: ImageGenerationSettingsUpdate) = viewModelScope.launch {
        repository.updateImage(update)
    }

    fun updateTranscription(update: TranscriptionSettingsUpdate) = viewModelScope.launch {
        repository.updateTranscription(update)
    }

    fun setTheme(value: ThemePreference) = viewModelScope.launch { preferences.setTheme(value) }
    fun setDensity(value: DensityPreference) = viewModelScope.launch { preferences.setDensity(value) }
    fun setLanguage(value: String?) = viewModelScope.launch { preferences.setLanguage(value) }
    fun activity(value: Boolean) = viewModelScope.launch { preferences.setShowActivityDetails(value) }
    fun wrap(value: Boolean) = viewModelScope.launch { preferences.setWrapCode(value) }
    fun logos(value: Boolean) = viewModelScope.launch { preferences.setShowBrandLogos(value) }
    fun fileEdits(value: FileEditDisplay) = viewModelScope.launch { preferences.setFileEditDisplay(value) }
}


internal data class ApiServiceStartRequest(
    val host: String,
    val port: Int,
    val timeout: Int,
    val apiKey: String?,
)

/**
 * 根据网关当前返回的 API service 配置补齐启动参数。
 *
 * System 页面只有 Start/Stop 开关时，不能因为缺少表单输入而把服务
 * 重置为固定的 localhost、默认端口和默认超时；null 表示沿用当前配置，
 * 也让服务端在未提供私密 header 时保留已有 API key。
 */
internal fun resolveApiServiceStartRequest(
    current: ApiServicePayload?,
    host: String?,
    port: Int?,
    timeout: Int?,
    key: String?,
): ApiServiceStartRequest = ApiServiceStartRequest(
    host = host?.takeIf(String::isNotBlank)
        ?: current?.host?.takeIf(String::isNotBlank)
        ?: "127.0.0.1",
    port = port?.takeIf { it > 0 } ?: current?.port?.takeIf { it > 0 } ?: 18765,
    timeout = timeout?.takeIf { it > 0 } ?: current?.timeout?.takeIf { it > 0 } ?: 120,
    apiKey = key,
)
