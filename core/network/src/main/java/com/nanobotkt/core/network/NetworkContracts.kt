package com.nanobotkt.core.network

import javax.inject.Qualifier

interface AuthContext {
    val baseUrl: String
    val apiToken: String?
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
