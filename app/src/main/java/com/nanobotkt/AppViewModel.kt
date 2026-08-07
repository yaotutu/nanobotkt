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
        resetRootUiState()
        viewModelScope.launch { authRepository.logout() }
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
