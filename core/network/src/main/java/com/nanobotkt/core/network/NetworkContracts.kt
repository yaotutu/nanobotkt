package com.nanobotkt.core.network

import javax.inject.Qualifier

/**
 * Gateway HTTP 入口提供者。
 *
 * 通信层只依赖当前服务端入口，不再读取登录仓库中的 Token 快照。这样 URL 配置与
 * 短期凭据生命周期可以独立演进，也避免业务层通过一个“认证上下文”同时修改地址和凭据。
 */
interface GatewayEndpointProvider {
    val baseUrl: String
}

/**
 * REST 请求所需的短期凭据能力。
 *
 * 接口返回的是“本次请求可使用的 Token”，而不是某个可能即将过期的内存字段。
 * 实现负责 TTL、并发刷新、服务端重启后的强制刷新以及 logout 竞态保护。
 */
interface ApiCredentialProvider {
    suspend fun tokenForRequest(): String

    /**
     * 处理服务端明确拒绝某个 Gateway API Token 的场景。
     *
     * [rejectedToken] 必须是刚刚收到 `Unauthorized` 的请求实际携带的 Token。实现先比较
     * 当前 Token：若其他并发请求已经完成刷新，直接复用新值；只有仍然持有被拒绝的值时
     * 才重新 Bootstrap，从而把并发 401 收敛为一次刷新。
     */
    suspend fun tokenAfterUnauthorized(rejectedToken: String): String
}

@Qualifier
@Retention(AnnotationRetention.BINARY)
annotation class GatewayServerUrl

@Qualifier
@Retention(AnnotationRetention.BINARY)
annotation class RestClient

@Qualifier
@Retention(AnnotationRetention.BINARY)
annotation class WebSocketClient

sealed class GatewayException(message: String, cause: Throwable? = null) : Exception(message, cause) {
    class Http(val status: Int, message: String) : GatewayException(message)
    class AuthenticationRequired(message: String = "authentication_required") : GatewayException(message)
    class HtmlResponse : GatewayException("gateway_html_response")
    class NonJsonResponse : GatewayException("non_json_response")
    class InvalidPayload(cause: Throwable) : GatewayException("invalid_payload", cause)
    class Timeout(cause: Throwable) : GatewayException("timeout", cause)
    class Network(cause: Throwable) : GatewayException("network_unavailable", cause)
}
