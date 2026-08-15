package com.nanobotkt.feature.chat

import com.nanobotkt.core.model.UiMediaAttachment
import com.nanobotkt.core.model.UiMessage

/**
 * 把 Composer 的本地 Queue 追加为普通用户消息的展示变体。
 *
 * Queue 不写回 Repository，也不伪造服务端 turnId；它只保证用户按下发送后能够立即在时间轴看到
 * 自己的输入。真正发送时 ViewModel 会先从 Queue 移除，再由 Repository 的 optimistic message 接管，
 * 因而服务端历史仍是唯一事实来源。
 */
internal fun appendQueuedPromptsToTimeline(
    timelineItems: List<ChatTimelineItem>,
    queuedPrompts: List<QueuedPrompt>,
    nowMs: Long = System.currentTimeMillis(),
): List<ChatTimelineItem> =
    timelineItems +
        queuedPrompts.mapIndexed { index, prompt ->
            val content = formatQuotedUserMessage(prompt.text, prompt.quotedContext)
            val media =
                prompt.attachments.map { attachment ->
                    UiMediaAttachment(
                        kind = attachment.mimeType.toTimelineMediaKind(),
                        url = attachment.outbound.dataUrl,
                        name = attachment.name,
                    )
                }
            ChatTimelineItem.UserMessage(
                message =
                    UiMessage(
                        id = prompt.id,
                        role = "user",
                        content = content,
                        createdAt = nowMs + index,
                        turnPhase = "queued",
                        media = media.ifEmpty { null },
                    ),
                originalIndex = -1,
                deliveryState = UserMessageDeliveryState.QUEUED,
            )
        }

/** MIME 只用于选择时间轴播放器；无法识别的格式统一降级为普通文件。 */
private fun String.toTimelineMediaKind(): String =
    when {
        startsWith("image/", ignoreCase = true) -> "image"
        startsWith("audio/", ignoreCase = true) -> "audio"
        startsWith("video/", ignoreCase = true) -> "video"
        else -> "file"
    }
