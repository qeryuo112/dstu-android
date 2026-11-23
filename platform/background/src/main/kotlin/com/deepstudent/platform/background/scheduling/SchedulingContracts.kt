package com.deepstudent.platform.background.scheduling

/**
 * A WorkManager run for one opaque schedule generation. [occurrence] is [OccurrenceNo.PERIODIC]
 * for a periodic tick; the host resolves the actual slot occurrence from its own ledger.
 * There is deliberately no business payload, title, due date or local-store key here.
 */
data class ScheduledWorkRequest(val scheduleId: ScheduleId, val occurrence: OccurrenceNo) {
    init {
        require(occurrence == OccurrenceNo.PERIODIC || occurrence.value > 0) {
            "one-time work needs a positive occurrence"
        }
    }
}

sealed interface ScheduledWorkOutcome {
    /** The host committed its business fact or checkpoint. */
    data object Done : ScheduledWorkOutcome

    /** Transient failure; retry under the platform's finite exponential backoff. */
    data object Retry : ScheduledWorkOutcome

    /** Terminal failure; do not retry. */
    data object Failed : ScheduledWorkOutcome
}

/**
 * The only reminder identity carried by an alarm or notification intent. The host resolves fresh
 * due/disabled/idempotence state; the platform never owns that record.
 */
data class ReminderFire(val scheduleId: ScheduleId, val occurrence: OccurrenceNo) {
    init {
        require(occurrence.value > 0) { "reminder occurrence must be positive" }
    }
}

/** User-visible reminder content resolved by the host immediately before a notification. */
data class ReminderDispatch(val title: String, val body: String) {
    init {
        require(title.isNotBlank()) { "reminder title must not be blank" }
    }
}

/** Host-controlled rebuild trigger. None of these events grants a permission or a new schedule. */
enum class ReplanReason {
    BOOT_COMPLETED,
    USER_UNLOCKED,
    TIME_SET,
    TIMEZONE_CHANGED,
    PACKAGE_REPLACED,
    EXACT_ALARM_ACCESS_CHANGED,
    ;

    companion object {
        private const val ACTION_BOOT_COMPLETED = "android.intent.action.BOOT_COMPLETED"
        private const val ACTION_USER_UNLOCKED = "android.intent.action.USER_UNLOCKED"
        private const val ACTION_TIME_SET = "android.intent.action.TIME_SET"
        private const val ACTION_TIMEZONE_CHANGED = "android.intent.action.TIMEZONE_CHANGED"
        private const val ACTION_MY_PACKAGE_REPLACED = "android.intent.action.MY_PACKAGE_REPLACED"
        private const val ACTION_EXACT_ALARM_CHANGED =
            "android.app.action.SCHEDULE_EXACT_ALARM_PERMISSION_STATE_CHANGED"

        fun fromAction(action: String?): ReplanReason? = when (action) {
            ACTION_BOOT_COMPLETED -> BOOT_COMPLETED
            ACTION_USER_UNLOCKED -> USER_UNLOCKED
            ACTION_TIME_SET -> TIME_SET
            ACTION_TIMEZONE_CHANGED -> TIMEZONE_CHANGED
            ACTION_MY_PACKAGE_REPLACED -> PACKAGE_REPLACED
            ACTION_EXACT_ALARM_CHANGED -> EXACT_ALARM_ACCESS_CHANGED
            else -> null
        }
    }
}

/** Stable WorkManager names and tags. Tests rely on these exact strings. */
internal object SchedulingNames {
    private const val ROOT = "dstu.schedule"

    fun tag(scheduleId: ScheduleId): String = "$ROOT.${scheduleId.value}"

    fun oneTime(scheduleId: ScheduleId, occurrence: OccurrenceNo): String = "$ROOT.one.${scheduleId.value}.${occurrence.value}"

    fun periodic(scheduleId: ScheduleId): String = "$ROOT.periodic.${scheduleId.value}"
}
