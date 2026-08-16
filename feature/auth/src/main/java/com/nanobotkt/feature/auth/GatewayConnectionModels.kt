package com.nanobotkt.feature.auth

import com.nanobotkt.core.network.GatewayServerAddressError

/**
 * 用户可配置的 Gateway 连接信息。
 *
 * 地址和 Bootstrap Secret 是不可拆分的业务配置。任何入口都必须构造完整对象，禁止重新引入
 * updateServerUrl、updatePassword 或只接收 Secret 的兼容 API。
 */
data class GatewayConnectionConfig(
    val serverUrl: String,
    val bootstrapSecret: String,
)

/** 初次配置和重新配置共用的稳定错误模型。 */
sealed interface GatewayConfigurationError {
    data class InvalidAddress(val reason: GatewayServerAddressError) : GatewayConfigurationError
    data object MissingSecret : GatewayConfigurationError
    data object AuthenticationRejected : GatewayConfigurationError
    data object Timeout : GatewayConfigurationError
    data object NetworkUnavailable : GatewayConfigurationError
    data object HtmlResponse : GatewayConfigurationError
    data object NonJsonResponse : GatewayConfigurationError
    data object InvalidResponse : GatewayConfigurationError
    data class Http(val status: Int, val message: String?) : GatewayConfigurationError
    data object StorageFailure : GatewayConfigurationError
    data object Cancelled : GatewayConfigurationError
    data class Unknown(val message: String?) : GatewayConfigurationError
}

/** 只有候选配置完成验证、持久化和激活后才返回 [Success]。 */
sealed interface GatewayConfigurationResult {
    data class Success(val serverUrl: String) : GatewayConfigurationResult
    data class Failure(val error: GatewayConfigurationError) : GatewayConfigurationResult
}
