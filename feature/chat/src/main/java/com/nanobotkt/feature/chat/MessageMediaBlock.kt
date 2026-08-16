package com.nanobotkt.feature.chat

import android.content.Intent
import android.net.Uri
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Close
import androidx.compose.material.icons.rounded.Description
import androidx.compose.material.icons.rounded.Movie
import androidx.compose.material.icons.rounded.MusicNote
import androidx.compose.material.icons.rounded.Pause
import androidx.compose.material.icons.rounded.PlayArrow
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Slider
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.Stable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import androidx.media3.common.C
import androidx.media3.common.MediaItem
import androidx.media3.common.PlaybackException
import androidx.media3.common.Player
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.ui.PlayerView
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import coil.compose.AsyncImage
import com.nanobotkt.core.model.UiMediaAttachment
import com.nanobotkt.core.model.UiMessage
import kotlinx.coroutines.delay

private const val MEDIA_PROGRESS_REFRESH_MS = 250L

private data class TimelineImage(
    val url: String,
    val name: String?,
)

/**
 * 时间轴媒体的轻量互斥协调器。
 *
 * 每个可见附件仍然持有自己的播放器，这个对象只记录“当前允许播放的附件 ID”。新附件开始播放时，
 * 其他附件观察到 ID 变化后立即暂停。它由单个 [MessageList] 持有，不进入 ViewModel，也不会把播放器
 * 或临时 URL 泄漏到全局状态。
 */
@Stable
internal class TimelinePlaybackCoordinator {
    var activeAttachmentId by mutableStateOf<String?>(null)
        private set

    fun activate(attachmentId: String) {
        activeAttachmentId = attachmentId
    }

    fun clearIfActive(attachmentId: String) {
        if (activeAttachmentId == attachmentId) activeAttachmentId = null
    }
}

