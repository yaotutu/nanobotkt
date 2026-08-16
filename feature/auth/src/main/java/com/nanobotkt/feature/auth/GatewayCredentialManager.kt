package com.nanobotkt.feature.auth

import com.nanobotkt.core.model.BootstrapResponse
import com.nanobotkt.core.model.GatewayRuntimeSnapshot
import com.nanobotkt.core.model.GatewayRuntimeSnapshotProvider
import com.nanobotkt.core.model.IngressLimitsProvider
import com.nanobotkt.core.model.WebUiIngressLimits
import com.nanobotkt.core.network.ApiCredentialProvider
import com.nanobotkt.core.network.GatewayEndpointProvider
import com.nanobotkt.core.network.GatewayException
import com.nanobotkt.core.network.GatewayServerAddressResult
import com.nanobotkt.core.network.GatewayServerUrl
import com.nanobotkt.core.network.normalizeGatewayServerAddress
import com.nanobotkt.core.transport.WebSocketCredentialProvider
import java.util.concurrent.atomic.AtomicLong
import javax.inject.Inject
import javax.inject.Singleton
import kotlin.math.max
import kotlin.math.min
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

/** 鉴权系统只向登录会话发布当前活动配置已经被服务端拒绝的不可恢复事件。 */
internal sealed interface CredentialEvent {
    data class AuthenticationRejected(val serverUrl: String) : CredentialEvent
}

/**
 * 为每一轮认证生命周期分配单调递增的代数。
 *
 * logout 和成功配置切换都会使旧代数失效。任何迟到的 Bootstrap 响应在写入 Token、活动配置
 * 或持久化状态前都必须核对代数，防止旧服务器响应在边界切换后复活。
 */
internal class AuthGeneration {
    private val value = AtomicLong(0L)

    fun current(): Long = value.get()
    fun invalidate(): Long = value.incrementAndGet()
    fun isCurrent(expected: Long): Boolean = value.get() == expected
}

/** 使用单调时钟计算 TTL，避免用户修改系统时间导致 Token 提前失效或超期使用。 */
internal fun interface MonotonicClock {
    fun elapsedRealtimeMillis(): Long
}

internal object SystemMonotonicClock : MonotonicClock {
    override fun elapsedRealtimeMillis(): Long = System.nanoTime() / 1_000_000L
}

/**
 * Gateway 完整活动配置与短期凭据的唯一所有者。
 *
 * 长期配置只有一份 [GatewayConnectionConfig]；REST API Token 和一次性 WebSocket Token 只是
 * 该配置派生出的短期实现细节。所有配置替换都遵循“候选验证 → 原子持久化 → 清理旧会话 →
 * 激活新快照”的顺序，失败候选绝不能修改当前地址、Secret 或 Token。
 */
