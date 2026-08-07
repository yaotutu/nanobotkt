package com.nanobotkt.feature.chat

import android.content.Context
import android.media.MediaRecorder
import android.os.Build
import android.os.SystemClock
import android.util.Base64
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File
import javax.inject.Inject
import kotlin.math.log10

interface VoiceRecorder {
    fun start(maxDurationSec: Int = DEFAULT_MAX_DURATION_SEC, maxUploadMb: Int = DEFAULT_MAX_UPLOAD_MB)
    fun durationMs(): Long
    fun meteringDb(): Double?
    fun maxReached(): Boolean
    suspend fun stopAndEncode(): EncodedVoiceRecording
    fun cancel()
}

class NativeVoiceRecorder @Inject constructor(
    @param:ApplicationContext private val context: Context,
) : VoiceRecorder {
    private var active: ActiveRecording? = null

    @Synchronized
    override fun start(
        maxDurationSec: Int,
        maxUploadMb: Int,
    ) {
        check(active == null) { "recorder_already_active" }
        val output = File.createTempFile("nanobot-voice-", ".m4a", context.cacheDir)
        val recorder = createRecorder()
        val recording = ActiveRecording(
            recorder = recorder,
            file = output,
            startedAtMs = SystemClock.elapsedRealtime(),
        )
        try {
            recorder.setAudioSource(MediaRecorder.AudioSource.MIC)
            recorder.setOutputFormat(MediaRecorder.OutputFormat.MPEG_4)
            recorder.setAudioEncoder(MediaRecorder.AudioEncoder.AAC)
            recorder.setAudioSamplingRate(44_100)
            recorder.setAudioEncodingBitRate(96_000)
            recorder.setOutputFile(output.absolutePath)
            recorder.setMaxDuration(boundedVoiceDurationSec(maxDurationSec) * 1_000)
            recorder.setMaxFileSize(boundedVoiceUploadMb(maxUploadMb).toLong() * 1024 * 1024)
            recorder.setOnInfoListener { _, what, _ ->
                if (
                    what == MediaRecorder.MEDIA_RECORDER_INFO_MAX_DURATION_REACHED ||
                    what == MediaRecorder.MEDIA_RECORDER_INFO_MAX_FILESIZE_REACHED
                ) {
                    synchronized(this) { active?.maxReached = true }
                }
            }
            recorder.prepare()
            recorder.start()
            active = recording
        } catch (error: Throwable) {
            runCatching { recorder.release() }
            output.delete()
            throw error
        }
    }

    @Synchronized
    override fun durationMs(): Long = active?.let { SystemClock.elapsedRealtime() - it.startedAtMs } ?: 0L

    @Synchronized
    override fun meteringDb(): Double? {
        val amplitude = runCatching { active?.recorder?.maxAmplitude }.getOrNull() ?: return null
        if (amplitude <= 0) return -160.0
        return 20.0 * log10(amplitude.toDouble() / 32_767.0)
    }

    @Synchronized
    override fun maxReached(): Boolean = active?.maxReached == true

    override suspend fun stopAndEncode(): EncodedVoiceRecording {
        val stopped = synchronized(this) {
            val recording = active ?: error("recorder_not_active")
            active = null
            val duration = SystemClock.elapsedRealtime() - recording.startedAtMs
            try {
                recording.recorder.stop()
            } catch (error: RuntimeException) {
                recording.file.delete()
                throw IllegalStateException("missing_audio", error)
            } finally {
                recording.recorder.release()
            }
            StoppedRecording(recording.file, duration, recording.maxReached)
        }
        return withContext(Dispatchers.IO) {
            try {
                val bytes = stopped.file.readBytes()
                check(bytes.isNotEmpty()) { "missing_audio" }
                EncodedVoiceRecording(
                    dataUrl = "data:audio/m4a;base64," + Base64.encodeToString(bytes, Base64.NO_WRAP),
                    durationMs = stopped.durationMs,
                    maxReached = stopped.maxReached,
                    bytes = bytes.size.toLong(),
                )
            } finally {
                stopped.file.delete()
            }
        }
    }

    @Synchronized
    override fun cancel() {
        val recording = active ?: return
        active = null
        runCatching { recording.recorder.stop() }
        runCatching { recording.recorder.release() }
        recording.file.delete()
    }

    @Suppress("DEPRECATION")
    private fun createRecorder(): MediaRecorder =
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) MediaRecorder(context) else MediaRecorder()

    private data class ActiveRecording(
        val recorder: MediaRecorder,
        val file: File,
        val startedAtMs: Long,
        var maxReached: Boolean = false,
    )

    private data class StoppedRecording(
        val file: File,
        val durationMs: Long,
        val maxReached: Boolean,
    )
}

data class EncodedVoiceRecording(
    val dataUrl: String,
    val durationMs: Long,
    val maxReached: Boolean,
    val bytes: Long,
)

