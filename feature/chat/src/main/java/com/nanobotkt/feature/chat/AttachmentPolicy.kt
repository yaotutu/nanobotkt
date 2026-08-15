package com.nanobotkt.feature.chat

import com.nanobotkt.core.model.WebUiIngressLimits
import kotlin.math.floor

internal const val DEFAULT_MAX_ATTACHMENT_COUNT = 4
internal const val DEFAULT_MAX_FILE_BYTES = 6L * 1024 * 1024
internal const val DEFAULT_MAX_TOTAL_BYTES = 24L * 1024 * 1024
internal const val DEFAULT_MAX_FRAME_BYTES = 36L * 1024 * 1024
internal const val DEFAULT_ENVELOPE_RESERVE_BYTES = 64L * 1024
internal const val DEFAULT_MAX_TEXT_BYTES = 64L * 1024

data class AttachmentPolicyLimits(
    val maxCount: Int,
    val maxFileBytes: Long,
    val maxTotalBytes: Long,
    val maxFrameBytes: Long,
    val envelopeReserveBytes: Long,
    val maxTextBytes: Long,
)

internal fun positiveLimit(value: Long?, fallback: Long): Long =
    value?.takeIf { it > 0 } ?: fallback

internal fun ingressLimits(limits: WebUiIngressLimits?): AttachmentPolicyLimits = AttachmentPolicyLimits(
    maxCount = positiveLimit(limits?.attachments?.maxCount?.toLong(), DEFAULT_MAX_ATTACHMENT_COUNT.toLong()).toInt(),
    maxFileBytes = positiveLimit(limits?.attachments?.maxFileBytes, DEFAULT_MAX_FILE_BYTES),
    maxTotalBytes = positiveLimit(limits?.attachments?.maxTotalBytes, DEFAULT_MAX_TOTAL_BYTES),
    maxFrameBytes = positiveLimit(limits?.transport?.maxFrameBytes?.toLong(), DEFAULT_MAX_FRAME_BYTES),
    envelopeReserveBytes = positiveLimit(
        limits?.transport?.envelopeReserveBytes?.toLong(),
        DEFAULT_ENVELOPE_RESERVE_BYTES,
    ),
    maxTextBytes = positiveLimit(limits?.message?.maxTextBytes?.toLong(), DEFAULT_MAX_TEXT_BYTES),
)

internal fun attachmentPayloadBudget(limits: AttachmentPolicyLimits): Long =
    (limits.maxFrameBytes - limits.envelopeReserveBytes - limits.maxTextBytes).coerceAtLeast(0)

internal fun decodedBase64Bytes(base64: String): Long {
    val padding = when {
        base64.endsWith("==") -> 2
        base64.endsWith('=') -> 1
        else -> 0
    }
    return (floor(base64.length * 3.0 / 4.0).toLong() - padding).coerceAtLeast(0)
}

internal fun projectedDataUrlBytes(mime: String, decodedBytes: Long): Long =
    "data:$mime;base64,".length + 4L * ((decodedBytes + 2L) / 3L)

private val documentMimeByExtension = mapOf(
    ".pdf" to "application/pdf",
    ".docx" to "application/vnd.openxmlformats-officedocument.wordprocessingml.document",
    ".xlsx" to "application/vnd.openxmlformats-officedocument.spreadsheetml.sheet",
    ".pptx" to "application/vnd.openxmlformats-officedocument.presentationml.presentation",
    ".txt" to "text/plain",
    ".md" to "text/markdown",
    ".csv" to "text/csv",
    ".json" to "application/json",
    ".xml" to "application/xml",
    ".html" to "text/html",
    ".htm" to "text/html",
    ".log" to "text/plain",
    ".yaml" to "application/yaml",
    ".yml" to "application/yaml",
    ".toml" to "application/toml",
    ".ini" to "text/plain",
    ".cfg" to "text/plain",
)

private val documentMimes = documentMimeByExtension.values.toSet() + setOf(
    "application/x-yaml",
    "application/xhtml+xml",
    "text/xml",
    "text/yaml",
)

