package com.nanobotkt.feature.auth

import com.nanobotkt.core.model.BootstrapResponse
import com.nanobotkt.core.network.BootstrapService
import com.nanobotkt.core.persistence.EncryptedSecretStore
import com.nanobotkt.core.persistence.UserPreferences
import com.nanobotkt.core.persistence.UserPreferencesRepository
import kotlinx.coroutines.flow.Flow
import javax.inject.Inject
import javax.inject.Singleton

/**
 * 认证仓库真正需要的 Bootstrap 能力。
 *
 * 将网络客户端包在小接口后，认证生命周期测试可以用受控的挂起实现模拟
 * “请求尚未返回时 logout”竞态，而不需要让测试依赖真实网络或 Android Keystore。
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

/** 认证只需要的持久化 secret 能力，避免测试直接触碰 Android Keystore。 */
interface AuthSecretStore {
    suspend fun save(secret: String)
    suspend fun load(): String?
    suspend fun clear()
}

@Singleton
class DefaultAuthSecretStore @Inject constructor(
    private val store: EncryptedSecretStore,
) : AuthSecretStore {
    override suspend fun save(secret: String) = store.save(secret)
    override suspend fun load(): String? = store.load()
    override suspend fun clear() = store.clear()
}

/** 认证只读取/修改的用户偏好能力。 */
interface AuthPreferencesStore {
    val preferences: Flow<UserPreferences>
    suspend fun setServerUrl(value: String?)
}

@Singleton
class DefaultAuthPreferencesStore @Inject constructor(
    private val repository: UserPreferencesRepository,
) : AuthPreferencesStore {
    override val preferences: Flow<UserPreferences> = repository.preferences
    override suspend fun setServerUrl(value: String?) = repository.setServerUrl(value)
}
