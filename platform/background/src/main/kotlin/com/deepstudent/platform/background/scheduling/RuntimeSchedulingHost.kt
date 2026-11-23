package com.deepstudent.platform.background.scheduling

import android.content.Intent
import com.deepstudent.core.common.logging.Logger
import kotlinx.coroutines.CoroutineScope

/**
 * Implemented by the main-process Application and wired to the real runtime and local stores.
 * The platform passes only opaque schedule identities; every business wait, lease, due check,
 * local-store read and idempotence decision belongs to this host.
 */
interface RuntimeSchedulingHost {
    val schedulingScope: CoroutineScope
    val schedulingLogger: Logger

    /**
     * Runs one scheduled occurrence. The host must acquire its own lease/checkpoint and make the
     * call idempotent for (scheduleId, occurrence). The platform adds no business payload.
     */
    suspend fun runScheduledWork(request: ScheduledWorkRequest): ScheduledWorkOutcome

    /**
     * Atomically decides and delivers one reminder. The host must lock its schedule store, re-read
     * fresh state (enabled, due window, not already acknowledged), and call [deliver] only for a
     * current occurrence. [deliver] returns true only when the notification was actually shown.
     * The host records the occurrence as acknowledged only after that true result; disabled,
     * expired, duplicate or failed-show occurrences remain unacknowledged.
     */
    suspend fun dispatchReminder(fire: ReminderFire, deliver: (ReminderDispatch) -> Boolean)

    /** Direct Activity target; Android 12+ prevents a notification broadcast trampoline. */
    fun reminderActivityIntent(fire: ReminderFire): Intent? = null

    /**
     * Rebuilds all platform registrations for the new system state. The host cancels and re-enqueues
     * through [RuntimeWorkScheduler] / [RuntimeReminderScheduler]; the platform never derives a due
     * list from its own state and never grants exact-alarm access.
     */
    suspend fun replanSchedules(reason: ReplanReason)
}
