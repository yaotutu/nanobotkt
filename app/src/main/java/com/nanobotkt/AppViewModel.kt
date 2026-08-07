package com.nanobotkt

import androidx.appcompat.app.AppCompatDelegate
import androidx.core.os.LocaleListCompat
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.nanobotkt.core.persistence.ThemePreference
import com.nanobotkt.core.persistence.UserPreferences
import com.nanobotkt.core.persistence.UserPreferencesRepository
import com.nanobotkt.core.transport.NanobotTransport
import com.nanobotkt.core.transport.TransportState
import com.nanobotkt.feature.auth.AuthSessionRepository
import com.nanobotkt.feature.auth.AuthState
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import javax.inject.Inject

@HiltViewModel
class AppViewModel @Inject constructor(
    private val authRepository: AuthSessionRepository,
    private val preferencesRepository: UserPreferencesRepository,
    private val transport: NanobotTransport,
) : ViewModel() {
    val authState: StateFlow<AuthState> = authRepository.state
    val preferences: StateFlow<UserPreferences> = preferencesRepository.preferences.stateIn(
        viewModelScope,
        SharingStarted.WhileSubscribed(5_000),
        UserPreferences(),
    )
    val transportState: StateFlow<TransportState> = transport.state

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
    fun logout() = viewModelScope.launch { authRepository.logout() }
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

    private fun applyApplicationLocale(languageTag: String?) {
        val requested = LocaleListCompat.forLanguageTags(languageTag.orEmpty())
        if (AppCompatDelegate.getApplicationLocales().toLanguageTags() != requested.toLanguageTags()) {
            AppCompatDelegate.setApplicationLocales(requested)
        }
    }
}