/** 渲染消息携带的图片、音频、视频与普通附件。 */
@Composable
internal fun MessageMediaBlock(
    message: UiMessage,
    resolveUrl: (String) -> String,
    playbackCoordinator: TimelinePlaybackCoordinator,
    modifier: Modifier = Modifier,
) {
    val images =
        remember(message.images, message.media) {
            buildList {
                message.images.orEmpty().forEach { image ->
                    image.url?.takeIf(String::isNotBlank)?.let { url ->
                        if (inferTimelineMediaKind(url = url, name = image.name) == "image") {
                            add(TimelineImage(url, image.name))
                        }
                    }
                }
                message.media.orEmpty().forEach { media ->
                    media.url?.takeIf(String::isNotBlank)?.let { url ->
                        if (
                            inferTimelineMediaKind(
                                declaredKind = media.kind,
                                url = url,
                                name = media.name,
                            ) == "image"
                        ) {
                            add(TimelineImage(url, media.name))
                        }
                    }
                }
            }.distinctBy { it.url }
        }
    val attachments =
        remember(message.images, message.media) {
            buildList {
                // 旧服务端把所有 media_urls 都序列化成 UiImage。根据 name/url 重新判断后，WAV
                // 等非图片媒体必须回到附件播放器，不能继续交给 Coil 形成破图。
                message.images.orEmpty().forEach { image ->
                    image.url?.takeIf(String::isNotBlank)?.let { url ->
                        val kind = inferTimelineMediaKind(url = url, name = image.name)
                        if (kind != "image") {
                            add(UiMediaAttachment(kind = kind, url = url, name = image.name))
                        }
                    }
                }
                message.media.orEmpty().forEach { media ->
                    val kind =
                        inferTimelineMediaKind(
                            declaredKind = media.kind,
                            url = media.url,
                            name = media.name,
                        )
                    if (kind != "image") add(media.copy(kind = kind))
                }
            }.distinctBy { Triple(it.kind, it.url, it.name) }
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

        attachments.forEachIndexed { index, attachment ->
            val resolvedUrl = attachment.url?.takeIf(String::isNotBlank)?.let(resolveUrl)
            val attachmentId = "${message.id}:$index:${attachment.kind}:${attachment.url.orEmpty()}"
            when {
                attachment.kind.startsWith("audio", ignoreCase = true) ->
                    AudioAttachmentPlayer(
                        attachment = attachment,
                        attachmentId = attachmentId,
                        resolvedUrl = resolvedUrl,
                        playbackCoordinator = playbackCoordinator,
                    )
                attachment.kind.startsWith("video", ignoreCase = true) ->
                    VideoAttachmentCard(
                        attachment = attachment,
                        attachmentId = attachmentId,
                        resolvedUrl = resolvedUrl,
                        playbackCoordinator = playbackCoordinator,
                    )
                else -> FileAttachmentTile(attachment = attachment, resolvedUrl = resolvedUrl)
            }
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
        modifier = modifier.aspectRatio(1f).clip(RoundedCornerShape(12.dp)).clickable(onClick = onOpen),
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

/**
 * 音频采用紧凑的原生时间轴播放器，不伪造波形，也不扩展倍速、下载或分享能力。
 *
 * 播放器只在对应 LazyColumn 单元进入组合时创建，离开组合立即释放。进度轮询只用于刷新 UI；真实时长
 * 尚未从媒体容器解析出来时只显示当前进度，不使用占位时长误导用户。
 */
@Composable
private fun AudioAttachmentPlayer(
    attachment: UiMediaAttachment,
    attachmentId: String,
    resolvedUrl: String?,
    playbackCoordinator: TimelinePlaybackCoordinator,
) {
    if (resolvedUrl.isNullOrBlank()) {
        UnavailableAttachmentTile(attachment = attachment, icon = Icons.Rounded.MusicNote)
        return
    }

    val context = LocalContext.current
    val player =
        remember(resolvedUrl) {
            ExoPlayer.Builder(context).build().apply {
                setMediaItem(MediaItem.fromUri(resolvedUrl))
                prepare()
            }
        }
    var positionMs by remember(player) { mutableLongStateOf(0L) }
    var durationMs by remember(player) { mutableLongStateOf(0L) }
    var isPlaying by remember(player) { mutableStateOf(false) }
    var playbackFailed by remember(player) { mutableStateOf(false) }
    val isActive = playbackCoordinator.activeAttachmentId == attachmentId

    PausePlayerOnLifecycleStop(player) {
        playbackCoordinator.clearIfActive(attachmentId)
    }

    DisposableEffect(player, attachmentId) {
        val listener =
            object : Player.Listener {
                override fun onIsPlayingChanged(playing: Boolean) {
                    isPlaying = playing
                }

                override fun onPlaybackStateChanged(playbackState: Int) {
                    if (playbackState == Player.STATE_ENDED) {
                        positionMs = player.duration.takeIf(::isKnownDuration) ?: 0L
                        playbackCoordinator.clearIfActive(attachmentId)
                    }
                }

                override fun onPlayerError(error: PlaybackException) {
                    // 服务端可能用 application/octet-stream 返回音频。Media3 无法解析时稳定降级
                    // 为普通附件入口，而不是永久停留在一个不可操作的播放按钮上。
                    playbackFailed = true
                    playbackCoordinator.clearIfActive(attachmentId)
                }
            }
        player.addListener(listener)
        onDispose {
            player.removeListener(listener)
            playbackCoordinator.clearIfActive(attachmentId)
            player.release()
        }
    }

    LaunchedEffect(isActive, player, playbackFailed) {
        if (playbackFailed) return@LaunchedEffect
        // 互斥切换只暂停旧附件，保留其进度；用户再次播放时可以从原位置继续。
        if (!isActive && player.isPlaying) player.pause()
        while (true) {
            positionMs = player.currentPosition.coerceAtLeast(0L)
            durationMs = player.duration.takeIf(::isKnownDuration) ?: 0L
            isPlaying = player.isPlaying
            delay(MEDIA_PROGRESS_REFRESH_MS)
        }
    }

    if (playbackFailed) {
        FileAttachmentTile(attachment = attachment.copy(kind = "file"), resolvedUrl = resolvedUrl)
    } else {
        CompactAudioPlayer(
            name =
                attachment.name?.takeIf(String::isNotBlank)
                    ?: stringResource(R.string.media_audio),
            positionMs = positionMs,
            durationMs = durationMs,
            isPlaying = isPlaying,
            onTogglePlayback = {
                if (player.isPlaying) {
                    player.pause()
                    playbackCoordinator.clearIfActive(attachmentId)
                } else {
                    playbackCoordinator.activate(attachmentId)
                    // Media3 在 STATE_ENDED 时不会总是自动回到开头；显式归零保证再次
                    // 点击播放是“重新播放”，而不是停留在末尾没有反馈。
                    if (player.playbackState == Player.STATE_ENDED) player.seekTo(0L)
                    player.play()
                }
            },
            onSeek = { value -> player.seekTo(value.toLong()) },
        )
    }
}

/**
 * 时间轴中的音频采用紧凑附件卡片，而不是占满整行的媒体控制面板。
 *
 * 280dp 上限保证它在 Assistant 文档流中保持“附件”而非“主内容”的视觉权重；父容器更窄时
 * 会自动收缩。播放键仍保留 48dp 触控区域，进度条只压缩视觉轨道和拇指，不牺牲核心操作。
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun CompactAudioPlayer(
    name: String,
    positionMs: Long,
    durationMs: Long,
    isPlaying: Boolean,
    onTogglePlayback: () -> Unit,
    onSeek: (Float) -> Unit,
) {
    val boundedPosition = positionMs.coerceAtMost(durationMs.takeIf { it > 0L } ?: positionMs)
    val progressText =
        if (durationMs > 0L) {
            "${formatMediaTime(boundedPosition)} / ${formatMediaTime(durationMs)}"
        } else {
            formatMediaTime(boundedPosition)
        }
    val activeColor = MaterialTheme.colorScheme.primary
    val inactiveColor = MaterialTheme.colorScheme.outlineVariant

    Surface(
        // widthIn 必须位于 fillMaxWidth 之前：先把可用宽度封顶为 280dp，再让内部 Row
        // 填满这段紧凑宽度；如果顺序相反，组件会重新膨胀到整条时间轴。
        modifier = Modifier.widthIn(min = 208.dp, max = 280.dp).fillMaxWidth(),
        color = MaterialTheme.colorScheme.surfaceContainerLow,
        shape = MaterialTheme.shapes.large,
        border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.72f)),
        tonalElevation = 0.dp,
        shadowElevation = 0.dp,
    ) {
        Row(
            modifier = Modifier.padding(start = 6.dp, top = 8.dp, end = 12.dp, bottom = 8.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(6.dp),
        ) {
            IconButton(onClick = onTogglePlayback) {
                Icon(
                    imageVector = if (isPlaying) Icons.Rounded.Pause else Icons.Rounded.PlayArrow,
                    contentDescription =
                        stringResource(if (isPlaying) R.string.media_pause else R.string.media_play),
                    tint = activeColor,
                )
            }
            Column(modifier = Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(1.dp)) {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Text(
                        text = name,
                        modifier = Modifier.weight(1f),
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                        style = MaterialTheme.typography.bodyMedium,
                        fontWeight = FontWeight.Medium,
                    )
                    Text(
                        text = progressText,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        maxLines = 1,
                        style = MaterialTheme.typography.labelSmall,
                    )
                }
                Slider(
                    value = boundedPosition.toFloat(),
                    onValueChange = onSeek,
                    valueRange = 0f..durationMs.coerceAtLeast(1L).toFloat(),
                    enabled = durationMs > 0L,
                    modifier = Modifier.fillMaxWidth().height(28.dp),
                    // Material 3 1.4 的默认 Slider 使用强调型高拇指，在聊天附件里会形成截图中
                    // 醒目的紫色竖条。这里保留标准 Slider 的手势和语义，只替换视觉轨道与拇指。
                    thumb = {
                        Box(
                            modifier =
                                Modifier.size(10.dp)
                                    .background(
                                        if (durationMs > 0L) activeColor else inactiveColor,
                                        CircleShape,
                                    )
                        )
                    },
                    track = { sliderState ->
                        val range = sliderState.valueRange
                        val span = (range.endInclusive - range.start).takeIf { it > 0f } ?: 1f
                        val fraction = ((sliderState.value - range.start) / span).coerceIn(0f, 1f)
                        Box(
                            modifier =
                                Modifier.fillMaxWidth()
                                    .height(3.dp)
                                    .clip(CircleShape)
                                    .background(inactiveColor)
                        ) {
                            Box(
                                modifier =
                                    Modifier.fillMaxWidth(fraction)
                                        .fillMaxHeight()
                                        .background(activeColor)
                            )
                        }
                    },
                )
            }
        }
    }
}

/** 视频在时间轴中只展示稳定占位，不自动加载画面或开始播放；点击后进入沉浸式播放器。 */
@Composable
private fun VideoAttachmentCard(
    attachment: UiMediaAttachment,
    attachmentId: String,
    resolvedUrl: String?,
    playbackCoordinator: TimelinePlaybackCoordinator,
) {
    var fullScreen by remember(attachmentId) { mutableStateOf(false) }
    val enabled = !resolvedUrl.isNullOrBlank()
    Surface(
        modifier =
            Modifier.fillMaxWidth().aspectRatio(16f / 9f).let { base ->
                if (enabled) base.clickable {
                    playbackCoordinator.activate(attachmentId)
                    fullScreen = true
                } else base
            },
        color = Color.Black,
        shape = MaterialTheme.shapes.large,
    ) {
        Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
            Icon(
                imageVector = Icons.Rounded.Movie,
                contentDescription = null,
                modifier = Modifier.size(42.dp),
                tint = Color.White.copy(alpha = 0.35f),
            )
            Surface(
                color = Color.White.copy(alpha = if (enabled) 0.92f else 0.45f),
                shape = RoundedCornerShape(999.dp),
            ) {
                Icon(
                    imageVector = Icons.Rounded.PlayArrow,
                    contentDescription = stringResource(R.string.media_play_video),
                    modifier = Modifier.padding(10.dp).size(28.dp),
                    tint = Color.Black,
                )
            }
            Text(
                text = attachment.name?.takeIf(String::isNotBlank)
                    ?: stringResource(R.string.media_video),
                modifier = Modifier.align(Alignment.BottomStart).padding(12.dp),
                color = Color.White,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                style = MaterialTheme.typography.labelLarge,
            )
        }
    }

    if (fullScreen && resolvedUrl != null) {
        FullScreenVideoPlayer(
            attachmentId = attachmentId,
            url = resolvedUrl,
            playbackCoordinator = playbackCoordinator,
            onDismiss = {
                fullScreen = false
                playbackCoordinator.clearIfActive(attachmentId)
            },
        )
    }
}

/** 全屏视频播放器使用 Media3 原生控制层，并在 Dialog 关闭或重组移除时立即释放资源。 */
@Composable
private fun FullScreenVideoPlayer(
    attachmentId: String,
    url: String,
    playbackCoordinator: TimelinePlaybackCoordinator,
    onDismiss: () -> Unit,
) {
    val context = LocalContext.current
    val player =
        remember(url) {
            ExoPlayer.Builder(context).build().apply {
                setMediaItem(MediaItem.fromUri(url))
                prepare()
                playWhenReady = true
            }
        }

    PausePlayerOnLifecycleStop(player) {
        playbackCoordinator.clearIfActive(attachmentId)
    }

    DisposableEffect(player, attachmentId) {
        onDispose {
            playbackCoordinator.clearIfActive(attachmentId)
            player.release()
        }
    }

    Dialog(
        onDismissRequest = onDismiss,
        properties = DialogProperties(usePlatformDefaultWidth = false),
    ) {
        Box(modifier = Modifier.fillMaxSize().background(Color.Black)) {
            AndroidView(
                factory = { playerContext ->
                    PlayerView(playerContext).apply {
                        useController = true
                        this.player = player
                    }
                },
                update = { view -> view.player = player },
                modifier = Modifier.fillMaxSize(),
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

/**
 * 页面进入 STOPPED 时暂停播放器，但不在 ON_START/ON_RESUME 自动继续。
 *
 * 锁屏不会销毁当前 Composition，单靠 onDispose 会让音频/视频在后台继续播放；恢复时保持暂停
 * 则要求用户再次明确点击，避免突然出声。播放器资源仍由各自原有 DisposableEffect 释放。
 */
@Composable
private fun PausePlayerOnLifecycleStop(
    player: Player,
    onStopped: () -> Unit,
) {
    val lifecycleOwner = LocalLifecycleOwner.current
    val currentOnStopped by rememberUpdatedState(onStopped)
    DisposableEffect(lifecycleOwner, player) {
        val observer = LifecycleEventObserver { _, event ->
            if (event == Lifecycle.Event.ON_STOP) {
                player.pause()
                currentOnStopped()
            }
        }
        lifecycleOwner.lifecycle.addObserver(observer)
        onDispose { lifecycleOwner.lifecycle.removeObserver(observer) }
    }
}

/** 普通文件仍沿用系统打开能力；Android 找不到可处理 Activity 时保持当前页面不崩溃。 */
@Composable
private fun FileAttachmentTile(
    attachment: UiMediaAttachment,
    resolvedUrl: String?,
) {
    val context = LocalContext.current
    Surface(
        modifier =
            Modifier.fillMaxWidth().let { base ->
                if (resolvedUrl.isNullOrBlank()) base
                else {
                    base.clickable {
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
                imageVector = Icons.Rounded.Description,
                contentDescription = null,
                modifier = Modifier.size(22.dp),
                tint = MaterialTheme.colorScheme.primary,
            )
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = attachment.name?.takeIf(String::isNotBlank)
                        ?: stringResource(R.string.media_file),
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    style = MaterialTheme.typography.bodyMedium,
                )
                Text(
                    text = stringResource(R.string.media_file),
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    style = MaterialTheme.typography.labelSmall,
                )
            }
        }
    }
}

/** URL 缺失时仍显示附件身份，但不提供无效的点击行为。 */
@Composable
private fun UnavailableAttachmentTile(
    attachment: UiMediaAttachment,
    icon: androidx.compose.ui.graphics.vector.ImageVector,
) {
    Surface(
        modifier = Modifier.fillMaxWidth(),
        color = MaterialTheme.colorScheme.surfaceVariant,
        shape = MaterialTheme.shapes.medium,
    ) {
        Row(
            modifier = Modifier.padding(horizontal = 12.dp, vertical = 10.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            Icon(icon, contentDescription = null, tint = MaterialTheme.colorScheme.onSurfaceVariant)
            Text(
                text = attachment.name?.takeIf(String::isNotBlank)
                    ?: stringResource(R.string.media_audio),
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                style = MaterialTheme.typography.bodyMedium,
            )
        }
    }
}

private fun isKnownDuration(durationMs: Long): Boolean = durationMs > 0L && durationMs != C.TIME_UNSET

/** 媒体时间只显示到秒，避免 250ms 的 UI 刷新造成文本持续抖动。 */
internal fun formatMediaTime(durationMs: Long): String {
    val totalSeconds = durationMs.coerceAtLeast(0L) / 1_000L
    val hours = totalSeconds / 3_600L
    val minutes = (totalSeconds % 3_600L) / 60L
    val seconds = totalSeconds % 60L
    return if (hours > 0L) {
        "%d:%02d:%02d".format(hours, minutes, seconds)
    } else {
        "%d:%02d".format(minutes, seconds)
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
