package com.nanobotkt.feature.settings

internal data class ProviderBrand(
    val logoUrls: List<String>,
    val color: Long,
    val initials: String,
)

private data class ProviderBrandDefinition(
    val domain: String,
    val color: Long,
    val initials: String,
    val logoOverrides: List<String> = emptyList(),
)

private val providerBrandAliases = mapOf(
    "brave_search" to "brave",
    "byteplus_coding_plan" to "byteplus",
    "mimo" to "xiaomi_mimo",
    "minimaxAnthropic" to "minimax",
    "minimax_anthropic" to "minimax",
    "openai_codex" to "openai",
    "xai-grok" to "xai",
    "xai_grok" to "xai",
    "xiaomi" to "xiaomi_mimo",
    "volcengine_coding_plan" to "volcengine",
)

private val providerBrandDefinitions = mapOf(
    "aihubmix" to definition("aihubmix.com", 0x111827, "AH"),
    "ant_ling" to definition("ant-ling.com", 0x7C3AED, "AL"),
    "anthropic" to definition("anthropic.com", 0xD97757, "A"),
    "assemblyai" to definition("assemblyai.com", 0x111827, "AA"),
    "atomic_chat" to definition("atomic.chat", 0x111827, "AC"),
    "azure_openai" to definition("azure.microsoft.com", 0x0078D4, "AZ"),
    "bedrock" to definition("aws.amazon.com", 0xFF9900, "AWS"),
    "bocha" to definition("bochaai.com", 0x2563EB, "B"),
    "brave" to definition("brave.com", 0xFB542B, "B"),
    "byteplus" to definition("byteplus.com", 0x325CFF, "BP"),
    "dashscope" to definition("dashscope.aliyun.com", 0xFF6A00, "DS"),
    "deepseek" to definition("deepseek.com", 0x4D6BFE, "DS"),
    "duckduckgo" to definition("duckduckgo.com", 0xDE5833, "DDG"),
    "exa" to definition("exa.ai", 0x5B5BF6, "E"),
    "gemini" to definition("gemini.google.com", 0x4285F4, "G"),
    "github_copilot" to definition("github.com", 0x24292F, "GH"),
    "groq" to definition("groq.com", 0xF55036, "GQ"),
    "huggingface" to definition("huggingface.co", 0xFF9D00, "HF"),
    "jina" to definition("jina.ai", 0x7C3AED, "J"),
    "kagi" to definition("kagi.com", 0xFFB319, "K"),
    "keenable" to definition("keenable.ai", 0x0EA5E9, "K"),
    "lm_studio" to definition("lmstudio.ai", 0x111827, "LM"),
    "longcat" to definition(
        "longcatai.org",
        0x4F8CFF,
        "LC",
        "https://www.longcatai.org/favicon.svg",
    ),
    "minimax" to definition("minimax.io", 0x111827, "MM"),
    "mistral" to definition("mistral.ai", 0xFA520F, "M"),
    "modelscope" to definition("modelscope.cn", 0x5B5BF6, "MS"),
    "moonshot" to definition("moonshot.ai", 0x111827, "MS"),
    "novita" to definition("novita.ai", 0x7C3AED, "N"),
    "olostep" to definition("olostep.com", 0x111827, "O"),
    "nvidia" to definition("nvidia.com", 0x76B900, "NV"),
    "ollama" to definition("ollama.com", 0x111827, "O"),
    "openai" to definition("openai.com", 0x111827, "AI"),
    "openrouter" to definition("openrouter.ai", 0x111827, "OR"),
    "ovms" to definition("openvino.ai", 0x0071C5, "OV"),
    "qianfan" to definition("cloud.baidu.com", 0x2932E1, "QF"),
    "searxng" to definition("searxng.org", 0x3050FF, "SX"),
    "siliconflow" to definition("siliconflow.cn", 0x111827, "SF"),
    "skywork" to definition("skywork.ai", 0x5B5BF6, "SW"),
    "stepfun" to definition(
        "stepfun.com",
        0x2F6BFF,
        "SF",
        "https://www.stepfun.com/step_favicon.svg",
    ),
    "tavily" to definition("tavily.com", 0x111827, "T"),
    "volcengine" to definition("volcengine.com", 0x1664FF, "VE"),
    "vllm" to definition("vllm.ai", 0x2563EB, "VL"),
    "xiaomi_mimo" to definition(
        "mimo.xiaomi.com",
        0xFF6900,
        "MI",
        "https://mimo.xiaomi.com/mimo-v2-pro/assets/logo.svg",
    ),
    "xai" to definition("x.ai", 0x111827, "xAI"),
    "zhipu" to definition(
        "z.ai",
        0x155EEF,
        "Z",
        "https://z-cdn.chatglm.cn/z-ai/static/logo.svg",
        "https://www.google.com/s2/favicons?domain=z.ai&sz=64",
    ),
)

private fun definition(
    domain: String,
    color: Long,
    initials: String,
    vararg logoOverrides: String,
) = ProviderBrandDefinition(domain, color, initials, logoOverrides.toList())

internal fun providerBrand(provider: String?): ProviderBrand? {
    if (provider.isNullOrBlank()) return null
    val key = providerBrandAliases[provider] ?: provider
    val definition = providerBrandDefinitions[key] ?: return null
    return ProviderBrand(
        logoUrls = (definition.logoOverrides + browserSafeFaviconUrls(definition.domain)).distinct(),
        color = 0xFF000000L or definition.color,
        initials = definition.initials,
    )
}

internal fun browserSafeFaviconUrls(domain: String): List<String> = listOf(
    "https://favicon.im/$domain?larger=true",
    "https://www.google.com/s2/favicons?domain=$domain&sz=64",
    "https://icons.duckduckgo.com/ip3/$domain.ico",
    "https://$domain/favicon.ico",
)

internal fun inferProviderFromModelName(modelName: String?): String? {
    val normalized = modelName.orEmpty().trim().lowercase()
    if (normalized.isBlank()) return null
    val prefix = normalized.split('/', ':').firstOrNull()
    if (providerBrand(prefix) != null) return prefix
    return when {
        "claude" in normalized || "anthropic" in normalized -> "anthropic"
        Regex("gpt-|^o\\d|chatgpt|openai").containsMatchIn(normalized) -> "openai"
        "deepseek" in normalized -> "deepseek"
        "gemini" in normalized -> "gemini"
        "modelscope" in normalized -> "modelscope"
        "qwen" in normalized || "dashscope" in normalized -> "dashscope"
        "kimi" in normalized || "moonshot" in normalized -> "moonshot"
        "minimax" in normalized -> "minimax"
        "mistral" in normalized || "mixtral" in normalized -> "mistral"
        "skywork" in normalized || "skyclaw" in normalized -> "skywork"
        "ring-" in normalized -> "ant_ling"
        else -> null
    }
}
