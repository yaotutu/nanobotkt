package com.nanobotkt.core.network

import com.nanobotkt.core.model.BootstrapResponse
import kotlinx.serialization.json.Json
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.OkHttpClient
import okhttp3.Request
import java.io.IOException
import java.net.SocketTimeoutException
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class BootstrapService @Inject constructor(
    @param:RestClient private val client: OkHttpClient,
    private val json: Json,
) {
    suspend fun fetch(baseUrl: String, secret: String): BootstrapResponse {
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
                return payload
            }
        } catch (error: GatewayException) { throw error }
        catch (error: SocketTimeoutException) { throw GatewayException.Timeout(error) }
        catch (error: IOException) { throw GatewayException.Network(error) }
    }

    fun deriveWebSocketUrl(baseUrl: String, payload: BootstrapResponse): String {
        // OkHttp's HttpUrl only accepts http/https schemes (the WebSocket upgrade
        // is handled transparently), so map ws/wss back to http/https.
        val base = payload.wsUrl?.takeIf { url ->
            url.startsWith("ws://", ignoreCase = true) || url.startsWith("wss://", ignoreCase = true)
        }?.let { wsUrl ->
            val secure = wsUrl.startsWith("wss://", ignoreCase = true)
            wsUrl.replaceFirst(if (secure) "wss" else "ws", if (secure) "https" else "http", ignoreCase = true)
        } ?: run {
            val http = baseUrl.toHttpUrl()
            http.newBuilder()
                .encodedPath(if (payload.wsPath.startsWith('/')) payload.wsPath else "/${payload.wsPath}")
                .build().toString()
        }
        return base.toHttpUrl().newBuilder().addQueryParameter("token", payload.token).build().toString()
    }
}
