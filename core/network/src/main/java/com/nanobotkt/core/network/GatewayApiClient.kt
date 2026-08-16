package com.nanobotkt.core.network

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.DeserializationStrategy
import kotlinx.serialization.SerializationStrategy
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.serializer
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import java.io.IOException
import java.io.InterruptedIOException
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class GatewayApiClient @Inject constructor(
    @param:RestClient private val client: OkHttpClient,
    private val json: Json,
    private val endpointProvider: GatewayEndpointProvider,
    private val credentialProvider: ApiCredentialProvider,
) {
    suspend fun <T> request(
        path: String,
        deserializer: DeserializationStrategy<T>,
        method: String = "GET",
        query: Map<String, Any?> = emptyMap(),
        body: String? = null,
        headers: Map<String, String> = emptyMap(),
    ): T = withContext(Dispatchers.IO) {
        try {
            val initialToken = credentialProvider.tokenForRequest().requireUsableToken()
            val firstAttempt = execute(
                path = path,
                method = method,
                query = query,
                body = body,
                headers = headers,
                apiToken = initialToken,
            )

            if (firstAttempt.code == 401 && isGatewayUnauthorized(firstAttempt.body)) {
                // 服务端重启会清空纯内存 Token Store，即使客户端本地 TTL 尚未到期，也会收到
                // Gateway 自身的 Unauthorized。这里只对这一种明确语义刷新，避免把 Provider OAuth
                // 等业务接口返回的 401 错误地当作登录会话失效。
                val refreshedToken = credentialProvider
                    .tokenAfterUnauthorized(initialToken)
                    .requireUsableToken()
                val secondAttempt = execute(
                    path = path,
                    method = method,
                    query = query,
                    body = body,
                    headers = headers,
                    apiToken = refreshedToken,
                )
                if (secondAttempt.code == 401 && isGatewayUnauthorized(secondAttempt.body)) {
                    // 自动恢复严格限制为一次。第二次仍被 Gateway 拒绝时终止重试，防止服务端异常
                    // 或错误 Secret 形成无限 Bootstrap/REST 循环。
                    throw GatewayException.AuthenticationRequired("gateway token rejected after refresh")
                }
                return@withContext decodeResponse(secondAttempt, deserializer)
            }

            return@withContext decodeResponse(firstAttempt, deserializer)
        } catch (error: CancellationException) {
            throw error
        } catch (error: GatewayException) {
            throw error
        } catch (error: InterruptedIOException) {
            throw GatewayException.Timeout(error)
        } catch (error: IOException) {
            throw GatewayException.Network(error)
        }
    }

    private suspend fun execute(
        path: String,
        method: String,
        query: Map<String, Any?>,
        body: String?,
        headers: Map<String, String>,
        apiToken: String,
    ): RawResponse {
        val base = endpointProvider.baseUrl.trimEnd('/')
        val urlBuilder = (base + path).toHttpUrl().newBuilder()
        query.forEach { (key, value) ->
            // null 表示调用方没有提供该字段；空字符串则是有意清空服务端值，必须保留为
            // `key=`，否则逐字段 patch 无法清除已有配置。
            if (value != null) urlBuilder.addQueryParameter(key, value.toString())
        }

        val requestBuilder = Request.Builder()
            .url(urlBuilder.build())
            .header("Accept", "application/json")
        headers.forEach(requestBuilder::header)
        // Authorization 必须最后写入。业务调用方可以附加协议头，但不能覆盖凭据系统为本次
        // 请求选出的 Token，否则会绕过 TTL/401 恢复边界并重新制造陈旧 Token 问题。
        requestBuilder.header("Authorization", "Bearer $apiToken")

        val requestBody = body?.toRequestBody(JSON_MEDIA_TYPE)
        if (body != null) requestBuilder.header("Content-Type", "application/json")
        requestBuilder.method(
            method,
            when {
                method == "GET" || method == "HEAD" -> null
                requestBody != null -> requestBody
                else -> EMPTY_BODY
            },
        )

        return client.newCall(requestBuilder.build()).await().use { response ->
            RawResponse(
                code = response.code,
                contentType = response.header("Content-Type").orEmpty(),
                body = response.body?.string().orEmpty(),
            )
        }
    }

    private fun <T> decodeResponse(
        response: RawResponse,
        deserializer: DeserializationStrategy<T>,
    ): T {
        if (!response.isSuccessful) {
            // 401/403 不再笼统映射为“登录失效”。403 在设置、安全等接口中有真实业务含义；
            // 非 Gateway Unauthorized 的 401（例如 OAuth login failed）同样必须保留原状态码。
            throw GatewayException.Http(
                response.code,
                response.body.trim().ifBlank { "HTTP ${response.code}" },
            )
        }
        val contentType = response.contentType.lowercase()
        if (contentType.isNotBlank() && "application/json" !in contentType) {
            val normalized = response.body.trimStart().lowercase()
            if (normalized.startsWith("<!doctype") || normalized.startsWith("<html")) {
                throw GatewayException.HtmlResponse()
            }
            throw GatewayException.NonJsonResponse()
        }
        return try {
            json.decodeFromString(deserializer, response.body)
        } catch (error: Exception) {
            throw GatewayException.InvalidPayload(error)
        }
    }

    /**
     * 服务端 Gateway 鉴权失败的契约是 JSON `{"error":"Unauthorized"}`。
     *
     * 这里只接受字段值或兼容纯文本严格等于 `Unauthorized`，不做 contains/忽略大小写匹配，
     * 避免把第三方 OAuth、Provider 或业务正文中偶然出现的 unauthorized 单词误判为短期 Token 失效。
     */
    private fun isGatewayUnauthorized(body: String): Boolean {
        val normalized = body.trim()
        if (normalized == GATEWAY_UNAUTHORIZED) return true
        return runCatching {
            json.parseToJsonElement(normalized)
                .jsonObject["error"]
                ?.jsonPrimitive
                ?.contentOrNull == GATEWAY_UNAUTHORIZED
        }.getOrDefault(false)
    }

    suspend inline fun <reified T> get(path: String, query: Map<String, Any?> = emptyMap()): T =
        request(path, serializer(), query = query)

    suspend inline fun <reified T, reified B> post(path: String, body: B): T =
        request(path, serializer(), method = "POST", body = encode(body, serializer()))

    fun <B> encode(value: B, serializer: SerializationStrategy<B>): String =
        json.encodeToString(serializer, value)

    /**
     * 把 Gateway 返回的相对媒体路径补齐为 Android 图片/系统 Intent 可消费的绝对 URL。
     *
     * 这里只拼接公开的 base URL，不附加或暴露 API Token。`data:`、`content:` 与 `file:` URI
     * 已经是完整资源标识，必须原样保留；HTTP(S) 地址也不能被当前 Gateway origin 覆盖。
     */
    fun resolveUrl(value: String): String {
        val trimmed = value.trim()
        if (trimmed.isBlank()) return trimmed
        if (
            trimmed.startsWith("http://", ignoreCase = true) ||
                trimmed.startsWith("https://", ignoreCase = true) ||
                trimmed.startsWith("data:", ignoreCase = true) ||
                trimmed.startsWith("content:", ignoreCase = true) ||
                trimmed.startsWith("file:", ignoreCase = true)
        ) {
            return trimmed
        }
        val base = endpointProvider.baseUrl.trimEnd('/')
        return if (trimmed.startsWith('/')) "$base$trimmed" else "$base/$trimmed"
    }

    private data class RawResponse(
        val code: Int,
        val contentType: String,
        val body: String,
    ) {
        val isSuccessful: Boolean get() = code in 200..299
    }

    private fun String.requireUsableToken(): String =
        takeIf(String::isNotBlank) ?: throw GatewayException.AuthenticationRequired("empty api token")

    companion object {
        private const val GATEWAY_UNAUTHORIZED = "Unauthorized"
        private val JSON_MEDIA_TYPE = "application/json; charset=utf-8".toMediaType()
        private val EMPTY_BODY = ByteArray(0).toRequestBody(null)
    }
}
