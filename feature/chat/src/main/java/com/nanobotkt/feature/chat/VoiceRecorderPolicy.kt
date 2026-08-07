package com.nanobotkt.feature.chat

import kotlin.math.abs
import kotlin.math.floor
import kotlin.math.max
import kotlin.math.min
import kotlin.math.sin

enum class VoiceRecorderError {
    UNSUPPORTED,
    PERMISSION,
    NOT_CONFIGURED,
    TOO_LONG,
    TOO_SHORT,
    NO_INPUT,
    NO_DEVICE,
    FAILED,
}

internal const val MIN_RECORDING_MS = 650L
internal const val NO_INPUT_HINT_MS = 1_100L
internal const val DEFAULT_MAX_DURATION_SEC = 120
internal const val DEFAULT_MAX_UPLOAD_MB = 25
internal const val METERING_SILENCE_DB = -55.0
internal const val WAVEFORM_BARS = 16

internal fun voiceErrorFromUnknown(error: Any?): VoiceRecorderError {
    val message = when (error) {
        is Throwable -> error.message.orEmpty()
        else -> error.toString()
    }.lowercase()
    return when {
        "permission" in message || "denied" in message || "not allowed" in message -> VoiceRecorderError.PERMISSION
        "not_configured" in message || "disabled" in message -> VoiceRecorderError.NOT_CONFIGURED
        "duration" in message || "too_long" in message -> VoiceRecorderError.TOO_LONG
        "missing_audio" in message || "empty" in message -> VoiceRecorderError.NO_INPUT
        "no device" in message || "no_device" in message || "input" in message -> VoiceRecorderError.NO_DEVICE
        else -> VoiceRecorderError.FAILED
    }
}

internal fun meteringLevel(db: Double?): Double {
    if (db == null || !db.isFinite()) return 0.08
    return max(0.06, min(1.0, (db + 60.0) / 60.0))
}

internal fun waveformFromMetering(
    db: Double?,
    durationMs: Long,
    bars: Int = WAVEFORM_BARS,
): List<Double> {
    val level = meteringLevel(db)
    val phase = floor(durationMs / 80.0).toInt()
    return List(bars.coerceAtLeast(0)) { index ->
        val ripple = 0.52 + abs(sin((index + phase) * 0.82)) * 0.48
        max(0.06, min(1.0, level * ripple))
    }
}

internal fun boundedVoiceDurationSec(value: Int?): Int =
    (value?.takeIf { it != 0 } ?: DEFAULT_MAX_DURATION_SEC).coerceIn(1, 600)

internal fun boundedVoiceUploadMb(value: Int?): Int =
    max(1, value?.takeIf { it != 0 } ?: DEFAULT_MAX_UPLOAD_MB)

internal data class RecordingAnalysis(
    var levelObserved: Boolean = false,
    var peakDb: Double = Double.NEGATIVE_INFINITY,
    var noInputHintVisible: Boolean = false,
)

internal fun RecordingAnalysis.observe(meteringDb: Double): Boolean {
    levelObserved = true
    peakDb = max(peakDb, meteringDb)
    if (noInputHintVisible && meteringDb >= METERING_SILENCE_DB) {
        noInputHintVisible = false
        return true
    }
    return false
}

internal fun RecordingAnalysis.shouldShowNoInputHint(): Boolean {
    val silent = levelObserved && peakDb < METERING_SILENCE_DB
    noInputHintVisible = silent
    return silent
}

internal fun recordingStopError(
    durationMs: Long,
    maxReached: Boolean,
    analysis: RecordingAnalysis,
): VoiceRecorderError? = when {
    maxReached -> VoiceRecorderError.TOO_LONG
    durationMs < MIN_RECORDING_MS -> VoiceRecorderError.TOO_SHORT
    analysis.levelObserved && analysis.peakDb < METERING_SILENCE_DB -> VoiceRecorderError.NO_INPUT
    else -> null
}

