package com.nanobotkt.feature.chat

import com.nanobotkt.core.model.UiMessage

/** Mapper 内部同时保留原始下标，确保 Fork 仍按服务端消息数组的 user index 计算。 */
private data class IndexedTimelineMessage(
    val message: UiMessage,
    val originalIndex: Int,
)

private enum class ActivityBucket {
    FILE,
    OTHER,
}

/**
 * 把服务端消息历史归一化为产品时间轴。
 *
 * 该函数保持纯函数特征：不读取 Compose 状态、不修改传入列表，也不启动任何副作用，便于用单元测试
 * 锁定复杂的 turn/segment 排序规则。实现语义与官方 WebUI 的 activity timeline 保持一致：
 *
 * 1. 用户消息先结束上一轮，并作为独立单元出现。
 * 2. trace、纯 reasoning assistant 与旧历史 role=tool 归入 Agent Activity。
 * 3. 同时含 reasoning 和正文的 assistant 被拆成“Activity + 正文”。
 * 4. 已完成轮次末尾的 Activity 移到最终 assistant 正文之前，避免活动卡悬在答案之后。
 * 5. 活跃轮次保留尾部活动，以准确呈现仍在执行的实时顺序。
 */
internal fun buildChatTimelineItems(
    messages: List<UiMessage>,
    activeTurnId: String? = null,
    failedMessageIds: Set<String> = emptySet(),
): List<ChatTimelineItem> {
    val units = mutableListOf<ChatTimelineItem>()
    val turnMessages = mutableListOf<IndexedTimelineMessage>()
    var currentTurnId: String? = null
    var currentTurnStartedAtMs: Long? = null

    fun flushTurn() {
        if (turnMessages.isEmpty()) {
            currentTurnId = null
            currentTurnStartedAtMs = null
            return
        }

        val preserveTrailingActivity =
            activeTurnId != null &&
                (currentTurnId == activeTurnId || turnMessages.any { it.message.turnId == activeTurnId })
        units +=
            buildTurnItems(
                indexedMessages = turnMessages.toList(),
                turnId = currentTurnId,
                startedAtMs = currentTurnStartedAtMs,
                activeTurnId = activeTurnId,
                preserveTrailingActivity = preserveTrailingActivity,
            )
        turnMessages.clear()
        currentTurnId = null
        currentTurnStartedAtMs = null
    }

    messages.forEachIndexed { index, message ->
        when (message.role) {
            "user" -> {
                flushTurn()
                units +=
                    ChatTimelineItem.UserMessage(
                        message = message,
                        originalIndex = index,
                        deliveryState =
                            if (message.id in failedMessageIds) {
                                UserMessageDeliveryState.FAILED
                            } else {
                                UserMessageDeliveryState.SENT
                            },
                    )
                currentTurnId = message.turnId
                currentTurnStartedAtMs = message.createdAt
            }

            "assistant", "tool" -> {
                // 官方旧历史会把工具结果保存为 role=tool，而实时新协议通常把结构化工具事件放进
                // assistant trace。两种形态必须进入同一个 Turn 管线，否则旧工具会退化成全宽 Marker。
                // turnId 变化说明服务端历史中进入了另一轮。即使中间缺少 user 记录，也必须先切断
                // 分组，防止两个独立 Turn 的工具活动被错误合并到同一张 Activity 卡片。
                if (message.turnId != null && currentTurnId != null && message.turnId != currentTurnId) {
                    flushTurn()
                }
                if (message.turnId != null) currentTurnId = message.turnId
                turnMessages += IndexedTimelineMessage(message, index)
            }

            else -> {
                // 未知角色不能伪装成 assistant。先提交当前轮，再以时间轴标记稳定降级。
                flushTurn()
                units += ChatTimelineItem.Marker(message, index)
            }
        }
    }

    flushTurn()
    return units
}