/**
 * 普通附件上传当前只新增服务端已经确认支持的 MP4。入站媒体识别支持更多扩展名并不代表这些格式
 * 可以上传；两条策略必须分开，避免客户端向用户暴露服务端无法接收的音频能力。
 */
private val videoMimeByExtension = mapOf(
    ".mp4" to "video/mp4",
)

private val videoMimes = videoMimeByExtension.values.toSet()

internal val supportedImageMimes = setOf("image/png", "image/jpeg", "image/webp", "image/gif")

internal fun canonicalDocumentMime(name: String, declared: String?): String? {
    val dot = name.lastIndexOf('.')
    val extension = if (dot < 0) "" else name.substring(dot).lowercase()
    documentMimeByExtension[extension]?.let { return it }
    val normalized = declared?.substringBefore(';')?.trim()?.lowercase()
    return normalized?.takeIf { it in documentMimes }
}

internal fun canonicalVideoMime(name: String, declared: String?): String? {
    val dot = name.lastIndexOf('.')
    val extension = if (dot < 0) "" else name.substring(dot).lowercase()
    videoMimeByExtension[extension]?.let { return it }
    val normalized = declared?.substringBefore(';')?.trim()?.lowercase()
    return normalized?.takeIf { it in videoMimes }
}

internal fun sniffImageMime(bytes: ByteArray): String? = when {
    bytes.size >= 8 &&
        bytes[0].u() == 0x89 && bytes[1].u() == 0x50 && bytes[2].u() == 0x4e && bytes[3].u() == 0x47 &&
        bytes[4].u() == 0x0d && bytes[5].u() == 0x0a && bytes[6].u() == 0x1a && bytes[7].u() == 0x0a -> "image/png"

    bytes.size >= 3 && bytes[0].u() == 0xff && bytes[1].u() == 0xd8 && bytes[2].u() == 0xff -> "image/jpeg"

    bytes.size >= 6 &&
        bytes[0].u() == 0x47 && bytes[1].u() == 0x49 && bytes[2].u() == 0x46 && bytes[3].u() == 0x38 &&
        (bytes[4].u() == 0x37 || bytes[4].u() == 0x39) && bytes[5].u() == 0x61 -> "image/gif"

    bytes.size >= 12 &&
        bytes[0].u() == 0x52 && bytes[1].u() == 0x49 && bytes[2].u() == 0x46 && bytes[3].u() == 0x46 &&
        bytes[8].u() == 0x57 && bytes[9].u() == 0x45 && bytes[10].u() == 0x42 && bytes[11].u() == 0x50 -> "image/webp"

    else -> null
}

internal fun validateEncodedAttachment(
    current: List<ComposerAttachment>,
    candidate: ComposerAttachment,
    limits: AttachmentPolicyLimits,
): String? = validateEncodedAttachmentMetrics(
    currentCount = current.size,
    currentDecodedBytes = current.sumOf(ComposerAttachment::bytes),
    currentWireBytes = current.sumOf { it.outbound.dataUrl.length.toLong() },
    candidateDecodedBytes = candidate.bytes,
    candidateWireBytes = candidate.outbound.dataUrl.length.toLong(),
    limits = limits,
)

internal fun validateEncodedAttachmentMetrics(
    currentCount: Int,
    currentDecodedBytes: Long,
    currentWireBytes: Long,
    candidateDecodedBytes: Long,
    candidateWireBytes: Long,
    limits: AttachmentPolicyLimits,
): String? {
    if (currentCount >= limits.maxCount) return "too_many_attachments"
    if (candidateDecodedBytes <= 0) return "empty_file"
    if (candidateDecodedBytes > limits.maxFileBytes) return "too_large"
    if (currentDecodedBytes + candidateDecodedBytes > limits.maxTotalBytes) return "total_too_large"
    if (currentWireBytes + candidateWireBytes > attachmentPayloadBudget(limits)) return "transport_too_large"
    return null
}

private fun Byte.u(): Int = toInt() and 0xff
