package com.nanobotkt.feature.auth

import com.nanobotkt.core.model.GatewayRuntimeSnapshotProvider
import com.nanobotkt.core.model.IngressLimitsProvider
import com.nanobotkt.core.network.ApiCredentialProvider
import com.nanobotkt.core.network.GatewayEndpointProvider
import com.nanobotkt.core.transport.WebSocketCredentialProvider
import dagger.Binds
import dagger.Module
import dagger.Provides
import dagger.hilt.InstallIn
import dagger.hilt.components.SingletonComponent
import javax.inject.Singleton

@Module
@InstallIn(SingletonComponent::class)
abstract class AuthModule {
    @Binds abstract fun bindAuthBootstrapGateway(implementation: DefaultAuthBootstrapGateway): AuthBootstrapGateway
    @Binds abstract fun bindAuthSecretStore(implementation: DefaultAuthSecretStore): AuthSecretStore
    @Binds abstract fun bindAuthPreferencesStore(implementation: DefaultAuthPreferencesStore): AuthPreferencesStore

    /** 通信层只依赖最小凭据接口；登录状态仓库不再充当 Token 快照容器。 */
    @Binds abstract fun bindGatewayEndpointProvider(manager: GatewayCredentialManager): GatewayEndpointProvider
    @Binds abstract fun bindApiCredentialProvider(manager: GatewayCredentialManager): ApiCredentialProvider
    @Binds abstract fun bindWebSocketCredentialProvider(manager: GatewayCredentialManager): WebSocketCredentialProvider

    /** 业务层只读取无敏感凭据的运行时元数据和上传限制。 */
    @Binds abstract fun bindIngressLimitsProvider(manager: GatewayCredentialManager): IngressLimitsProvider
    @Binds abstract fun bindGatewayRuntimeSnapshotProvider(manager: GatewayCredentialManager): GatewayRuntimeSnapshotProvider

    companion object {
        @Provides
        @Singleton
        internal fun provideMonotonicClock(): MonotonicClock = SystemMonotonicClock
    }
}
