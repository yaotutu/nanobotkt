package com.nanobotkt.feature.chat

import com.nanobotkt.core.model.ModelPresetInfo

data class ChatModelSelection(
    val activePreset: String = DEFAULT_MODEL_PRESET,
    val displayLabel: String = NANOBOT_MODEL_FALLBACK,
    val presets: List<ModelPresetInfo> = emptyList(),
    val pendingPreset: String? = null,
    val error: String? = null,
    val enabled: Boolean = false,
)

internal data class LocalModelSelection(
    val scopeKey: String,
    val preset: String,
)

internal const val NEW_TOPIC_MODEL_SCOPE = "__new__"
internal const val DEFAULT_MODEL_PRESET = "default"
internal const val NANOBOT_MODEL_FALLBACK = "nanobot"

internal fun orderedModelPresets(
    presets: List<ModelPresetInfo>,
    callOrder: List<String>,
): List<ModelPresetInfo> {
    val order = callOrder.mapIndexed { index, name -> name.trim() to index }.toMap()
    return presets.sortedBy { preset -> order[preset.name.trim()] ?: Int.MAX_VALUE }
}

internal fun resolveActiveModelPreset(
    scopeKey: String,
    localSelection: LocalModelSelection?,
    sessionModelPreset: String?,
    settingsModelPreset: String?,
): String = sequenceOf(
    localSelection?.takeIf { it.scopeKey == scopeKey }?.preset,
    sessionModelPreset,
    settingsModelPreset,
    DEFAULT_MODEL_PRESET,
).mapNotNull { it?.trim()?.takeIf(String::isNotEmpty) }
    .first()

internal fun resolveModelDisplayLabel(
    activePreset: String,
    presets: List<ModelPresetInfo>,
    turnModelName: String?,
    runtimeModelName: String?,
    bootstrapModelName: String?,
): String {
    val presetLabel = presets.firstOrNull { it.name == activePreset }
        ?.label
        ?.trim()
        ?.takeIf(String::isNotEmpty)
    return sequenceOf(
        presetLabel,
        turnModelName,
        runtimeModelName,
        bootstrapModelName,
        activePreset,
        NANOBOT_MODEL_FALLBACK,
    ).mapNotNull { it?.trim()?.takeIf(String::isNotEmpty) }
        .first()
}