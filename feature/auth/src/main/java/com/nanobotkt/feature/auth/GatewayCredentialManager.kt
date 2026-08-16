package com.nanobotkt.feature.auth

import com.nanobotkt.core.model.BootstrapResponse
import com.nanobotkt.core.model.GatewayRuntimeSnapshot
import com.nanobotkt.core.model.GatewayRuntimeSnapshotProvider
import com.nanobotkt.core.model.IngressLimitsProvider
import com.nanobotkt.core.model.WebUiIngressLimits
import com.nanobotkt.core.network.ApiCredentialProvider
import com.nanobotkt.core.network.GatewayEndpointProvider
import com.nanobotkt.core.network.GatewayException
import com.nanobotkt.core.network.GatewayServerUrl
import com.nanobotkt.core.transport.WebSocketCredentialProvider
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import java.util.concurrent.atomic.AtomicLong
import javax.inject.Inject
import javax.inject.Singleton
import kotlin.math.max
import kotlin.math.min

/** 鉴权系统只向登录会话发布不可恢复的凭据事件，不发布普通 Token 轮换。 */
internal sealed interface CredentialEvent {
    data object AuthenticationRejected : CredentialEvent
}

/**
 * 为每一轮认证生命周期分配单调递增的代数。
 *
 * logout 会先使当前代数失效，再等待正在进行的 Bootstrap。网络响应返回后必须核对代数，
 * 才能写回 Token；否则旧账号的迟到响应可能在退出后重新恢复认证状态。
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
 * Gateway 短期凭据的唯一所有者。
 *
 * 该对象把服务端的两个不同契约封装起来：REST API Token 在 TTL 内可重复使用；WebSocket
 * Token 只允许领取一次。所有刷新路径共用同一把 [refreshMutex]，避免 REST 401、按需 TTL 刷新和
 * Socket 重连同时发起 Bootstrap，也确保 logout 与迟到响应之间不存在凭据复活窗口。
 */
