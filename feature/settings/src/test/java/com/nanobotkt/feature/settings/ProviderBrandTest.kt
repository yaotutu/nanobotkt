package com.nanobotkt.feature.settings

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class ProviderBrandTest {
    @Test
    fun aliasesResolveToOfficialBrand() {
        assertEquals(providerBrand("brave"), providerBrand("brave_search"))
        assertEquals(providerBrand("minimax"), providerBrand("minimax_anthropic"))
        assertEquals(providerBrand("openai"), providerBrand("openai_codex"))
        assertEquals(providerBrand("xiaomi_mimo"), providerBrand("mimo"))
        assertEquals(providerBrand("xai"), providerBrand("xai_grok"))
    }

    @Test
    fun explicitOfficialAssetsStayFirst() {
        assertEquals(
            "https://www.longcatai.org/favicon.svg",
            providerBrand("longcat")?.logoUrls?.first(),
        )
        assertEquals(
            "https://www.stepfun.com/step_favicon.svg",
            providerBrand("stepfun")?.logoUrls?.first(),
        )
        assertEquals(
            "https://mimo.xiaomi.com/mimo-v2-pro/assets/logo.svg",
            providerBrand("xiaomi_mimo")?.logoUrls?.first(),
        )
        assertEquals(
            "https://z-cdn.chatglm.cn/z-ai/static/logo.svg",
            providerBrand("zhipu")?.logoUrls?.first(),
        )
    }

    @Test
    fun faviconFallbackOrderMatchesWebUi() {
        assertEquals(
            listOf(
                "https://favicon.im/openrouter.ai?larger=true",
                "https://www.google.com/s2/favicons?domain=openrouter.ai&sz=64",
                "https://icons.duckduckgo.com/ip3/openrouter.ai.ico",
                "https://openrouter.ai/favicon.ico",
            ),
            browserSafeFaviconUrls("openrouter.ai"),
        )
        assertTrue(providerBrand("assemblyai")?.logoUrls.orEmpty().contains("https://assemblyai.com/favicon.ico"))
    }

    @Test
    fun modelProviderInferenceMatchesWebUiRules() {
        assertEquals("anthropic", inferProviderFromModelName("claude-sonnet-4"))
        assertEquals("openai", inferProviderFromModelName("gpt-5"))
        assertEquals("dashscope", inferProviderFromModelName("qwen-max"))
        assertEquals("moonshot", inferProviderFromModelName("kimi-k2"))
        assertEquals("ant_ling", inferProviderFromModelName("ring-1t"))
        assertNull(inferProviderFromModelName("unknown-model"))
    }
}
