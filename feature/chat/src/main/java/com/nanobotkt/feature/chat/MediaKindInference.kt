package com.nanobotkt.feature.chat

/**
 * 在服务端没有稳定下发 MIME/kind 时，根据现有字段推断时间轴媒体类型。
 *
 * 这里只影响客户端如何展示已经收到的媒体，不会扩大附件上传白名单。尤其是 `.m4a` 等扩展名
 * 可以被识别为入站音频，但仍不会因此允许用户把它作为普通附件发送。
 */
internal fun inferTimelineMediaKind(
    declaredKind: String? = null,
    url: String? = null,
    name: String? = null,
): String {
    val normalizedKind = declaredKind?.substringBefore(';')?.trim()?.lowercase()
    when {
        normalizedKind == "image" || normalizedKind?.startsWith("image/") == true -> return "image"
        normalizedKind == "audio" || normalizedKind?.startsWith("audio/") == true -> return "audio"
        normalizedKind == "video" || normalizedKind?.startsWith("video/") == true -> return "video"
    }

    dataUrlMime(url)?.let { mime ->
        when {
            mime.startsWith("image/") -> return "image"
            mime.startsWith("audio/") -> return "audio"
            mime.startsWith("video/") -> return "video"
        }
    }

    // name 可能只是服务端生成的通用 .bin，而 URL 仍保留真实扩展名；逐个候选查找已知
    // 类型，不能因为第一个“非空但未知”的扩展名而提前停止兜底。
    return sequenceOf(name, url?.substringBefore('?')?.substringBefore('#'))
        .filterNotNull()
        .map(::fileExtension)
        .mapNotNull(::mediaKindForExtension)
        .firstOrNull()
        ?: "file"
}

/** Data URL 只读取头部 MIME，不解码正文，避免为类型判断复制大附件。 */
private fun dataUrlMime(url: String?): String? {
    if (url == null || !url.startsWith("data:", ignoreCase = true)) return null
    return url.substringAfter(':').substringBefore(';').substringBefore(',').trim().lowercase()
}

private fun fileExtension(value: String): String {
    val fileName = value.substringAfterLast('/')
    val dot = fileName.lastIndexOf('.')
    return if (dot < 0) "" else fileName.substring(dot).lowercase()
}

private fun mediaKindForExtension(extension: String): String? =
    when (extension) {
        in IMAGE_EXTENSIONS -> "image"
        in AUDIO_EXTENSIONS -> "audio"
        in VIDEO_EXTENSIONS -> "video"
        else -> null
    }

private val IMAGE_EXTENSIONS = setOf(".png", ".jpg", ".jpeg", ".webp", ".gif", ".svg")
private val AUDIO_EXTENSIONS = setOf(".wav", ".mp3", ".m4a", ".aac", ".ogg", ".flac")
private val VIDEO_EXTENSIONS = setOf(".mp4", ".webm", ".mov")
