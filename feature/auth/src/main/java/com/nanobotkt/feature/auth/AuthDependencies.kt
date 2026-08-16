package com.nanobotkt.feature.auth

import com.nanobotkt.core.model.BootstrapResponse
import com.nanobotkt.core.network.BootstrapService
import com.nanobotkt.core.persistence.EncryptedGatewayConfigStore
import com.nanobotkt.core.persistence.StoredGatewayConnectionConfig
import javax.inject.Inject
import javax.inject.Singleton

/**
 * 认证仓库真正需要的 Bootstrap 能力。
 *
 * 候选配置验证必须显式传入候选地址和候选 Secret，不能读取当前活动配置。这个边界让测试可以
 * 直接证明旧 Secret 不会被发送到新地址，也避免为了切换 Gateway 临时修改全局 HTTP Client。
 */
interface AuthBootstrapGateway {
    suspend fun fetch(baseUrl: String, secret: String): BootstrapResponse
    fun deriveWebSocketUrl(baseUrl: String, payload: BootstrapResponse): String
}

@Singleton
class DefaultAuthBootstrapGateway @Inject constructor(
    private val service: BootstrapService,
) : AuthBootstrapGateway {
    override suspend fun fetch(baseUrl: String, secret: String): BootstrapResponse =
        service.fetch(baseUrl, secret)

    override fun deriveWebSocketUrl(baseUrl: String, payload: BootstrapResponse): String =
        service.deriveWebSocketUrl(baseUrl, payload)
}

/**
 * feature:auth 所需的完整配置存储边界。
 *
 * 接口只允许整体 save/load/clear，调用方无法分别写地址或 Secret，从类型层面消除半配置状态。
 */
interface AuthGatewayConfigStore {
    suspend fun save(config: GatewayConnectionConfig)
    suspend fun load(): GatewayConnectionConfig?
    suspend fun clear()
}

@Singleton
class DefaultAuthGatewayConfigStore @Inject constructor(
    private val store: EncryptedGatewayConfigStore,
) : AuthGatewayConfigStore {
    override suspend fun save(config: GatewayConnectionConfig) {
        store.save(
            StoredGatewayConnectionConfig(
                serverUrl = config.serverUrl,
                bootstrapSecret = config.bootstrapSecret,
            ),
        )
    }

    override suspend fun load(): GatewayConnectionConfig? = store.load()?.let { stored ->
        GatewayConnectionConfig(
            serverUrl = stored.serverUrl,
            bootstrapSecret = stored.bootstrapSecret,
        )
    }

    override suspend fun clear() = store.clear()
}
