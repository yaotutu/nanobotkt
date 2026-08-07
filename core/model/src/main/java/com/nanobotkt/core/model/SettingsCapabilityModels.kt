package com.nanobotkt.core.model

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

@Serializable
data class WebSearchProviderInfo(
    val name: String = "",
    val label: String = name,
    val credential: String = "none",
)

@Serializable
data class WebSearchSettings(
    val provider: String = "duckduckgo",
    @SerialName("api_key_hint") val apiKeyHint: String? = null,
    @SerialName("base_url") val baseUrl: String? = null,
    @SerialName("max_results") val maxResults: Int = 5,
    val timeout: Int = 30,
    val providers: List<WebSearchProviderInfo> = emptyList(),
)

@Serializable
data class WebSearchBehavior(
    @SerialName("max_results") val maxResults: Int = 5,
    val timeout: Int = 30,
)

@Serializable
data class WebFetchSettings(
    @SerialName("use_jina_reader") val useJinaReader: Boolean = true,
)

@Serializable
data class WebSettings(
    val enable: Boolean = true,
    val proxy: String? = null,
    @SerialName("user_agent") val userAgent: String? = null,
    val search: WebSearchBehavior = WebSearchBehavior(),
    val fetch: WebFetchSettings = WebFetchSettings(),
)

@Serializable
data class ImageGenerationProviderInfo(
    val name: String = "",
    val label: String = name,
    val configured: Boolean = false,
    @SerialName("auth_type") val authType: String? = null,
    @SerialName("api_key_hint") val apiKeyHint: String? = null,
    @SerialName("api_base") val apiBase: String? = null,
    @SerialName("default_api_base") val defaultApiBase: String? = null,
    val models: List<String> = emptyList(),
    @SerialName("default_model") val defaultModel: String? = null,
)

@Serializable
data class ImageGenerationSettings(
    val enabled: Boolean = false,
    val provider: String = "openrouter",
    @SerialName("provider_configured") val providerConfigured: Boolean = false,
    val model: String = "openai/gpt-5.4-image-2",
    @SerialName("default_aspect_ratio") val defaultAspectRatio: String = "1:1",
    @SerialName("default_image_size") val defaultImageSize: String = "1K",
    @SerialName("max_images_per_turn") val maxImagesPerTurn: Int = 4,
    @SerialName("save_dir") val saveDir: String = "",
    val providers: List<ImageGenerationProviderInfo> = emptyList(),
)

@Serializable
data class TranscriptionProviderInfo(
    val name: String = "",
    val label: String = name,
    val configured: Boolean = false,
    @SerialName("api_key_hint") val apiKeyHint: String? = null,
    @SerialName("api_base") val apiBase: String? = null,
    @SerialName("default_api_base") val defaultApiBase: String? = null,
)

@Serializable
data class TranscriptionSettings(
    val enabled: Boolean = true,
    val provider: String = "groq",
    @SerialName("provider_configured") val providerConfigured: Boolean = false,
    val model: String = "whisper-large-v3",
    val language: String? = null,
    @SerialName("max_duration_sec") val maxDurationSec: Int = 120,
    @SerialName("max_upload_mb") val maxUploadMb: Int = 25,
    val providers: List<TranscriptionProviderInfo> = emptyList(),
)
