package com.nanobotkt.feature.chat

import android.content.Context
import android.text.format.DateUtils
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Refresh
import androidx.compose.material.icons.rounded.Schedule
import androidx.compose.material.icons.rounded.WarningAmber
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.repeatOnLifecycle
import com.nanobotkt.core.designsystem.NanobotThemeDefaults
import com.nanobotkt.core.model.SessionAutomationJob
import java.text.DateFormat
import java.util.Locale
import kotlinx.coroutines.delay


/**
 * Session automation list, mirroring the RN `SessionAutomationList` component:
 * header with count badge, loading / error / empty status cards, and job rows
 * with a status dot, name, disabled badge, payload preview, and schedule +
 * next-run metadata. Data refreshes every [AUTOMATIONS_REFRESH_MS] ms while
 * visible, and again when the app returns to the foreground.
 */
@Composable
internal fun SessionAutomationList(
    sessionKey: String?,
    loadJobs: suspend (String) -> List<SessionAutomationJob>,
    visible: Boolean,
    modifier: Modifier = Modifier,
) {
    val context = LocalContext.current
    val state = remember { SessionAutomationState() }
    val currentLoadJobs by rememberUpdatedState(loadJobs)
    val renderer = remember(context) { SessionAutomationTextRenderer(context) }
    val lifecycleOwner = LocalLifecycleOwner.current
    var retryEpoch by remember { mutableIntStateOf(0) }

    // Manual retry restarts the current generation and shows the loading card.
    LaunchedEffect(sessionKey, visible, retryEpoch) {
        val key = sessionKey
        if (!visible || key == null || retryEpoch == 0) return@LaunchedEffect
        state.beginLoad()
        state.request(currentLoadJobs, key, showLoading = true)
    }

    // 初次加载与周期刷新都绑定 STARTED；锁屏时 Composition 仍存在，普通 LaunchedEffect 不会停。
    LaunchedEffect(sessionKey, visible, lifecycleOwner) {
        val key = sessionKey
        if (!visible || key == null) return@LaunchedEffect
        state.beginLoad()
        var firstForegroundEntry = true
        try {
            lifecycleOwner.lifecycle.repeatOnLifecycle(Lifecycle.State.STARTED) {
                // 首次进入显示 loading；锁屏恢复只静默刷新，避免已有健康列表闪回空白。
                state.request(currentLoadJobs, key, showLoading = firstForegroundEntry)
                firstForegroundEntry = false
                while (true) {
                    delay(AUTOMATIONS_REFRESH_MS)
                    state.request(currentLoadJobs, key, showLoading = false)
                }
            }
        } finally {
            state.endGeneration()
        }
    }

    Column(modifier = modifier.fillMaxWidth()) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Icon(
                    imageVector = Icons.Rounded.Schedule,
                    contentDescription = null,
                    tint = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.size(16.dp),
                )
                Spacer(Modifier.width(NanobotThemeDefaults.spacing.xs))
                Text(
                    text = stringResource(SessionAutomationRes.AUTOMATIONS),
                    style = MaterialTheme.typography.titleSmall,
                    color = MaterialTheme.colorScheme.onSurface,
                )
            }
            Spacer(Modifier.weight(1f))
            Surface(
                shape = MaterialTheme.shapes.medium,
                color = MaterialTheme.colorScheme.surfaceVariant,
            ) {
                Text(
                    text = stringResource(SessionAutomationRes.COUNT, state.jobs.size),
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(horizontal = 9.dp, vertical = 4.dp),
                )
            }
        }

        Spacer(Modifier.height(NanobotThemeDefaults.spacing.sm))

        when {
            state.loading -> StatusCard(tone = StatusCardTone.DEFAULT) {
                CircularProgressIndicator(
                    modifier = Modifier.size(18.dp),
                    strokeWidth = 2.dp,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                Text(
                    text = stringResource(SessionAutomationRes.LOADING),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }

            state.loadFailed -> StatusCard(
                tone = StatusCardTone.ERROR,
                onClick = { retryEpoch += 1 },
            ) {
                Icon(
                    imageVector = Icons.Rounded.WarningAmber,
                    contentDescription = null,
                    tint = MaterialTheme.colorScheme.error,
                    modifier = Modifier.size(16.dp),
                )
                Text(
                    text = stringResource(SessionAutomationRes.LOAD_FAILED),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.error,
                )
            }

            state.jobs.isEmpty() -> StatusCard(tone = StatusCardTone.DEFAULT) {
                Icon(
                    imageVector = Icons.Rounded.Refresh,
                    contentDescription = null,
                    tint = MaterialTheme.colorScheme.outline,
                    modifier = Modifier.size(15.dp),
                )
                Text(
                    text = stringResource(SessionAutomationRes.EMPTY),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }

            else -> LazyColumn(
                modifier = Modifier
                    .fillMaxWidth()
                    .heightIn(min = 64.dp, max = 420.dp),
                verticalArrangement = Arrangement.spacedBy(NanobotThemeDefaults.spacing.xs),
            ) {
                items(state.jobs, key = { it.id }) { job ->
                    AutomationRow(job = job, renderer = renderer)
                }
            }
        }
    }
}

private enum class StatusCardTone { DEFAULT, ERROR }

@Composable
private fun StatusCard(
    tone: StatusCardTone,
    onClick: (() -> Unit)? = null,
    content: @Composable () -> Unit,
) {
    val background = when (tone) {
        StatusCardTone.DEFAULT -> MaterialTheme.colorScheme.surfaceVariant
        StatusCardTone.ERROR -> MaterialTheme.colorScheme.errorContainer
    }
    Surface(
        shape = MaterialTheme.shapes.large,
        color = background,
        modifier = Modifier
            .fillMaxWidth()
            .heightIn(min = 48.dp)
            .then(if (onClick != null) Modifier.clickable(onClick = onClick) else Modifier),
    ) {
        Row(
            modifier = Modifier.padding(horizontal = 13.dp, vertical = 14.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(9.dp),
        ) {
            content()
        }
    }
}

@Composable
private fun AutomationRow(
    job: SessionAutomationJob,
    renderer: SessionAutomationTextRenderer,
) {
    val statusColor = if (!job.enabled) {
        MaterialTheme.colorScheme.outline
    } else if (job.state.lastStatus == "error") {
        MaterialTheme.colorScheme.error
    } else {
        // 主题没有额外的 success token；启用状态使用 primary 保持 Light/Dark 对比一致。
        MaterialTheme.colorScheme.primary
    }

    Surface(
        shape = MaterialTheme.shapes.large,
        color = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.6f),
        modifier = Modifier.fillMaxWidth(),
    ) {
        Row(
            modifier = Modifier.padding(horizontal = 12.dp, vertical = 11.dp),
            verticalAlignment = Alignment.Top,
            horizontalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            Box(
                modifier = Modifier
                    .padding(top = 6.dp)
                    .size(7.dp)
                    .background(statusColor, CircleShape),
            )
            Column(modifier = Modifier.weight(1f)) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(
                        text = job.name,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                        style = MaterialTheme.typography.titleSmall,
                        color = MaterialTheme.colorScheme.onSurface,
                        modifier = Modifier.weight(1f, fill = false),
                    )
                    if (!job.enabled) {
                        Spacer(Modifier.width(7.dp))
                        Surface(
                            shape = MaterialTheme.shapes.small,
                            color = MaterialTheme.colorScheme.surfaceVariant,
                        ) {
                            Text(
                                text = stringResource(SessionAutomationRes.DISABLED),
                                style = MaterialTheme.typography.labelSmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                                modifier = Modifier.padding(horizontal = 7.dp, vertical = 2.dp),
                            )
                        }
                    }
                }
                Spacer(Modifier.height(5.dp))
                Text(
                    text = job.payload.message,
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                Spacer(Modifier.height(7.dp))
                Text(
                    text = renderer.scheduleText(job) + " \u00b7 " + renderer.nextRunText(job),
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis,
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.outline,
                )
            }
        }
    }
}

