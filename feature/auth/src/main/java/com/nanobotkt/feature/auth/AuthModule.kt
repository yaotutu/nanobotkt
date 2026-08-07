package com.nanobotkt.feature.auth

import com.nanobotkt.core.model.BootstrapSnapshotProvider
import com.nanobotkt.core.model.IngressLimitsProvider
import com.nanobotkt.core.network.AuthContext
import com.nanobotkt.core.transport.TransportCredentials
import dagger.Binds
import dagger.Module
import dagger.hilt.InstallIn
import dagger.hilt.components.SingletonComponent

@Module
@InstallIn(SingletonComponent::class)
abstract class AuthModule {
    @Binds abstract fun bindAuthContext(repository: AuthSessionRepository): AuthContext
    @Binds abstract fun bindTransportCredentials(repository: AuthSessionRepository): TransportCredentials
    @Binds abstract fun bindIngressLimitsProvider(repository: AuthSessionRepository): IngressLimitsProvider
    @Binds abstract fun bindBootstrapSnapshotProvider(repository: AuthSessionRepository): BootstrapSnapshotProvider
}