@Singleton
class GatewayCredentialManager @Inject internal constructor(
    private val bootstrapService: AuthBootstrapGateway,
    private val configStore: AuthGatewayConfigStore,
    @param:GatewayServerUrl private val defaultServerUrl: String,
    private val clock: MonotonicClock,
) : GatewayEndpointProvider,
    ApiCredentialProvider,
    WebSocketCredentialProvider,
    IngressLimitsProvider,
    GatewayRuntimeSnapshotProvider {

    /**
     * 同一把锁串行化恢复、配置替换、Token 刷新和 logout。
     *
     * REST Token 在有效期内走无锁快路径，因此候选服务器验证不会中断已经建立的 WebSocket，
     * 也不会阻塞持有有效 Token 的普通请求；只有恰好需要刷新凭据的操作会等待配置事务结束。
     */
    private val credentialMutex = Mutex()
    private val authGeneration = AuthGeneration()
    private val mutableEvents = MutableSharedFlow<CredentialEvent>(extraBufferCapacity = 1)

    @Volatile
    private var currentSnapshot: CredentialSnapshot? = null

    @Volatile
    private var currentConfig: GatewayConnectionConfig? = null

    @Volatile
    private var currentBaseUrl: String = normalizedDefaultServerUrl()

    internal val events: SharedFlow<CredentialEvent> = mutableEvents.asSharedFlow()

    override val baseUrl: String
        get() = currentBaseUrl

    /** 启动时只恢复新的完整配置；旧版分离字段不会被读取或迁移。 */
    suspend fun restore(): Boolean {
        val expectedGeneration = authGeneration.current()
        val stored = configStore.load() ?: return credentialMutex.withLock {
            if (!authGeneration.isCurrent(expectedGeneration)) return@withLock false
            currentConfig = null
            currentSnapshot = null
            currentBaseUrl = normalizedDefaultServerUrl()
            false
        }
        val normalized = normalizeConfig(stored) ?: run {
            configStore.clear()
            return false
        }

        return credentialMutex.withLock {
            if (!authGeneration.isCurrent(expectedGeneration)) return@withLock false
            // 即使当前网络不可达，也要先发布真实的持久化入口，错误页和重新配置表单才能
            // 正确预填用户当前配置，而不是误回退到编译期默认地址。
            currentConfig = normalized
            currentBaseUrl = normalized.serverUrl
            val fetched = fetchActivePayloadLocked(normalized, expectedGeneration)
            currentSnapshot = buildSnapshot(fetched, normalized.serverUrl, expectedGeneration)
            true
        }
    }

    /** 初次连接与重新配置都走同一个完整候选事务。 */
    suspend fun configure(
        rawConfig: GatewayConnectionConfig,
        beforeActivation: () -> Unit = {},
    ): GatewayConfigurationResult {
        val normalized = when (val address = normalizeGatewayServerAddress(rawConfig.serverUrl)) {
            is GatewayServerAddressResult.Valid -> rawConfig.copy(serverUrl = address.url)
            is GatewayServerAddressResult.Invalid -> {
                return GatewayConfigurationResult.Failure(
                    GatewayConfigurationError.InvalidAddress(address.error),
                )
            }
        }
        if (normalized.bootstrapSecret.isBlank()) {
            return GatewayConfigurationResult.Failure(GatewayConfigurationError.MissingSecret)
        }

        val expectedGeneration = authGeneration.current()
        return credentialMutex.withLock {
            if (!authGeneration.isCurrent(expectedGeneration)) {
                return@withLock GatewayConfigurationResult.Failure(GatewayConfigurationError.Cancelled)
            }

            val fetched = try {
                // 候选验证只使用候选对象中的地址与 Secret，不读取 currentConfig，因此旧 Secret
                // 不可能被转发到用户新填写的 Host。
                fetchPayload(normalized, expectedGeneration)
            } catch (error: CancellationException) {
                throw error
            } catch (error: Exception) {
                return@withLock candidateFailure(error, expectedGeneration)
            }
            val candidateSnapshot = try {
                // WebSocket URL、TTL 和运行时元数据也必须在持久化前完成验证。否则 Bootstrap
                // 表面成功但响应不可用时，会把无法激活的候选配置写成新的活动配置。
                buildSnapshot(fetched, normalized.serverUrl, expectedGeneration)
            } catch (error: CancellationException) {
                throw error
            } catch (error: Exception) {
                return@withLock candidateFailure(error, expectedGeneration)
            }
            if (!authGeneration.isCurrent(expectedGeneration)) {
                return@withLock GatewayConfigurationResult.Failure(GatewayConfigurationError.Cancelled)
            }

            try {
                // DataStore 在同一个事务中替换地址和加密 Secret。持久化失败时，下面的旧会话
                // 清理和活动快照替换都不会执行，当前连接仍然完整可用。
                configStore.save(normalized)
            } catch (error: CancellationException) {
                throw error
            } catch (_: Exception) {
                return@withLock GatewayConfigurationResult.Failure(GatewayConfigurationError.StorageFailure)
            }
            if (!authGeneration.isCurrent(expectedGeneration)) {
                // logout 已经使本次候选失效；logout 随后会取得同一把锁并清除刚写入的配置。
                return@withLock GatewayConfigurationResult.Failure(GatewayConfigurationError.Cancelled)
            }

            val activationGeneration = authGeneration.invalidate()
            // 回调由 app 组合根同步清理旧 Gateway 的 feature 状态和 Transport。它只能出现在
            // 持久化成功之后、活动凭据切换之前，避免失败候选破坏旧连接，也避免业务请求在
            // 清理窗口中误用新 Gateway Token。
            //
            // 这里刻意把“激活候选配置”放在清理回调的异常隔离之后：持久化一旦成功，就必须
            // 保证内存活动配置与磁盘记录最终收敛到同一份候选配置。即使组合根某个清理动作意外抛错，也不能留下
            // “本进程继续使用旧地址、下次启动却恢复新地址”的撕裂状态。组合根自己的清理函数
            // 仍应保证逐项完成且不抛异常；这里是最后一道一致性保护，而不是吞掉正常业务错误。
            try {
                beforeActivation()
            } catch (_: Exception) {
                // 清理回调属于进程内的派生状态维护，不得破坏已经提交的配置事务。异常时仍继续
                // 激活候选，随后新的 Ready epoch 会驱动各 Repository 和 Transport 重新建立状态。
                // Error 不在此吞掉，避免掩盖 OOM 等进程级故障。
            }
            if (authGeneration.isCurrent(activationGeneration)) {
                currentConfig = normalized
                currentBaseUrl = normalized.serverUrl
                currentSnapshot = candidateSnapshot
            }
            if (!authGeneration.isCurrent(activationGeneration)) {
                return@withLock GatewayConfigurationResult.Failure(GatewayConfigurationError.Cancelled)
            }

            GatewayConfigurationResult.Success(normalized.serverUrl)
        }
    }

    /** 使用当前完整配置重新 Bootstrap；用于冷启动临时失败后的显式重试。 */
    suspend fun retryCurrent(): Boolean {
        val expectedGeneration = authGeneration.current()
        return credentialMutex.withLock {
            if (!authGeneration.isCurrent(expectedGeneration)) return@withLock false
            val config = currentConfig ?: configStore.load()?.let(::normalizeConfig) ?: return@withLock false
            currentConfig = config
            currentBaseUrl = config.serverUrl
            val fetched = fetchActivePayloadLocked(config, expectedGeneration)
            currentSnapshot = buildSnapshot(fetched, config.serverUrl, expectedGeneration)
            true
        }
    }

    /** logout 先使代数失效，再把地址和 Secret 作为一个整体清除。 */
    suspend fun logout() {
        authGeneration.invalidate()
        credentialMutex.withLock {
            currentSnapshot = null
            currentConfig = null
            currentBaseUrl = normalizedDefaultServerUrl()
            configStore.clear()
        }
    }

    override suspend fun tokenForRequest(): String {
        val snapshot = currentSnapshot ?: throw GatewayException.AuthenticationRequired()
        val now = clock.elapsedRealtimeMillis()
        if (now < snapshot.refreshAtElapsedMillis) return snapshot.apiToken

        val expectedGeneration = authGeneration.current()
        return credentialMutex.withLock {
            if (!authGeneration.isCurrent(expectedGeneration)) {
                throw GatewayException.AuthenticationRequired()
            }
            val latest = currentSnapshot ?: throw GatewayException.AuthenticationRequired()
            val checkedAt = clock.elapsedRealtimeMillis()
            if (checkedAt < latest.refreshAtElapsedMillis) return@withLock latest.apiToken

            try {
                refreshActiveLocked(expectedGeneration).apiToken
            } catch (error: CancellationException) {
                throw error
            } catch (error: GatewayException.AuthenticationRequired) {
                throw error
            } catch (error: Exception) {
                // 提前刷新失败时，只要旧 Token 尚未硬过期就继续使用；临时网络错误不能被
                // 解释成配置失效。硬过期后必须抛出真实错误，禁止发送确定失效的 Token。
                if (clock.elapsedRealtimeMillis() < latest.expiresAtElapsedMillis) latest.apiToken else throw error
            }
        }
    }

    override suspend fun tokenAfterUnauthorized(rejectedToken: String): String {
        val expectedGeneration = authGeneration.current()
        return credentialMutex.withLock {
            if (!authGeneration.isCurrent(expectedGeneration)) {
                throw GatewayException.AuthenticationRequired()
            }
            val latest = currentSnapshot
            if (
                latest != null &&
                latest.apiToken != rejectedToken &&
                clock.elapsedRealtimeMillis() < latest.expiresAtElapsedMillis
            ) {
                // 其他并发请求已经刷新完成时复用新 Token，避免多个 401 各自发起 Bootstrap。
                return@withLock latest.apiToken
            }
            refreshActiveLocked(expectedGeneration).apiToken
        }
    }

    override suspend fun freshWebSocketUrl(): String? {
        val expectedGeneration = authGeneration.current()
        return credentialMutex.withLock {
            if (!authGeneration.isCurrent(expectedGeneration)) return@withLock null
            var snapshot = currentSnapshot ?: return@withLock null
            val now = clock.elapsedRealtimeMillis()

            if (snapshot.unclaimedWebSocketUrl == null || now >= snapshot.refreshAtElapsedMillis) {
                snapshot = try {
                    refreshActiveLocked(expectedGeneration)
                } catch (error: CancellationException) {
                    throw error
                } catch (error: GatewayException.AuthenticationRequired) {
                    throw error
                } catch (error: Exception) {
                    // 未消费且未硬过期的一次性 URL 可以作为提前刷新失败时的兜底；已经领取、
                    // 缺失或硬过期的 URL 绝不能复用。
                    val fallback = currentSnapshot
                    if (
                        fallback?.unclaimedWebSocketUrl != null &&
                        clock.elapsedRealtimeMillis() < fallback.expiresAtElapsedMillis
                    ) {
                        fallback
                    } else {
                        throw error
                    }
                }
            }

            val url = snapshot.unclaimedWebSocketUrl ?: return@withLock null
            currentSnapshot = snapshot.copy(unclaimedWebSocketUrl = null)
            url
        }
    }

    override fun maxFrameBytes(): Int? = currentSnapshot?.runtime?.limits?.transport?.maxFrameBytes

    override fun currentIngressLimits(): WebUiIngressLimits? = currentSnapshot?.runtime?.limits

    override fun currentRuntimeSnapshot(): GatewayRuntimeSnapshot? = currentSnapshot?.runtime

    private suspend fun refreshActiveLocked(expectedGeneration: Long): CredentialSnapshot {
        val config = currentConfig ?: throw GatewayException.AuthenticationRequired()
        val fetched = fetchActivePayloadLocked(config, expectedGeneration)
        return buildSnapshot(fetched, config.serverUrl, expectedGeneration).also { currentSnapshot = it }
    }


    /**
     * 把候选请求的真实认证拒绝与生命周期取消区分开。
     *
     * logout 会在等待 credentialMutex 之前先递增代数，因此一个已经发出的候选请求可能收到
     * 响应后才发现自己失效。此时 [fetchPayload] 为了中断后续写入会抛出 AuthenticationRequired，
     * 但它不代表用户填写的完整配置被服务端拒绝，UI 必须显示“操作已取消”而不是误报 Secret。
     */
    private fun candidateFailure(
        error: Exception,
        expectedGeneration: Long,
    ): GatewayConfigurationResult.Failure = GatewayConfigurationResult.Failure(
        if (authGeneration.isCurrent(expectedGeneration)) {
            error.toGatewayConfigurationError()
        } else {
            GatewayConfigurationError.Cancelled
        },
    )

    /** 当前活动配置被拒绝时，整组配置失效；候选配置失败绝不会调用这里。 */
    private suspend fun fetchActivePayloadLocked(
        config: GatewayConnectionConfig,
        expectedGeneration: Long,
    ): FetchedBootstrap = try {
        fetchPayload(config, expectedGeneration)
    } catch (error: GatewayException.AuthenticationRequired) {
        rejectActiveConfigurationLocked(expectedGeneration)
        throw error
    }

    /** 只执行指定配置的 Bootstrap，不读取或修改活动配置。 */
    private suspend fun fetchPayload(
        config: GatewayConnectionConfig,
        expectedGeneration: Long,
    ): FetchedBootstrap {
        if (!authGeneration.isCurrent(expectedGeneration)) {
            throw GatewayException.AuthenticationRequired()
        }
        val requestStartedAt = clock.elapsedRealtimeMillis()
        val payload = bootstrapService.fetch(config.serverUrl, config.bootstrapSecret)
        if (!authGeneration.isCurrent(expectedGeneration)) {
            throw GatewayException.AuthenticationRequired()
        }
        return FetchedBootstrap(payload = payload, issuedAtEstimateMillis = requestStartedAt)
    }

    /** 将已验证响应构造成候选快照；构造过程不修改任何活动字段。 */
    private fun buildSnapshot(
        fetched: FetchedBootstrap,
        baseUrl: String,
        expectedGeneration: Long,
    ): CredentialSnapshot {
        if (!authGeneration.isCurrent(expectedGeneration)) {
            throw GatewayException.AuthenticationRequired()
        }
        val payload = fetched.payload
        val issuedAt = fetched.issuedAtEstimateMillis
        val ttlMillis = payload.expiresIn.coerceAtLeast(0L).saturatingMultiply(1_000L)
        val expiresAt = issuedAt.saturatingAdd(ttlMillis)
        val refreshMargin = min(TOKEN_REFRESH_MARGIN_MILLIS, max(1_000L, ttlMillis / 2L))
        val refreshDelay = min(ttlMillis, max(TOKEN_REFRESH_MIN_DELAY_MILLIS, ttlMillis - refreshMargin))
        return CredentialSnapshot(
            apiToken = payload.apiToken,
            refreshAtElapsedMillis = issuedAt.saturatingAdd(refreshDelay),
            expiresAtElapsedMillis = expiresAt,
            unclaimedWebSocketUrl = bootstrapService.deriveWebSocketUrl(baseUrl, payload),
            runtime = GatewayRuntimeSnapshot(
                limits = payload.limits,
                modelName = payload.modelName,
                runtimeSurface = payload.runtimeSurface,
                runtimeCapabilities = payload.runtimeCapabilities,
            ),
        )
    }

    private suspend fun rejectActiveConfigurationLocked(expectedGeneration: Long) {
        if (!authGeneration.isCurrent(expectedGeneration)) return
        val rejectedUrl = currentBaseUrl
        currentSnapshot = null
        currentConfig = null
        try {
            configStore.clear()
        } catch (_: Exception) {
            // 服务端已经明确拒绝当前配置，内存必须立即失效；本地清理失败不能恢复 Token。
        }
        mutableEvents.tryEmit(CredentialEvent.AuthenticationRejected(rejectedUrl))
    }

    private fun normalizeConfig(config: GatewayConnectionConfig): GatewayConnectionConfig? {
        val address = normalizeGatewayServerAddress(config.serverUrl) as? GatewayServerAddressResult.Valid
            ?: return null
        return config
            .takeIf { it.bootstrapSecret.isNotBlank() }
            ?.copy(serverUrl = address.url)
    }

    private fun normalizedDefaultServerUrl(): String =
        (normalizeGatewayServerAddress(defaultServerUrl) as? GatewayServerAddressResult.Valid)?.url
            ?: error("BuildConfig.NANOBOT_SERVER_URL 必须是合法的 HTTP(S) Gateway 地址")

    private data class FetchedBootstrap(
        val payload: BootstrapResponse,
        val issuedAtEstimateMillis: Long,
    )

    private data class CredentialSnapshot(
        val apiToken: String,
        val refreshAtElapsedMillis: Long,
        val expiresAtElapsedMillis: Long,
        val unclaimedWebSocketUrl: String?,
        val runtime: GatewayRuntimeSnapshot,
    )

    private companion object {
        const val TOKEN_REFRESH_MARGIN_MILLIS = 30_000L
        const val TOKEN_REFRESH_MIN_DELAY_MILLIS = 5_000L
    }
}

private fun Long.saturatingAdd(other: Long): Long =
    if (other > 0L && this > Long.MAX_VALUE - other) Long.MAX_VALUE else this + other

private fun Long.saturatingMultiply(other: Long): Long =
    if (this > 0L && other > 0L && this > Long.MAX_VALUE / other) Long.MAX_VALUE else this * other