private fun buildTurnItems(
    indexedMessages: List<IndexedTimelineMessage>,
    turnId: String?,
    startedAtMs: Long?,
    activeTurnId: String?,
    preserveTrailingActivity: Boolean,
): List<ChatTimelineItem> {
    val ordered = orderMessagesByTurnSeq(indexedMessages)
    val visibleMessages = ordered.mapNotNull(::visibleMessageForLatency)
    val turnUnits = mutableListOf<ChatTimelineItem>()
    val pendingActivity = mutableListOf<IndexedTimelineMessage>()
    var visibleIndex = 0

    fun flushActivity() {
        if (pendingActivity.isEmpty()) return
        turnUnits +=
            buildActivityRuns(
                activityMessages = pendingActivity.toList(),
                remainingVisibleMessages = visibleMessages.drop(visibleIndex),
                turnId = turnId,
                startedAtMs = startedAtMs,
                activeTurnId = activeTurnId,
            )
        pendingActivity.clear()
    }

    ordered.forEach { indexed ->
        val message = indexed.message
        when {
            isAgentActivityMember(message) -> pendingActivity += indexed

            assistantHasInlineReasoning(message) -> {
                // 一条 assistant 可能同时携带 reasoning 和最终正文。Reasoning 使用派生 ID 拆入活动组，
                // 正文副本则清除 reasoning 字段，避免同一内容在两个组件重复渲染。
                pendingActivity +=
                    IndexedTimelineMessage(
                        message = reasoningOnlyMessageFromAnswer(message),
                        originalIndex = indexed.originalIndex,
                    )
                flushActivity()
                turnUnits +=
                    ChatTimelineItem.AssistantMessage(
                        message = stripInlineReasoning(message),
                        originalIndex = indexed.originalIndex,
                    )
                visibleIndex += 1
            }

            else -> {
                flushActivity()
                turnUnits += ChatTimelineItem.AssistantMessage(message, indexed.originalIndex)
                visibleIndex += 1
            }
        }
    }

    flushActivity()
    return normalizeCompletedTurnItems(turnUnits, preserveTrailingActivity)
}

/** 只有所有消息都带 turnSeq 时才排序；部分缺失时保持服务端原始顺序是最安全的降级。 */
private fun orderMessagesByTurnSeq(
    messages: List<IndexedTimelineMessage>
): List<IndexedTimelineMessage> =
    if (messages.size < 2 || messages.any { it.message.turnSeq == null }) {
        messages
    } else {
        messages.sortedWith(
            compareBy<IndexedTimelineMessage> { it.message.turnSeq }
                .thenBy { it.originalIndex }
        )
    }

private fun isAgentActivityMember(message: UiMessage): Boolean =
    isLegacyToolActivityMessage(message) || message.kind == "trace" || isReasoningOnlyAssistant(message)

/**
 * 兼容持久化历史中的经典 Chat Completion 结构：工具结果以独立 role=tool 消息存在。
 * 这里只识别明确的 tool 角色，未知 system/custom 角色仍走 Marker 降级，避免扩大产品语义。
 */
private fun isLegacyToolActivityMessage(message: UiMessage): Boolean =
    message.role.equals("tool", ignoreCase = true)

private fun isReasoningOnlyAssistant(message: UiMessage): Boolean =
    message.role == "assistant" &&
        message.kind != "trace" &&
        message.content.isBlank() &&
        (!message.reasoning.isNullOrBlank() ||
            message.reasoningStreaming == true ||
            message.isStreaming == true)

private fun assistantHasInlineReasoning(message: UiMessage): Boolean =
    message.role == "assistant" &&
        message.kind != "trace" &&
        message.content.isNotBlank() &&
        (!message.reasoning.isNullOrBlank() || message.reasoningStreaming == true)

private fun reasoningOnlyMessageFromAnswer(message: UiMessage): UiMessage =
    message.copy(
        id = "${message.id}-reasoning",
        content = "",
        kind = null,
        isStreaming = message.reasoningStreaming,
        traces = null,
        toolEvents = null,
        fileEdits = null,
        source = null,
        cliApps = null,
        mcpPresets = null,
        media = null,
        images = null,
    )

private fun stripInlineReasoning(message: UiMessage): UiMessage =
    message.copy(reasoning = null, reasoningStreaming = null)

