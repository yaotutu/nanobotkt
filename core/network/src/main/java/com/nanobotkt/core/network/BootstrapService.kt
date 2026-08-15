package com.nanobotkt.core.network

import com.nanobotkt.core.model.BootstrapResponse
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.OkHttpClient
import okhttp3.Request
import java.io.IOException
import java.io.InterruptedIOException
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class BootstrapService @Inject constructor(
    @param:RestClient private val client: OkHttpClient,
    private val json: Json,
) {
    suspend fun fetch(baseUrl: String, secret: String): BootstrapResponse = withContext(Dispatchers.IO) {
        val request = Request.Builder()
            .url(baseUrl.trimEnd('/').plus("/webui/bootstrap").toHttpUrl())
            .header("Accept", "application/json")
            .apply { if (secret.isNotBlank()) header("X-Nanobot-Auth", secret) }
            .get()
            .build()
        try {
            client.newCall(request).await().use { response ->
                if (response.code == 401 || response.code == 403) throw GatewayException.AuthenticationRequired("bootstrap failed: HTTP ${response.code}")
                val text = response.body?.string().orEmpty()
                if (!response.isSuccessful) throw GatewayException.Http(response.code, text.trim().ifBlank { "HTTP ${response.code}" })
                val contentType = response.header("Content-Type").orEmpty().lowercase()
                if (contentType.isNotBlank() && "application/json" !in contentType) {
                    val normalized = text.trimStart().lowercase()
                    if (normalized.startsWith("<!doctype") || normalized.startsWith("<html")) throw GatewayException.HtmlResponse()
                    throw GatewayException.NonJsonResponse()
                }
                val payload = try { json.decodeFromString<BootstrapResponse>(text) }
                catch (error: Exception) { throw GatewayException.InvalidPayload(error) }
                if (payload.token.isBlank() || payload.apiToken.isBlank() || payload.wsPath.isBlank()) throw GatewayException.AuthenticationRequired("bootstrap response missing credentials")
                return@withContext payload
            }
        } catch (error: GatewayException) { throw error }
        catch (error: InterruptedIOException) {
            // OkHttp 的超时可能表现为 SocketTimeoutException 或其他 InterruptedIOException；
            // 两者都属于请求超时，必须与 GatewayApiClient 保持一致的错误语义。
            throw GatewayException.Timeout(error)
        }
        catch (error: IOException) { throw GatewayException.Network(error) }
    }

    fun deriveWebSocketUrl(baseUrl: String, payload: BootstrapResponse): String {
        // WebSocket 必须与 HTTP/Bootstrap 使用同一个用户已选择的 origin。服务端返回的
        // ws_url 可能包含 127.0.0.1、容器地址或其他仅服务端可见的监听主机，因此客户端
        // 绝不能直接采用它的 scheme/host/port；只使用 Bootstrap 明确下发的 ws_path 与 token。
        // OkHttp 使用 http/https URL 发起 WebSocket upgrade，所以无需手动转换为 ws/wss。
        val path = if (payload.wsPath.startsWith('/')) payload.wsPath else "/${payload.wsPath}"
        return baseUrl.toHttpUrl()
            .newBuilder()
            .encodedPath(path)
            .query(null)
            .fragment(null)
            .addQueryParameter("token", payload.token)
            .build()
            .toString()
    }
}
