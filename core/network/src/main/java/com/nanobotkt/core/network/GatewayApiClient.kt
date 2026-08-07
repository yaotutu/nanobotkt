package com.nanobotkt.core.network

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.DeserializationStrategy
import kotlinx.serialization.SerializationStrategy
import kotlinx.serialization.json.Json
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
    private val authContext: AuthContext,
) {
    suspend fun <T> request(
        path: String,
        deserializer: DeserializationStrategy<T>,
        method: String = "GET",
        query: Map<String, Any?> = emptyMap(),
        body: String? = null,
        headers: Map<String, String> = emptyMap(),
    ): T = withContext(Dispatchers.IO) {
        val base = authContext.baseUrl.trimEnd('/')
        val urlBuilder = (base + path).toHttpUrl().newBuilder()
        query.forEach { (key, value) ->
            if (value != null && value.toString().isNotEmpty()) {
                urlBuilder.addQueryParameter(key, value.toString())
            }
        }
        val requestBuilder = Request.Builder()
            .url(urlBuilder.build())
            .header("Accept", "application/json")
        authContext.apiToken
            ?.takeIf(String::isNotBlank)
            ?.let { requestBuilder.header("Authorization", "Bearer $it") }
        headers.forEach(requestBuilder::header)
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

        try {
            client.newCall(requestBuilder.build()).await().use { response ->
                val responseText = response.body?.string().orEmpty()
                if (response.code == 401 || response.code == 403) {
                    throw GatewayException.AuthenticationRequired("authentication failed: HTTP ${response.code}")
                }
                if (!response.isSuccessful) {
                    throw GatewayException.Http(
                        response.code,
                        responseText.trim().ifBlank { "HTTP ${response.code}" },
                    )
                }
                val contentType = response.header("Content-Type").orEmpty().lowercase()
                if (contentType.isNotBlank() && "application/json" !in contentType) {
                    val normalized = responseText.trimStart().lowercase()
                    if (normalized.startsWith("<!doctype") || normalized.startsWith("<html")) {
                        throw GatewayException.HtmlResponse()
                    }
                    throw GatewayException.NonJsonResponse()
                }
                return@withContext try {
                    json.decodeFromString(deserializer, responseText)
                } catch (error: Exception) {
                    throw GatewayException.InvalidPayload(error)
                }
            }
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

    suspend inline fun <reified T> get(path: String, query: Map<String, Any?> = emptyMap()): T =
        request(path, serializer(), query = query)

    suspend inline fun <reified T, reified B> post(path: String, body: B): T =
        request(path, serializer(), method = "POST", body = encode(body, serializer()))

    fun <B> encode(value: B, serializer: SerializationStrategy<B>): String =
        json.encodeToString(serializer, value)

    companion object {
        private val JSON_MEDIA_TYPE = "application/json; charset=utf-8".toMediaType()
        private val EMPTY_BODY = ByteArray(0).toRequestBody(null)
    }
}
