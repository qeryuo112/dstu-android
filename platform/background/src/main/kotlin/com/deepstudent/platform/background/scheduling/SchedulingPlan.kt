package com.deepstudent.platform.background.scheduling

import java.time.Duration
import java.time.Instant
import java.time.ZonedDateTime

/** Pure scheduling rules shared by the WorkManager adapter and its JVM tests. */
internal object SchedulingPlan {
    /** Total attempts includes the first run; retries use exponential backoff. */
    const val MAX_ATTEMPTS = 5
    const val BACKOFF_SECONDS = 30L
    val MIN_PERIODIC_INTERVAL: Duration = Duration.ofMinutes(15)

    fun initialDelayMillis(now: Instant, notBefore: Instant): Long = Duration.between(now, notBefore).toMillis().coerceAtLeast(0)

    fun initialDelayMillis(now: Instant, slot: ZonedDateTime): Long = initialDelayMillis(now, slot.toInstant())

    fun shouldRetry(runAttemptCount: Int): Boolean = runAttemptCount + 1 < MAX_ATTEMPTS

    fun requirePeriodic(repeatEvery: Duration) {
        require(!repeatEvery.isNegative && repeatEvery >= MIN_PERIODIC_INTERVAL) {
            "periodic schedule must repeat at least every ${MIN_PERIODIC_INTERVAL.toMinutes()} minutes"
        }
    }
}

/** Pure codec for the only two WorkManager InputData keys. No business field is accepted. */
internal object SchedulingCodec {
    const val KEY_SCHEDULE_ID = "schedule_id"
    const val KEY_OCCURRENCE = "occurrence_no"

    fun requestFrom(scheduleId: String?, occurrence: Long?): ScheduledWorkRequest? {
        val parsedId = ScheduleId.parseOrNull(scheduleId) ?: return null
        if (occurrence == null || occurrence < 0) return null
        return ScheduledWorkRequest(parsedId, OccurrenceNo(occurrence))
    }
}
