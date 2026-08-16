package com.nanobotkt.feature.chat

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch

/**
 * 语音录制生命周期协调器。
 *
 * Recorder 的启动/停止、80ms 计时、静音分析和转写属于同一副作用边界，集中在此处能保证
 * 会话切换时由 [reset] 同时取消计时与原生录音。Composer 只接收最终状态变换，不感知设备 API。
 */
internal class ChatVoiceCoordinator(
    private val scope: CoroutineScope,
    private val recorder: VoiceRecorder,
    private val composer: ComposerStateCoordinator,
    private val transcribe: suspend (dataUrl: String, durationMs: Long) -> String,
) {
    private var timer: Job? = null
    private var analysis = RecordingAnalysis()

    fun start(permissionGranted: Boolean) {
        val voice = composer.value.voice
        if (voice.isRecording || voice.isTranscribing) return
        if (!permissionGranted) {
            updateVoice { current -> current.copy(error = VoiceRecorderError.PERMISSION) }
            return
        }

        analysis = RecordingAnalysis()
        runCatching { recorder.start(DEFAULT_MAX_DURATION_SEC, DEFAULT_MAX_UPLOAD_MB) }
            .onFailure { error ->
                updateVoice { current -> current.copy(error = voiceErrorFromUnknown(error)) }
                return
            }
        updateVoice { VoiceUiState(isRecording = true, waveform = waveformFromMetering(null, 0)) }
        timer?.cancel()
        timer = scope.launch {
            while (isActive && composer.value.voice.isRecording) {
                delay(80)
                val duration = recorder.durationMs()
                val metering = recorder.meteringDb()
                if (metering != null) analysis.observe(metering)
                val noInputHint = duration >= NO_INPUT_HINT_MS && analysis.shouldShowNoInputHint()
                updateVoice { current ->
                    current.copy(
                        durationMs = duration,
                        waveform = waveformFromMetering(metering, duration),
                        noInputHint = noInputHint,
                    )
                }
                if (
                    recorder.maxReached() ||
                    duration >= boundedVoiceDurationSec(DEFAULT_MAX_DURATION_SEC) * 1_000L
                ) {
                    launch { stop(maxReached = true) }
                    break
                }
            }
        }
    }

    fun stop(cancelled: Boolean = false, maxReached: Boolean = false) {
        if (!composer.value.voice.isRecording) return
        timer?.cancel()
        timer = null
        if (cancelled) {
            recorder.cancel()
            updateVoice { VoiceUiState() }
            return
        }

        updateVoice { current -> current.copy(isRecording = false, isTranscribing = true, noInputHint = false) }
        val requestEpoch = composer.epoch
        scope.launch {
            try {
                val recording = recorder.stopAndEncode()
                if (!composer.isCurrent(requestEpoch)) return@launch
                val validationError = recordingStopError(
                    durationMs = recording.durationMs,
                    maxReached = maxReached || recording.maxReached,
                    analysis = analysis,
                )
                if (validationError != null) {
                    updateVoice { VoiceUiState(error = validationError) }
                    return@launch
                }
                val transcript = transcribe(recording.dataUrl, recording.durationMs).trim()
                if (!composer.isCurrent(requestEpoch)) return@launch
                if (transcript.isEmpty()) error("missing_audio")
                val nextText = listOf(composer.value.text.trimEnd(), transcript)
                    .filter(String::isNotBlank)
                    .joinToString(" ")
                composer.update { current ->
                    current.copy(
                        text = nextText,
                        cursorPosition = nextText.length,
                        voice = VoiceUiState(),
                        error = null,
                    )
                }
            } catch (error: CancellationException) {
                // ViewModel 销毁或会话任务取消必须保留结构化并发语义，不能显示为录音失败。
                throw error
            } catch (error: Throwable) {
                if (composer.isCurrent(requestEpoch)) {
                    updateVoice { VoiceUiState(error = voiceErrorFromUnknown(error)) }
                }
            }
        }
    }

    fun reset() {
        timer?.cancel()
        timer = null
        recorder.cancel()
        analysis = RecordingAnalysis()
    }

    fun close() = reset()

    private fun updateVoice(transform: (VoiceUiState) -> VoiceUiState) {
        composer.update { current -> current.copy(voice = transform(current.voice)) }
    }
}
