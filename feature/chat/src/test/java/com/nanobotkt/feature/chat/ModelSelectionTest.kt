package com.nanobotkt.feature.chat

import com.nanobotkt.core.model.ModelPresetInfo
import org.junit.Assert.assertEquals
import org.junit.Test

class ModelSelectionTest {
    @Test
    fun `active preset follows local session settings and default priority`() {
        assertEquals(
            "local",
            resolveActiveModelPreset(
                scopeKey = "websocket:chat",
                localSelection = LocalModelSelection("websocket:chat", "local"),
                sessionModelPreset = "session",
                settingsModelPreset = "settings",
            ),
        )
        assertEquals(
            "session",
            resolveActiveModelPreset(
                scopeKey = "websocket:chat",
                localSelection = null,
                sessionModelPreset = "session",
                settingsModelPreset = "settings",
            ),
        )
        assertEquals(
            "settings",
            resolveActiveModelPreset(
                scopeKey = "websocket:chat",
                localSelection = null,
                sessionModelPreset = " ",
                settingsModelPreset = "settings",
            ),
        )
        assertEquals(
            DEFAULT_MODEL_PRESET,
            resolveActiveModelPreset(
                scopeKey = "websocket:chat",
                localSelection = null,
                sessionModelPreset = null,
                settingsModelPreset = null,
            ),
        )
    }

    @Test
    fun `local preset for another scope is ignored`() {
        assertEquals(
            "session",
            resolveActiveModelPreset(
                scopeKey = "websocket:active",
                localSelection = LocalModelSelection("websocket:other", "local"),
                sessionModelPreset = "session",
                settingsModelPreset = "settings",
            ),
        )
    }

    @Test
    fun `call order sorts known presets then keeps unknown relative order`() {
        val presets = listOf(
            preset("unknown-a"),
            preset("slow"),
            preset("unknown-b"),
            preset("fast"),
        )

        val ordered = orderedModelPresets(presets, listOf("fast", "slow"))

        assertEquals(
            listOf("fast", "slow", "unknown-a", "unknown-b"),
            ordered.map(ModelPresetInfo::name),
        )
    }

    @Test
    fun `display label follows preset turn runtime bootstrap active and nanobot priority`() {
        val presets = listOf(preset("fast", label = "Fast preset"))

        assertEquals(
            "Fast preset",
            resolveModelDisplayLabel("fast", presets, "turn", "runtime", "bootstrap"),
        )
        assertEquals(
            "turn",
            resolveModelDisplayLabel("missing", presets, "turn", "runtime", "bootstrap"),
        )
        assertEquals(
            "runtime",
            resolveModelDisplayLabel("missing", presets, " ", "runtime", "bootstrap"),
        )
        assertEquals(
            "bootstrap",
            resolveModelDisplayLabel("missing", presets, null, " ", "bootstrap"),
        )
        assertEquals(
            "missing",
            resolveModelDisplayLabel("missing", presets, null, null, " "),
        )
        assertEquals(
            NANOBOT_MODEL_FALLBACK,
            resolveModelDisplayLabel(" ", presets, null, null, null),
        )
    }

    private fun preset(
        name: String,
        label: String = name,
    ) = ModelPresetInfo(
        name = name,
        label = label,
        active = false,
        isDefault = false,
        model = "model-$name",
        provider = "provider-$name",
    )
}