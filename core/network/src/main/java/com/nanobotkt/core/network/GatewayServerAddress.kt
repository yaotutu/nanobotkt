package com.nanobotkt.core.network

import okhttp3.HttpUrl.Companion.toHttpUrlOrNull

/**
 * Gateway 基础地址在本地校验阶段可能产生的稳定错误。
 *
 * 网络层只描述结构化原因，不携带任何面向用户的文案。这样初次配置页和 Settings
 * 重新配置页可以复用完全一致的安全规则，同时根据各自界面映射本地化提示。
 */
enum class GatewayServerAddressError {
    EMPTY,
    MISSING_SCHEME,
    UNSUPPORTED_SCHEME,
    INVALID_URL,
    MISSING_HOST,
    EMBEDDED_CREDENTIALS,
    QUERY_NOT_ALLOWED,
    FRAGMENT_NOT_ALLOWED,
}

/** Gateway 地址校验结果；成功值始终为不带末尾斜杠的 HTTP(S) 基础地址。 */
sealed interface GatewayServerAddressResult {
    data class Valid(val url: String) : GatewayServerAddressResult
    data class Invalid(val error: GatewayServerAddressError) : GatewayServerAddressResult
}

/**
 * 规范化用户输入的 Gateway 基础地址。
 *
 * 安全边界：
 * - 必须显式填写 http:// 或 https://，客户端不能猜测协议后发送 Bootstrap Secret；
 * - 允许反向代理路径，但拒绝 query、fragment 和 URL 内嵌凭据；
 * - 统一移除末尾斜杠，确保 Bootstrap、REST 与 WebSocket 共享同一个基础入口。
 */
fun normalizeGatewayServerAddress(rawValue: String): GatewayServerAddressResult {
    val value = rawValue.trim()
    if (value.isEmpty()) return GatewayServerAddressResult.Invalid(GatewayServerAddressError.EMPTY)

    val schemeSeparator = value.indexOf("://")
    if (schemeSeparator <= 0) {
        return GatewayServerAddressResult.Invalid(GatewayServerAddressError.MISSING_SCHEME)
    }
    val scheme = value.substring(0, schemeSeparator).lowercase()
    if (scheme != "http" && scheme != "https") {
        return GatewayServerAddressResult.Invalid(GatewayServerAddressError.UNSUPPORTED_SCHEME)
    }

    val parsed = value.toHttpUrlOrNull()
        ?: return GatewayServerAddressResult.Invalid(GatewayServerAddressError.INVALID_URL)
    if (parsed.host.isBlank()) {
        return GatewayServerAddressResult.Invalid(GatewayServerAddressError.MISSING_HOST)
    }
    if (parsed.username.isNotEmpty() || parsed.password.isNotEmpty()) {
        return GatewayServerAddressResult.Invalid(GatewayServerAddressError.EMBEDDED_CREDENTIALS)
    }
    if (parsed.query != null) {
        return GatewayServerAddressResult.Invalid(GatewayServerAddressError.QUERY_NOT_ALLOWED)
    }
    if (parsed.fragment != null) {
        return GatewayServerAddressResult.Invalid(GatewayServerAddressError.FRAGMENT_NOT_ALLOWED)
    }

    return GatewayServerAddressResult.Valid(parsed.toString().trimEnd('/'))
}
