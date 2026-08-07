package com.nanobotkt.feature.settings

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
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
    fun providerModels(name: String) = viewModelScope.launch { repository.providerModels(name) }
    fun oauth(name: String) = viewModelScope.launch { repository.oauthLogin(name) }
    fun checkVersion() = viewModelScope.launch { repository.checkVersion() }

    fun apiService(
        start: Boolean,
        host: String = "127.0.0.1",
        port: Int = 18765,
        timeout: Int = 120,
        key: String? = null,
    ) = viewModelScope.launch {
        if (start) repository.startApiService(host, port, timeout, key) else repository.stopApiService()
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
