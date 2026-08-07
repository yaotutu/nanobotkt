package com.nanobotkt.feature.chat

import com.nanobotkt.core.model.SessionAutomationJob

/** String resources consumed by the session automation list. */
internal object SessionAutomationRes {
    val AUTOMATIONS = R.string.session_info_automations
    val COUNT = R.string.session_info_count
    val LOADING = R.string.session_info_loading
    val LOAD_FAILED = R.string.session_info_load_failed
    val EMPTY = R.string.session_info_empty
    val DISABLED = R.string.session_info_disabled
    val SCHEDULE_AT = R.string.session_info_schedule_at
    val SCHEDULE_EVERY = R.string.session_info_schedule_every
    val SCHEDULE_CRON = R.string.session_info_schedule_cron
    val SCHEDULE_CRON_WITH_TZ = R.string.session_info_schedule_cron_with_tz
    val SCHEDULE_LOCAL = R.string.session_info_schedule_local
    val SCHEDULE_UNKNOWN = R.string.session_info_schedule_unknown
    val NEXT_LABEL = R.string.session_info_next_label
    val NEXT_PENDING = R.string.session_info_next_pending
    val NEXT_DISABLED = R.string.session_info_next_disabled
    val NEXT_LOCAL = R.string.session_info_next_local
    val NEXT_NONE = R.string.session_info_next_none
    val DURATION_DAY = R.plurals.session_info_duration_day
    val DURATION_HOUR = R.plurals.session_info_duration_hour
    val DURATION_MINUTE = R.plurals.session_info_duration_minute
    val DURATION_SECOND = R.plurals.session_info_duration_second
}

/**
 * True when the job is a local (CLI) trigger, mirroring RN
 * `isLocalTrigger`: kind or payload kind `local_trigger`, or schedule kind `local`.
 */
internal fun isLocalTrigger(job: SessionAutomationJob): Boolean =
    job.kind == "local_trigger" ||
        job.payload.kind == "local_trigger" ||
        job.schedule.kind == "local"

/**
 * Mirrors RN `formatSessionSchedule`: local trigger first, then at / every / cron,
 * with "custom schedule" as the fallback.
 */
internal fun formatSessionSchedule(
    job: SessionAutomationJob,
): SessionScheduleText {
    if (isLocalTrigger(job)) return SessionScheduleText.Local
    return when (job.schedule.kind) {
        "at" -> job.schedule.atMs?.let { SessionScheduleText.At(it) }
        "every" -> job.schedule.everyMs?.let { SessionScheduleText.Every(it) }
        "cron" -> job.schedule.expr?.let {
            SessionScheduleText.Cron(it, job.schedule.tz)
        }
        else -> null
    } ?: SessionScheduleText.Unknown
}

/**
 * Mirrors RN `formatSessionNextRun`: disabled, pending, local trigger, then
 * relative time of the next run (or "no next run").
 */
internal fun formatSessionNextRun(
    job: SessionAutomationJob,
): SessionNextRunText {
    if (!job.enabled) return SessionNextRunText.Disabled
    if (job.state.pending == true) return SessionNextRunText.Pending
    if (isLocalTrigger(job)) return SessionNextRunText.LocalTrigger
    val next = job.state.nextRunAtMs
        ?: return SessionNextRunText.None
    return SessionNextRunText.Relative(next)
}

/**
 * Result of [formatSessionSchedule]. The caller resolves the locale text from
 * these parts (resource templates are in `SessionAutomationRes`).
 */
internal sealed interface SessionScheduleText {
    data object Local : SessionScheduleText
    data object Unknown : SessionScheduleText
    data class At(val atMs: Long) : SessionScheduleText
    data class Every(val everyMs: Long) : SessionScheduleText
    data class Cron(val expr: String, val tz: String?) : SessionScheduleText
}

/**
 * Result of [formatSessionNextRun]. The caller resolves the locale text.
 */
internal sealed interface SessionNextRunText {
    data object Disabled : SessionNextRunText
    data object Pending : SessionNextRunText
    data object LocalTrigger : SessionNextRunText
    data object None : SessionNextRunText
    data class Relative(val nextRunAtMs: Long) : SessionNextRunText
}

/**
 * Mirrors RN `formatSessionDuration`: the largest unit that divides the duration
 * evenly; anything else falls back to fractional minutes.
 */
internal data class SessionDuration(
    val unit: SessionDurationUnit,
    val value: Long,
)

internal enum class SessionDurationUnit { DAY, HOUR, MINUTE, SECOND }

internal fun sessionDuration(ms: Long): SessionDuration {
    val units = listOf(
        SessionDurationUnit.DAY to DAY_MS,
        SessionDurationUnit.HOUR to HOUR_MS,
        SessionDurationUnit.MINUTE to MINUTE_MS,
        SessionDurationUnit.SECOND to SECOND_MS,
    )
    for ((unit, sizeMs) in units) {
        if (ms >= sizeMs && ms % sizeMs == 0L) {
            return SessionDuration(unit, ms / sizeMs)
        }
    }
    return SessionDuration(SessionDurationUnit.MINUTE, ms / MINUTE_MS)
}

internal const val DAY_MS: Long = 86_400_000L
internal const val HOUR_MS: Long = 3_600_000L
internal const val MINUTE_MS: Long = 60_000L
internal const val SECOND_MS: Long = 1_000L
