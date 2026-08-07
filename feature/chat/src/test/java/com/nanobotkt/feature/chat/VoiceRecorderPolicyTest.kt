package com.nanobotkt.feature.chat

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class VoiceRecorderPolicyTest {
    @Test
    fun `maps native and gateway errors to stable UI errors`() {
        assertEquals(VoiceRecorderError.PERMISSION, voiceErrorFromUnknown(IllegalStateException("Permission denied")))
        assertEquals(VoiceRecorderError.NOT_CONFIGURED, voiceErrorFromUnknown("not_configured"))
        assertEquals(VoiceRecorderError.NO_INPUT, voiceErrorFromUnknown("missing_audio"))
        assertEquals(VoiceRecorderError.FAILED, voiceErrorFromUnknown("unexpected"))
    }

    @Test
    fun `bounds runtime limits`() {
        assertEquals(120, boundedVoiceDurationSec(0))
        assertEquals(600, boundedVoiceDurationSec(999))
        assertEquals(1, boundedVoiceUploadMb(-2))
    }

    @Test
    fun `tracks silence and chooses stop validation errors in priority order`() {
        val analysis = RecordingAnalysis()
        analysis.observe(-70.0)
        assertTrue(analysis.shouldShowNoInputHint())
        assertEquals(VoiceRecorderError.NO_INPUT, recordingStopError(1_000, false, analysis))
        assertEquals(VoiceRecorderError.TOO_LONG, recordingStopError(100, true, analysis))

        assertTrue(analysis.observe(-20.0))
        assertEquals(VoiceRecorderError.TOO_SHORT, recordingStopError(100, false, analysis))
        assertNull(recordingStopError(1_000, false, analysis))
    }

    @Test
    fun `creates bounded waveform levels`() {
        assertEquals(0.08, meteringLevel(null), 0.0)
        assertEquals(0.06, meteringLevel(-60.0), 0.0)
        val waveform = waveformFromMetering(-12.0, 640, 8)
        assertEquals(8, waveform.size)
        assertTrue(waveform.all { it in 0.06..1.0 })
    }
}
