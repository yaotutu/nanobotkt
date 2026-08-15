package com.nanobotkt.feature.chat

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.net.Uri
import android.provider.OpenableColumns
import android.util.Base64
import com.nanobotkt.core.model.OutboundMedia
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.ByteArrayOutputStream
import java.io.InputStream
import javax.inject.Inject
import javax.inject.Singleton
import kotlin.math.max

interface AttachmentEncoding {
    suspend fun encode(uri: Uri, maxFileBytes: Long): ComposerAttachment
}

@Singleton
class AttachmentEncoder @Inject constructor(
    @param:ApplicationContext private val context: Context,
) : AttachmentEncoding {
    override suspend fun encode(uri: Uri, maxFileBytes: Long): ComposerAttachment = withContext(Dispatchers.IO) {
        val resolver = context.contentResolver
        val metadata = resolver.query(
            uri,
            arrayOf(OpenableColumns.DISPLAY_NAME, OpenableColumns.SIZE),
            null,
            null,
            null,
        )?.use { cursor ->
            if (!cursor.moveToFirst()) {
                null
            } else {
                val nameIndex = cursor.getColumnIndex(OpenableColumns.DISPLAY_NAME)
                val sizeIndex = cursor.getColumnIndex(OpenableColumns.SIZE)
                AttachmentMetadata(
                    name = if (nameIndex >= 0) cursor.getString(nameIndex) else null,
                    size = if (sizeIndex >= 0 && !cursor.isNull(sizeIndex)) cursor.getLong(sizeIndex) else null,
                )
            }
        }
        val name = metadata?.name?.takeIf(String::isNotBlank) ?: "attachment"
        val declaredMime = resolver.getType(uri)?.substringBefore(';')?.trim()?.lowercase()
        val signature = resolver.openInputStream(uri)?.use { it.readSignature() }
            ?: error("io")
        val sniffedImageMime = sniffImageMime(signature)
        val isImage = declaredMime?.startsWith("image/") == true || sniffedImageMime != null

        val encoded =
            if (isImage) {
                encodeImage(
                    uri = uri,
                    declaredMime = declaredMime,
                    sniffedMime = sniffedImageMime,
                    knownBytes = metadata?.size,
                    maxFileBytes = maxFileBytes,
                )
            } else {
                // 视频和文档都保持原始字节。MP4 不能进入 Bitmap 解码/压缩分支，否则不仅会
                // 解码失败，还可能把一个有效视频错误映射为 unsupported_type。
                val mime =
                    canonicalVideoMime(name, declaredMime)
                        ?: canonicalDocumentMime(name, declaredMime)
                        ?: error("unsupported_type")
                metadata?.size?.let { size ->
                    require(size > 0) { "empty_file" }
                    require(size <= maxFileBytes) { "too_large" }
                }
                val bytes = resolver.openInputStream(uri)?.use { it.readBytesLimited(maxFileBytes) }
                    ?: error("io")
                require(bytes.isNotEmpty()) { "empty_file" }
                EncodedBytes(mime, bytes)
            }

        require(encoded.bytes.isNotEmpty()) { "empty_file" }
        require(encoded.bytes.size.toLong() <= maxFileBytes) { "too_large" }
        val dataUrl = "data:${encoded.mime};base64," +
            Base64.encodeToString(encoded.bytes, Base64.NO_WRAP)
        ComposerAttachment(
            uri = uri,
            name = name,
            mimeType = encoded.mime,
            bytes = encoded.bytes.size.toLong(),
            outbound = OutboundMedia(dataUrl, name),
        )
    }

    private fun encodeImage(
        uri: Uri,
        declaredMime: String?,
        sniffedMime: String?,
        knownBytes: Long?,
        maxFileBytes: Long,
    ): EncodedBytes {
        val resolver = context.contentResolver
        val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        resolver.openInputStream(uri)?.use { BitmapFactory.decodeStream(it, null, bounds) }
        val longest = max(bounds.outWidth, bounds.outHeight)
        if (longest <= 0) error("decode_failed")
        val shouldNormalize = declaredMime !in supportedImageMimes ||
            sniffedMime == null ||
            (knownBytes != null && knownBytes > maxFileBytes) ||
            longest > MAX_IMAGE_EDGE

        if (!shouldNormalize) {
            val bytes = resolver.openInputStream(uri)?.use { it.readBytesLimited(maxFileBytes) }
                ?: error("io")
            val actualMime = sniffImageMime(bytes.take(SIGNATURE_BYTES).toByteArray())
                ?: error("magic_mismatch")
            return EncodedBytes(actualMime, bytes)
        }

        var sample = 1
        while (longest / (sample * 2) >= MAX_IMAGE_EDGE) sample *= 2
        val decoded = resolver.openInputStream(uri)?.use {
            BitmapFactory.decodeStream(it, null, BitmapFactory.Options().apply { inSampleSize = sample })
        } ?: error("decode_failed")
        val scaled = if (max(decoded.width, decoded.height) > MAX_IMAGE_EDGE) {
            val scale = MAX_IMAGE_EDGE.toFloat() / max(decoded.width, decoded.height)
            Bitmap.createScaledBitmap(
                decoded,
                (decoded.width * scale).toInt().coerceAtLeast(1),
                (decoded.height * scale).toInt().coerceAtLeast(1),
                true,
            ).also { if (it !== decoded) decoded.recycle() }
        } else {
            decoded
        }

        try {
            var quality = 88
            var bytes: ByteArray
            do {
                bytes = ByteArrayOutputStream().use { output ->
                    check(scaled.compress(Bitmap.CompressFormat.JPEG, quality, output)) { "decode_failed" }
                    output.toByteArray()
                }
                quality -= 8
            } while (bytes.size.toLong() > maxFileBytes && quality >= 48)
            require(bytes.size.toLong() <= maxFileBytes) { "too_large" }
            return EncodedBytes("image/jpeg", bytes)
        } finally {
            scaled.recycle()
        }
    }

    private fun InputStream.readSignature(): ByteArray {
        val bytes = ByteArray(SIGNATURE_BYTES)
        val count = read(bytes)
        return if (count <= 0) byteArrayOf() else bytes.copyOf(count)
    }

    private fun InputStream.readBytesLimited(maxBytes: Long): ByteArray {
        val output = ByteArrayOutputStream()
        val buffer = ByteArray(DEFAULT_BUFFER_SIZE)
        var total = 0L
        while (true) {
            val read = read(buffer)
            if (read < 0) break
            total += read
            require(total <= maxBytes) { "too_large" }
            output.write(buffer, 0, read)
        }
        return output.toByteArray()
    }

    private data class AttachmentMetadata(val name: String?, val size: Long?)
    private data class EncodedBytes(val mime: String, val bytes: ByteArray)

    private companion object {
        const val MAX_IMAGE_EDGE = 2048
        const val SIGNATURE_BYTES = 12
    }
}

data class ComposerAttachment(
    val uri: Uri,
    val name: String,
    val mimeType: String,
    val bytes: Long,
    val outbound: OutboundMedia,
)
