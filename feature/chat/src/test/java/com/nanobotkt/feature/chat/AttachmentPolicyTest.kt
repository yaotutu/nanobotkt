package com.nanobotkt.feature.chat

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class AttachmentPolicyTest {
    @Test
    fun `normalizes supported document MIME types by extension`() {
        assertEquals("text/markdown", canonicalDocumentMime("README.MD", "application/octet-stream"))
        assertEquals("application/json", canonicalDocumentMime("payload.unknown", "application/json; charset=utf-8"))
        assertNull(canonicalDocumentMime("payload.bin", "application/octet-stream"))
    }

    @Test
    fun `sniffs supported image signatures`() {
        assertEquals("image/jpeg", sniffImageMime(byteArrayOf(0xff.toByte(), 0xd8.toByte(), 0xff.toByte())))
        assertEquals("image/gif", sniffImageMime(byteArrayOf(0x47, 0x49, 0x46, 0x38, 0x39, 0x61)))
        assertNull(sniffImageMime(byteArrayOf(1, 2, 3)))
    }

    @Test
    fun `calculates encoded sizes and safe ingress defaults`() {
        assertEquals(3L, decodedBase64Bytes("YWJj"))
        assertEquals(1L, decodedBase64Bytes("YQ=="))
        assertEquals("data:text/plain;base64,".length + 4L, projectedDataUrlBytes("text/plain", 3))
        assertEquals(12L, positiveLimit(-1, 12))
        val limits = ingressLimits(null)
        assertEquals(
            limits.maxFrameBytes - limits.envelopeReserveBytes - limits.maxTextBytes,
            attachmentPayloadBudget(limits),
        )
        assertTrue(limits.maxCount > 0)
    }
    @Test
    fun `rejects empty per-file total and frame overflow in stable order`() {
        val limits = AttachmentPolicyLimits(
            maxCount = 2,
            maxFileBytes = 10,
            maxTotalBytes = 16,
            maxFrameBytes = 100,
            envelopeReserveBytes = 10,
            maxTextBytes = 20,
        )
        assertEquals("empty_file", validateEncodedAttachmentMetrics(0, 0, 0, 0, 0, limits))
        assertEquals("too_large", validateEncodedAttachmentMetrics(0, 0, 0, 11, 20, limits))
        assertEquals("total_too_large", validateEncodedAttachmentMetrics(1, 10, 20, 7, 12, limits))
        assertEquals("transport_too_large", validateEncodedAttachmentMetrics(1, 5, 65, 5, 6, limits))
        assertEquals("too_many_attachments", validateEncodedAttachmentMetrics(2, 0, 0, 1, 1, limits))
    }

    @Test
    fun `rejects image signatures that do not match supported formats`() {
        assertNull(sniffImageMime("not an image".encodeToByteArray()))
        assertNull(sniffImageMime(byteArrayOf(0x89.toByte(), 0x50, 0x4e, 0x47)))
    }
}
