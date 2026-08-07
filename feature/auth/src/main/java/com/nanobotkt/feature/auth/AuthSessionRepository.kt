package com.nanobotkt.feature.auth

import com.nanobotkt.core.model.BootstrapResponse
import com.nanobotkt.core.model.BootstrapSnapshotProvider
import com.nanobotkt.core.model.IngressLimitsProvider
import com.nanobotkt.core.model.WebUiIngressLimits
import com.nanobotkt.core.network.AuthContext
import com.nanobotkt.core.network.BootstrapService
import com.nanobotkt.core.network.GatewayException
import com.nanobotkt.core.network.GatewayServerUrl
import com.nanobotkt.core.persistence.EncryptedSecretStore
import com.nanobotkt.core.persistence.UserPreferencesRepository
import com.nanobotkt.core.transport.TransportCredentials
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import javax.inject.Inject
import javax.inject.Singleton
import kotlin.math.max

sealed interface AuthState {
    val sessionEpoch: Long
    val tokenGeneration: Long
    data class Booting(override val sessionEpoch: Long = 0, override val tokenGeneration: Long = 0) : AuthState
    data class Authentication(val failed: Boolean = false, val submitting: Boolean = false, override val sessionEpoch: Long = 0, override val tokenGeneration: Long = 0) : AuthState
    data class Ready(val bootstrap: BootstrapResponse, override val sessionEpoch: Long, override val tokenGeneration: Long) : AuthState
    data class Unreachable(val message: String, override val sessionEpoch: Long, override val tokenGeneration: Long) : AuthState
}

@Singleton
class AuthSessionRepository @Inject constructor(
    private val bootstrapService: BootstrapService,
    private val secretStore: EncryptedSecretStore,
    private val preferences: UserPreferencesRepository,
    @param:GatewayServerUrl private val defaultServerUrl: String,
) : AuthContext, TransportCredentials, IngressLimitsProvider, BootstrapSnapshotProvider {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val refreshMutex = Mutex()
    private val mutableState = MutableStateFlow<AuthState>(AuthState.Booting())
    private var renewalJob: Job? = null
    private var started = false
    private var currentBootstrap: BootstrapResponse? = null
    private var currentBaseUrl = defaultServerUrl.trimEnd('/')

    val state: StateFlow<AuthState> = mutableState.asStateFlow()
    override val baseUrl: String get() = currentBaseUrl
    override val apiToken: String? get() = currentBootstrap?.apiToken
    override fun currentBootstrap(): BootstrapResponse? = currentBootstrap

    fun start() {
        if (started) return
        started = true
        scope.launch {
            currentBaseUrl = preferences.preferences.first().serverUrl?.trimEnd('/') ?: defaultServerUrl.trimEnd('/')
            val secret = secretStore.load()
            if (secret.isNullOrBlank()) {
                mutableState.value = AuthState.Authentication()
                return@launch
            }
            refresh(secret, establishSession = true, authFailureReturnsToForm = true)
        }
    }

    suspend fun authenticate(secret: String) {
        val value = secret.trim()
        if (value.isEmpty()) return
        val previous = mutableState.value
        mutableState.value = AuthState.Authentication(submitting = true, sessionEpoch = previous.sessionEpoch, tokenGeneration = previous.tokenGeneration)
        try {
            val payload = bootstrapService.fetch(currentBaseUrl, value)
            secretStore.save(value)
            applyBootstrap(payload, establishSession = currentBootstrap == null)
        } catch (error: GatewayException.AuthenticationRequired) {
            mutableState.value = AuthState.Authentication(failed = true, sessionEpoch = previous.sessionEpoch, tokenGeneration = previous.tokenGeneration)
        } catch (error: CancellationException) {
            throw error
        } catch (error: Exception) {
            mutableState.value = AuthState.Unreachable(error.toAuthMessage(), previous.sessionEpoch, previous.tokenGeneration)
        }
    }

    suspend fun retry() {
        val secret = secretStore.load()
        if (secret.isNullOrBlank()) {
            mutableState.value = AuthState.Authentication()
            return
        }
        val previous = mutableState.value
        mutableState.value = AuthState.Booting(previous.sessionEpoch, previous.tokenGeneration)
        refresh(secret, establishSession = currentBootstrap == null, authFailureReturnsToForm = true)
    }

    suspend fun refreshForSocket(): String? {
        val secret = secretStore.load() ?: return null
        return refreshMutex.withLock {
            try {
                val payload = bootstrapService.fetch(currentBaseUrl, secret)
                applyBootstrap(payload, establishSession = false)
                bootstrapService.deriveWebSocketUrl(currentBaseUrl, payload)
            } catch (_: Exception) {
                null
            }
        }
    }

    suspend fun logout() {
        renewalJob?.cancel()
        currentBootstrap = null
        secretStore.clear()
        mutableState.value = AuthState.Authentication()
    }

    suspend fun updateServerUrl(url: String?) {
        preferences.setServerUrl(url)
        currentBaseUrl = url?.trim()?.trimEnd('/')?.takeIf(String::isNotEmpty) ?: defaultServerUrl.trimEnd('/')
        retry()
    }

    override fun currentWebSocketUrl(): String? = currentBootstrap?.let { bootstrapService.deriveWebSocketUrl(currentBaseUrl, it) }
    override suspend fun reauthenticateWebSocketUrl(): String? = refreshForSocket()
    override fun maxFrameBytes(): Int? = currentBootstrap?.limits?.transport?.maxFrameBytes
    override fun currentIngressLimits(): WebUiIngressLimits? = currentBootstrap?.limits

    private suspend fun refresh(secret: String, establishSession: Boolean, authFailureReturnsToForm: Boolean) {
        refreshMutex.withLock {
            val previous = mutableState.value
            try {
                applyBootstrap(bootstrapService.fetch(currentBaseUrl, secret), establishSession)
            } catch (error: GatewayException.AuthenticationRequired) {
                if (authFailureReturnsToForm) mutableState.value = AuthState.Authentication(failed = true, sessionEpoch = previous.sessionEpoch, tokenGeneration = previous.tokenGeneration)
                else mutableState.value = AuthState.Unreachable(error.toAuthMessage(), previous.sessionEpoch, previous.tokenGeneration)
            } catch (error: CancellationException) {
                throw error
            } catch (error: Exception) {
                mutableState.value = AuthState.Unreachable(error.toAuthMessage(), previous.sessionEpoch, previous.tokenGeneration)
            }
        }
    }

    private fun applyBootstrap(payload: BootstrapResponse, establishSession: Boolean) {
        val previous = mutableState.value
        currentBootstrap = payload
        val epoch = if (establishSession) previous.sessionEpoch + 1 else previous.sessionEpoch
        mutableState.value = AuthState.Ready(payload, epoch, previous.tokenGeneration + 1)
        renewalJob?.cancel()
        val renewAfterMillis = max(5_000L, payload.expiresIn * 800L)
        renewalJob = scope.launch {
            delay(renewAfterMillis)
            val secret = secretStore.load() ?: return@launch
            refresh(secret, establishSession = false, authFailureReturnsToForm = false)
        }
    }
}

private fun Throwable.toAuthMessage(): String = when (this) {
    is GatewayException.HtmlResponse -> "gateway_html_response"
    is GatewayException.NonJsonResponse -> "non_json_response"
    is GatewayException.Timeout -> "timeout"
    is GatewayException.Network -> "network_unavailable"
    is GatewayException.Http -> message ?: "HTTP $status"
    else -> message ?: "unknown_error"
}


