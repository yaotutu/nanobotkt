package com.nanobotkt.feature.chat

import org.junit.Assert.assertEquals
import org.junit.Test

class MediaKindInferenceTest {
    @Test
    fun `recognizes audio from filename and signed URL without MIME`() {
        assertEquals("audio", inferTimelineMediaKind(name = "output.wav"))
        assertEquals("audio", inferTimelineMediaKind(name = "OUTPUT.WAV"))
        assertEquals(
            "audio",
            inferTimelineMediaKind(url = "https://example.invalid/output.wav?token=secret#fragment"),
        )
        assertEquals(
            "audio",
            inferTimelineMediaKind(
                url = "https://example.invalid/download",
                name = "generated-output.wav",
            ),
        )
    }

    @Test
    fun `generic declared kind and filename still fall back to URL extension`() {
        assertEquals(
            "audio",
            inferTimelineMediaKind(
                declaredKind = "file",
                name = "download.bin",
                url = "https://example.invalid/result.wav?token=secret",
            ),
        )
        assertEquals("video", inferTimelineMediaKind(name = "preview.mp4"))
        assertEquals("file", inferTimelineMediaKind(name = "archive.xlsx"))
    }

    @Test
    fun `declared media kind and data URL MIME take precedence`() {
        assertEquals("image", inferTimelineMediaKind(declaredKind = "image/png", name = "wrong.wav"))
        assertEquals("audio", inferTimelineMediaKind(declaredKind = "audio", name = "wrong.png"))
        assertEquals("video", inferTimelineMediaKind(declaredKind = "video/mp4", name = "wrong.txt"))
        assertEquals("audio", inferTimelineMediaKind(url = "data:audio/wav;base64,UklGRg=="))
    }
}
