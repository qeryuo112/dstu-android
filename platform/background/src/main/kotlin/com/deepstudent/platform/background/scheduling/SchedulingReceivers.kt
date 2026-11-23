package com.deepstudent.platform.background.scheduling

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeoutOrNull
import java.util.concurrent.atomic.AtomicBoolean

private const val HOST_DEADLINE_MS = 10_000L

/**
 * Alarm callback. The alarm intent carries only schedule id + occurrence. The host locks its
 * schedule store and calls the supplied deliver lambda under that decision; the platform only shows
 * the notification and never acknowledges anything by itself.
 */
class ReminderAlarmReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        val fire = ReminderIntents.parse(intent, ReminderActions.DUE) ?: return
        val host = context.applicationContext as? RuntimeSchedulingHost ?: return
        val startedAt = System.currentTimeMillis()
        val log = SchedulingLog(host.schedulingLogger)
        val scheduler = RuntimeReminderScheduler(context)
        launchBanked(host.schedulingScope, block = {
            val dispatched = withTimeoutOrNull(HOST_DEADLINE_MS) {
                host.dispatchReminder(fire) { dispatch -> scheduler.show(fire, dispatch) }
                true
            } ?: false
            log.event(
                "background.reminder",
                if (dispatched) "dispatched" else "timeout",
                fire = fire,
                elapsedMs = elapsed(startedAt),
            )
        }, onFailure = {
            log.event("background.reminder", "failed", fire = fire, elapsedMs = elapsed(startedAt))
        })
    }
}

/**
 * System-state replan trigger. It never derives a schedule itself and never grants exact-alarm
 * access; the host decides what to cancel/re-enqueue.
 */
class SchedulingRescheduleReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        val reason = ReplanReason.fromAction(intent.action) ?: return
        val host = context.applicationContext as? RuntimeSchedulingHost ?: return
        val startedAt = System.currentTimeMillis()
        val log = SchedulingLog(host.schedulingLogger)
        launchBanked(host.schedulingScope, block = {
            val replanned = withTimeoutOrNull(HOST_DEADLINE_MS) {
                host.replanSchedules(reason)
                true
            } ?: false
            log.event(
                "background.schedule.replan",
                if (replanned) "done" else "timeout",
                elapsedMs = elapsed(startedAt),
            )
        }, onFailure = {
            log.event("background.schedule.replan", "failed", elapsedMs = elapsed(startedAt))
        })
    }
}

private fun elapsed(startedAt: Long): Long = System.currentTimeMillis() - startedAt

/**
 * goAsync wrapper that finishes exactly once. If the host scope is already cancelled, the launch
 * returns an already-cancelled job and invokeOnCompletion still finishes the pending result.
 */
private fun BroadcastReceiver.launchBanked(scope: CoroutineScope, block: suspend () -> Unit, onFailure: () -> Unit) {
    val pending = goAsync()
    val finished = AtomicBoolean(false)
    val finish = {
        if (finished.compareAndSet(false, true)) pending.finish()
    }
    val job = runCatching {
        scope.launch(Dispatchers.IO) {
            try {
                block()
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (_: Exception) {
                onFailure()
            }
        }
    }.getOrNull()
    if (job == null) finish() else job.invokeOnCompletion { finish() }
}
