package com.nanobotkt.feature.chat

import android.content.Intent
import android.net.Uri
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Close
import androidx.compose.material.icons.rounded.Description
import androidx.compose.material.icons.rounded.Movie
import androidx.compose.material.icons.rounded.MusicNote
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import coil.compose.AsyncImage
import com.nanobotkt.core.model.UiMediaAttachment
import com.nanobotkt.core.model.UiMessage

private data class TimelineImage(
    val url: String,
    val name: String?,
)

/**
 * 渲染消息携带的图片与普通附件。
 *
 * `images` 与 `media(kind=image)` 可能同时指向同一个服务端资源，必须按解析后的 URL 去重，
 * 否则历史回放时会显示两张完全相同的图片。相对 URL 由上层通过 [resolveUrl] 补齐 Gateway
 * origin；本组件不接触认证 Token，也不会把签名地址写入日志。
 */
@Composable
internal fun MessageMediaBlock(
    message: UiMessage,
    resolveUrl: (String) -> String,
    modifier: Modifier = Modifier,
) {
    val images =
        remember(message.images, message.media) {
            buildList {
                message.images.orEmpty().forEach { image ->
                    image.url?.takeIf(String::isNotBlank)?.let { add(TimelineImage(it, image.name)) }
                }
                message.media.orEmpty().filter { it.kind.equals("image", ignoreCase = true) }
                    .forEach { media ->
                        media.url?.takeIf(String::isNotBlank)?.let { add(TimelineImage(it, media.name)) }
                    }
            }.distinctBy { it.url }
        }
    val attachments =
        remember(message.media) {
            message.media.orEmpty().filterNot { it.kind.equals("image", ignoreCase = true) }
        }
    var previewImage by remember(message.id) { mutableStateOf<TimelineImage?>(null) }

    if (images.isEmpty() && attachments.isEmpty()) return

    Column(
        modifier = modifier.fillMaxWidth(),
        verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        images.chunked(2).forEach { rowImages ->
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                rowImages.forEach { image ->
                    TimelineImageThumbnail(
                        image = image,
                        resolvedUrl = resolveUrl(image.url),
                        onOpen = { previewImage = image },
                        modifier = Modifier.weight(1f),
                    )
                }
                // 双列网格最后一行只有一张图时保留另一列空位，避免单图突然拉伸到不同宽度。
                if (rowImages.size == 1 && images.size > 1) Box(Modifier.weight(1f))
            }
        }

        attachments.forEach { attachment ->
            AttachmentTile(attachment = attachment, resolvedUrl = attachment.url?.let(resolveUrl))
        }
    }

    previewImage?.let { image ->
        FullScreenImagePreview(
            url = resolveUrl(image.url),
            name = image.name,
            onDismiss = { previewImage = null },
        )
    }
}

@Composable
private fun TimelineImageThumbnail(
    image: TimelineImage,
    resolvedUrl: String,
    onOpen: () -> Unit,
    modifier: Modifier = Modifier,
) {
    var failed by remember(resolvedUrl) { mutableStateOf(false) }
    Surface(
        modifier = modifier.aspectRatio(1.35f).clip(RoundedCornerShape(14.dp)).clickable(onClick = onOpen),
        color = MaterialTheme.colorScheme.surfaceVariant,
    ) {
        Box(contentAlignment = Alignment.Center) {
            if (failed) {
                Text(
                    text = stringResource(R.string.media_image_load_failed),
                    modifier = Modifier.padding(12.dp),
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    style = MaterialTheme.typography.labelMedium,
                )
            } else {
                AsyncImage(
                    model = resolvedUrl,
                    contentDescription = image.name ?: stringResource(R.string.media_image),
                    modifier = Modifier.fillMaxSize(),
                    contentScale = ContentScale.Crop,
                    onError = { failed = true },
                )
            }
        }
    }
}

@Composable
private fun AttachmentTile(
    attachment: UiMediaAttachment,
    resolvedUrl: String?,
) {
    val context = LocalContext.current
    val kind = attachment.kind.lowercase()
    val icon =
        when (kind) {
            "video" -> Icons.Rounded.Movie
            "audio" -> Icons.Rounded.MusicNote
            else -> Icons.Rounded.Description
        }
    val fallbackName =
        when (kind) {
            "video" -> stringResource(R.string.media_video)
            "audio" -> stringResource(R.string.media_audio)
            else -> stringResource(R.string.media_file)
        }

    Surface(
        modifier =
            Modifier.fillMaxWidth().let { base ->
                if (resolvedUrl.isNullOrBlank()) base
                else {
                    base.clickable {
                        // 服务端媒体 URL 可能是一次性签名地址。这里只交给系统处理，不持久化 URL，
                        // Activity 不存在时静默保留当前页面，避免点击附件导致聊天进程崩溃。
                        runCatching {
                            context.startActivity(
                                Intent(Intent.ACTION_VIEW, Uri.parse(resolvedUrl)).apply {
                                    addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                                }
                            )
                        }
                    }
                }
            },
        color = MaterialTheme.colorScheme.surfaceVariant,
        shape = MaterialTheme.shapes.medium,
    ) {
        Row(
            modifier = Modifier.padding(horizontal = 12.dp, vertical = 10.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            Icon(
                imageVector = icon,
                contentDescription = null,
                modifier = Modifier.size(22.dp),
                tint = MaterialTheme.colorScheme.primary,
            )
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = attachment.name?.takeIf(String::isNotBlank) ?: fallbackName,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    style = MaterialTheme.typography.bodyMedium,
                )
                Text(
                    text = fallbackName,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    style = MaterialTheme.typography.labelSmall,
                )
            }
        }
    }
}

@Composable
private fun FullScreenImagePreview(
    url: String,
    name: String?,
    onDismiss: () -> Unit,
) {
    Dialog(
        onDismissRequest = onDismiss,
        properties = DialogProperties(usePlatformDefaultWidth = false),
    ) {
        Box(modifier = Modifier.fillMaxSize().background(Color.Black)) {
            AsyncImage(
                model = url,
                contentDescription = name ?: stringResource(R.string.media_image),
                modifier = Modifier.fillMaxSize().padding(16.dp),
                contentScale = ContentScale.Fit,
            )
            IconButton(
                onClick = onDismiss,
                modifier = Modifier.align(Alignment.TopEnd).padding(12.dp),
            ) {
                Icon(
                    Icons.Rounded.Close,
                    contentDescription = stringResource(R.string.close),
                    tint = Color.White,
                )
            }
        }
    }
}
