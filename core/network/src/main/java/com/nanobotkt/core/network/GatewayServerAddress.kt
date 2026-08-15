package com.nanobotkt.core.network

import okhttp3.HttpUrl.Companion.toHttpUrlOrNull

/**
 * Gateway 地址本地校验失败的稳定原因。
 *
 * 网络层只返回结构化错误，不包含面向用户的文案；Auth、Settings 等 UI 可以按自己的
 * 语言环境映射提示，同时避免多个 feature 各自实现略有差异的 URL 规则。
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

/** Gateway 地址校验结果；成功值始终是不带末尾斜杠的规范化 HTTP(S) 基础地址。 */
sealed interface GatewayServerAddressResult {
    data class Valid(val url: String) : GatewayServerAddressResult
    data class Invalid(val error: GatewayServerAddressError) : GatewayServerAddressResult
}

/**
 * 规范化用户输入的 Gateway 基础地址。
 *
 * - 只接受显式的 http/https scheme，避免应用猜测协议后把 Secret 发到错误端点；
 * - 保留反向代理路径，但拒绝 query、fragment 和 URL 内嵌凭据；
 * - 根地址与带路径地址都移除末尾斜杠，确保 HTTP、Bootstrap 和 WebSocket 使用同一键值。
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