@Singleton
class GatewayCredentialManager @Inject internal constructor(
    private val bootstrapService: AuthBootstrapGateway,
    private val secretStore: AuthSecretStore,
    private val preferences: AuthPreferencesStore,
    @param:GatewayServerUrl private val defaultServerUrl: String,
    private val clock: MonotonicClock,
) : GatewayEndpointProvider,
    ApiCredentialProvider,
    WebSocketCredentialProvider,
    IngressLimitsProvider,
    GatewayRuntimeSnapshotProvider {

    private val refreshMutex = Mutex()
    private val authGeneration = AuthGeneration()
    private val mutableEvents = MutableSharedFlow<CredentialEvent>(extraBufferCapacity = 1)

    @Volatile
    private var currentSnapshot: CredentialSnapshot? = null

    @Volatile
    private var currentSecret: String? = null

    @Volatile
    private var currentBaseUrl: String = normalizeBaseUrl(defaultServerUrl)

    internal val events: SharedFlow<CredentialEvent> = mutableEvents.asSharedFlow()

    override val baseUrl: String
        get() = currentBaseUrl

    /** 启动时恢复服务地址和持久化 Secret；没有 Secret 时返回 false，不把它当成网络错误。 */
    suspend fun restore(): Boolean {
        val expectedGeneration = authGeneration.current()
        val restoredBaseUrl = normalizeBaseUrl(preferences.preferences.first().serverUrl)
        val restoredSecret = secretStore.load()?.trim()?.takeIf(String::isNotEmpty)

        return refreshMutex.withLock {
            if (!authGeneration.isCurrent(expectedGeneration)) return@withLock false
            // 即使用户从未登录，也必须先恢复服务地址；登录页、Settings 摘要和后续手工认证
            // 都应使用持久化入口，而不是暂时退回编译期默认值。
            currentBaseUrl = restoredBaseUrl
            if (restoredSecret == null) return@withLock false

            val fetched = fetchPayloadLocked(restoredSecret, expectedGeneration)
            applyPayloadLocked(fetched, expectedGeneration)
            currentSecret = restoredSecret
            true
        }
    }

    /** 手工登录只有在 Bootstrap 成功且 Secret 保存成功后才建立内存凭据。 */
    suspend fun authenticate(secret: String) {
        val normalized = secret.trim()
        require(normalized.isNotEmpty()) { "bootstrap secret is blank" }
        val expectedGeneration = authGeneration.current()

        refreshMutex.withLock {
            if (!authGeneration.isCurrent(expectedGeneration)) return@withLock
            val fetched = fetchPayloadLocked(normalized, expectedGeneration)
            if (!authGeneration.isCurrent(expectedGeneration)) return@withLock
            // 必须先完成持久化再发布 Token。若 Keystore 写入失败，调用方进入 Unreachable，
            // 但内存中不会残留一个无法在下次启动恢复的“半登录”会话。
            secretStore.save(normalized)
            if (!authGeneration.isCurrent(expectedGeneration)) return@withLock
            currentSecret = normalized
            applyPayloadLocked(fetched, expectedGeneration)
        }
    }

    /** 使用已保存的 Secret 重新建立凭据；用于启动失败后的显式重试。 */
    suspend fun retry(): Boolean {
        val expectedGeneration = authGeneration.current()
        val secret = currentSecret ?: secretStore.load()?.trim()?.takeIf(String::isNotEmpty) ?: return false
        return refreshMutex.withLock {
            if (!authGeneration.isCurrent(expectedGeneration)) return@withLock false
            val fetched = fetchPayloadLocked(secret, expectedGeneration)
            applyPayloadLocked(fetched, expectedGeneration)
            currentSecret = secret
            true
        }
    }

    /**
     * 修改 Gateway 入口会使旧入口签发的所有内存快照失去意义。
     *
     * 先持久化配置，再在刷新锁内清理旧快照；后续 [retry] 会使用同一 Secret 在新入口上
     * Bootstrap。这里不清理 Secret，因为用户只是切换服务端地址，并未执行 logout。
     */
    suspend fun updateServerUrl(url: String?) {
        val normalized = url?.trim()?.trimEnd('/')?.takeIf(String::isNotEmpty)
        preferences.setServerUrl(normalized)
        refreshMutex.withLock {
            currentBaseUrl = normalizeBaseUrl(normalized)
            currentSnapshot = null
        }
    }

    /** logout 先使代数失效，再清理持久化和内存状态，阻止并发 Bootstrap 复活旧会话。 */
    suspend fun logout() {
        authGeneration.invalidate()
        refreshMutex.withLock {
            currentSnapshot = null
            currentSecret = null
            secretStore.clear()
        }
    }

    override suspend fun tokenForRequest(): String {
        val snapshot = currentSnapshot ?: throw GatewayException.AuthenticationRequired()
        val now = clock.elapsedRealtimeMillis()
        if (now < snapshot.refreshAtElapsedMillis) return snapshot.apiToken

        val expectedGeneration = authGeneration.current()
        return refreshMutex.withLock {
            if (!authGeneration.isCurrent(expectedGeneration)) {
                throw GatewayException.AuthenticationRequired()
            }
            val latest = currentSnapshot ?: throw GatewayException.AuthenticationRequired()
            val checkedAt = clock.elapsedRealtimeMillis()
            if (checkedAt < latest.refreshAtElapsedMillis) return@withLock latest.apiToken

            try {
                refreshLocked(expectedGeneration).apiToken
            } catch (error: CancellationException) {
                throw error
            } catch (error: GatewayException.AuthenticationRequired) {
                throw error
            } catch (error: Exception) {
                // 提前刷新只是按需优化：旧 Token 尚未到硬过期时间时继续使用，网络故障不能伪装成
                // 登录失效；超过硬过期时间后则原样抛出网络/服务错误，禁止发送确定失效的 Token。
                if (clock.elapsedRealtimeMillis() < latest.expiresAtElapsedMillis) latest.apiToken else throw error
            }
        }
    }

    override suspend fun tokenAfterUnauthorized(rejectedToken: String): String {
        val expectedGeneration = authGeneration.current()
        return refreshMutex.withLock {
            if (!authGeneration.isCurrent(expectedGeneration)) {
                throw GatewayException.AuthenticationRequired()
            }
            val latest = currentSnapshot
            if (
                latest != null &&
                latest.apiToken != rejectedToken &&
                clock.elapsedRealtimeMillis() < latest.expiresAtElapsedMillis
            ) {
                // 其他并发请求已完成刷新，直接复用新 Token，避免并发 401 各自 Bootstrap。
                return@withLock latest.apiToken
            }
            refreshLocked(expectedGeneration).apiToken
        }
    }

    override suspend fun freshWebSocketUrl(): String? {
        val expectedGeneration = authGeneration.current()
        return refreshMutex.withLock {
            if (!authGeneration.isCurrent(expectedGeneration)) return@withLock null
            var snapshot = currentSnapshot ?: return@withLock null
            val now = clock.elapsedRealtimeMillis()

            if (snapshot.unclaimedWebSocketUrl == null || now >= snapshot.refreshAtElapsedMillis) {
                snapshot = try {
                    refreshLocked(expectedGeneration)
                } catch (error: CancellationException) {
                    throw error
                } catch (error: GatewayException.AuthenticationRequired) {
                    throw error
                } catch (error: Exception) {
                    // 若刷新只是因为接近边界且旧的一次性 Token 仍未硬过期，可以尝试领取旧值；
                    // 已消费、缺失或硬过期时必须失败，绝不复用以前交给 Socket 的 URL。
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
            // URL 一旦交给一次连接尝试就视为已领取。即使握手是否到达服务端不确定，也不能
            // 再次使用，因为服务端的 issued token 在成功握手时会被 pop，消费状态不可查询。
            currentSnapshot = snapshot.copy(unclaimedWebSocketUrl = null)
            url
        }
    }

    override fun maxFrameBytes(): Int? = currentSnapshot?.runtime?.limits?.transport?.maxFrameBytes

    override fun currentIngressLimits(): WebUiIngressLimits? = currentSnapshot?.runtime?.limits

    override fun currentRuntimeSnapshot(): GatewayRuntimeSnapshot? = currentSnapshot?.runtime

    private suspend fun refreshLocked(expectedGeneration: Long): CredentialSnapshot {
        val secret = currentSecret ?: secretStore.load()?.trim()?.takeIf(String::isNotEmpty)
            ?: throw GatewayException.AuthenticationRequired()
        val fetched = fetchPayloadLocked(secret, expectedGeneration)
        return applyPayloadLocked(fetched, expectedGeneration).also { currentSecret = secret }
    }

    /**
     * 只完成 Bootstrap 网络边界和认证拒绝处理，不提前写入任何 Token。
     *
     * authenticate 需要在 Secret 成功持久化后才调用 [applyPayloadLocked]；restore/retry/refresh
     * 则可在同一临界区直接应用。拆开这两步可以避免 Keystore 失败留下半登录内存状态。
     */
    private suspend fun fetchPayloadLocked(
        secret: String,
        expectedGeneration: Long,
    ): FetchedBootstrap {
        if (!authGeneration.isCurrent(expectedGeneration)) {
            throw GatewayException.AuthenticationRequired()
        }
        val requestStartedAt = clock.elapsedRealtimeMillis()
        return try {
            FetchedBootstrap(
                payload = bootstrapService.fetch(currentBaseUrl, secret),
                issuedAtEstimateMillis = requestStartedAt,
            )
        } catch (error: GatewayException.AuthenticationRequired) {
            handleAuthenticationRejectedLocked(expectedGeneration)
            throw error
        }.also {
            if (!authGeneration.isCurrent(expectedGeneration)) {
                throw GatewayException.AuthenticationRequired()
            }
        }
    }

    /** 将已经验证的 Bootstrap 响应转换成按请求检查 TTL 的客户端凭据快照。 */
    private fun applyPayloadLocked(
        fetched: FetchedBootstrap,
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
        val snapshot = CredentialSnapshot(
            apiToken = payload.apiToken,
            refreshAtElapsedMillis = issuedAt.saturatingAdd(refreshDelay),
            expiresAtElapsedMillis = expiresAt,
            unclaimedWebSocketUrl = bootstrapService.deriveWebSocketUrl(currentBaseUrl, payload),
            runtime = GatewayRuntimeSnapshot(
                limits = payload.limits,
                modelName = payload.modelName,
                runtimeSurface = payload.runtimeSurface,
                runtimeCapabilities = payload.runtimeCapabilities,
            ),
        )
        // 不启动后台定时续期：已打开的 WebSocket 不依赖握手 Token 继续存活；REST 和
        // 新建 Socket 都会在真正需要凭据时检查 TTL。按需刷新避免 App 在后台无意义请求网络，
        // 也让生命周期层无需知道 Token 细节。
        currentSnapshot = snapshot
        return snapshot
    }

    private suspend fun handleAuthenticationRejectedLocked(expectedGeneration: Long) {
        if (!authGeneration.isCurrent(expectedGeneration)) return
        currentSnapshot = null
        currentSecret = null
        // 已保存 Secret 被服务端明确拒绝后应清除，否则下次冷启动会再次自动提交同一无效值。
        try {
            secretStore.clear()
        } catch (_: Exception) {
            // Keystore 清理失败不能覆盖服务端已经给出的认证结论；内存状态仍按失效处理。
        }
        mutableEvents.tryEmit(CredentialEvent.AuthenticationRejected)
    }

    private fun normalizeBaseUrl(value: String?): String =
        value?.trim()?.trimEnd('/')?.takeIf(String::isNotEmpty)
            ?: defaultServerUrl.trim().trimEnd('/')

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