/**
 * Android-resources-backed renderer for the pure [formatSessionSchedule] and
 * [formatSessionNextRun] results. Date/time formatting mirrors the RN
 * `Intl.DateTimeFormat` medium date + short time output; relative time and
 * durations use the platform's localized formatters.
 */
internal class SessionAutomationTextRenderer(context: Context) {
    private val appContext = context.applicationContext
    private val dateTimeFormatter: DateFormat = DateFormat.getDateTimeInstance(
        DateFormat.MEDIUM,
        DateFormat.SHORT,
        Locale.getDefault(),
    )

    fun scheduleText(job: SessionAutomationJob): String =
        when (val text = formatSessionSchedule(job)) {
            is SessionScheduleText.Local -> appContext.getString(SessionAutomationRes.SCHEDULE_LOCAL)
            is SessionScheduleText.Unknown -> appContext.getString(SessionAutomationRes.SCHEDULE_UNKNOWN)
            is SessionScheduleText.At -> format(
                SessionAutomationRes.SCHEDULE_AT,
                dateTimeFormatter.format(text.atMs),
            )
            is SessionScheduleText.Every -> format(
                SessionAutomationRes.SCHEDULE_EVERY,
                durationText(text.everyMs),
            )
            is SessionScheduleText.Cron -> if (text.tz != null) {
                format(SessionAutomationRes.SCHEDULE_CRON_WITH_TZ, text.expr, text.tz)
            } else {
                format(SessionAutomationRes.SCHEDULE_CRON, text.expr)
            }
        }

    fun nextRunText(job: SessionAutomationJob): String =
        when (val text = formatSessionNextRun(job)) {
            is SessionNextRunText.Disabled -> appContext.getString(SessionAutomationRes.NEXT_DISABLED)
            is SessionNextRunText.Pending -> appContext.getString(SessionAutomationRes.NEXT_PENDING)
            is SessionNextRunText.LocalTrigger -> appContext.getString(SessionAutomationRes.NEXT_LOCAL)
            is SessionNextRunText.None -> appContext.getString(SessionAutomationRes.NEXT_NONE)
            is SessionNextRunText.Relative -> format(
                SessionAutomationRes.NEXT_LABEL,
                relativeTimeText(text.nextRunAtMs),
            )
        }

    private fun durationText(ms: Long): String {
        val duration = sessionDuration(ms)
        val unitRes = when (duration.unit) {
            SessionDurationUnit.DAY -> SessionAutomationRes.DURATION_DAY
            SessionDurationUnit.HOUR -> SessionAutomationRes.DURATION_HOUR
            SessionDurationUnit.MINUTE -> SessionAutomationRes.DURATION_MINUTE
            SessionDurationUnit.SECOND -> SessionAutomationRes.DURATION_SECOND
        }
        val template = appContext.resources.getQuantityString(unitRes, duration.value.toInt())
        return String.format(Locale.getDefault(), template, duration.value)
    }

    private fun relativeTimeText(targetMs: Long): String = DateUtils.getRelativeTimeSpanString(
        targetMs,
        System.currentTimeMillis(),
        DateUtils.MINUTE_IN_MILLIS,
        DateUtils.FORMAT_NUMERIC_DATE or DateUtils.FORMAT_ABBREV_RELATIVE,
    ).toString()

    private fun format(resId: Int, vararg args: Any): String =
        String.format(Locale.getDefault(), appContext.getString(resId), *args)
}
