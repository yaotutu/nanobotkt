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
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import javax.inject.Inject

@HiltViewModel
class SettingsViewModel @Inject constructor(
    private val repository: SettingsRepository,
    private val preferences: UserPreferencesRepository,
) : ViewModel() {
    val state = repository.state
    val appearance = preferences.preferences.stateIn(
        scope = viewModelScope,
        started = SharingStarted.WhileSubscribed(5_000),
        initialValue = UserPreferences(),
    )

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