private fun visibleMessageForLatency(indexed: IndexedTimelineMessage): UiMessage? =
    indexed.message.takeUnless(::isAgentActivityMember)?.let { message ->
        if (assistantHasInlineReasoning(message)) stripInlineReasoning(message) else message
    }

private fun buildActivityRuns(
    activityMessages: List<IndexedTimelineMessage>,
    remainingVisibleMessages: List<UiMessage>,
    turnId: String?,
    startedAtMs: Long?,
    activeTurnId: String?,
): List<ChatTimelineItem.AgentActivity> {
    val units = mutableListOf<ChatTimelineItem.AgentActivity>()
    val run = mutableListOf<IndexedTimelineMessage>()
    var runBucket: ActivityBucket? = null
    var runSegmentId: String? = null

    fun flushRun() {
        if (run.isEmpty()) return
        val runMessages = run.map { it.message }
        val first = run.first().message
        val bucket = runBucket ?: ActivityBucket.OTHER
        val inferredTurnId = runMessages.firstNotNullOfOrNull { it.turnId } ?: turnId
        val isStreaming =
            runMessages.any { it.isStreaming == true || it.reasoningStreaming == true } ||
                (activeTurnId != null && inferredTurnId == activeTurnId)
        units +=
            ChatTimelineItem.AgentActivity(
                // 首条消息 ID 在后续流式增量中保持稳定；不能把 lastId 或活动数量写进 key，
                // 否则每次 trace 增长都会重置用户手动展开状态。
                key = "activity:${first.id}:${bucket.name.lowercase()}",
                messages = runMessages,
                turnId = inferredTurnId,
                turnLatencyMs = activityTurnLatencyMs(runMessages, remainingVisibleMessages),
                startedAtMs = startedAtMs,
                isStreaming = isStreaming,
            )
        run.clear()
        runBucket = null
        runSegmentId = null
    }

    activityMessages.forEach { indexed ->
        val bucket =
            if (indexed.message.kind == "trace" && !indexed.message.fileEdits.isNullOrEmpty()) {
                ActivityBucket.FILE
            } else {
                ActivityBucket.OTHER
            }
        val segmentId = indexed.message.activitySegmentId
        val segmentChanged =
            bucket == ActivityBucket.FILE &&
                runBucket == ActivityBucket.FILE &&
                !runSegmentId.isNullOrBlank() &&
                !segmentId.isNullOrBlank() &&
                runSegmentId != segmentId

        // 文件修改与普通工具活动使用不同信息密度；文件 segment 变化也表示一个新的原子修改批次。
        if ((runBucket != null && bucket != runBucket) || segmentChanged) flushRun()
        runBucket = bucket
        if (!segmentId.isNullOrBlank()) runSegmentId = segmentId
        run += indexed
    }

    flushRun()
    return units
}

private fun activityTurnLatencyMs(
    activityMessages: List<UiMessage>,
    visibleMessages: List<UiMessage>,
): Long? =
    visibleMessages.asReversed().firstNotNullOfOrNull { it.latencyMs?.takeIf { value -> value >= 0L } }
        ?: activityMessages.asReversed().firstNotNullOfOrNull {
            it.latencyMs?.takeIf { value -> value >= 0L }
        }

/** 已完成轮次的尾部 Activity 应位于最终答案之前；活跃轮次则保持实时到达顺序。 */
private fun normalizeCompletedTurnItems(
    items: List<ChatTimelineItem>,
    preserveTrailingActivity: Boolean,
): List<ChatTimelineItem> {
    if (preserveTrailingActivity || items.size < 2 || items.last() !is ChatTimelineItem.AgentActivity) {
        return items
    }

    var trailingStart = items.lastIndex
    while (trailingStart > 0 && items[trailingStart - 1] is ChatTimelineItem.AgentActivity) {
        trailingStart -= 1
    }
    val previous = items.getOrNull(trailingStart - 1)
    if (previous !is ChatTimelineItem.AssistantMessage) return items

    return buildList(items.size) {
        addAll(items.subList(0, trailingStart - 1))
        addAll(items.subList(trailingStart, items.size))
        add(previous)
    }
}
