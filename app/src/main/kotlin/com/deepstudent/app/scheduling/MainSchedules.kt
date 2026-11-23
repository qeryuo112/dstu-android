package com.deepstudent.app.scheduling

import android.content.Context
import com.deepstudent.app.runtime.MainRuntimeResources
import com.deepstudent.app.storage.AndroidOperationLogger
import com.deepstudent.core.common.json.JsonValue
import com.deepstudent.core.common.logging.LogLevel
import com.deepstudent.core.common.logging.LogRecord
import com.deepstudent.platform.background.scheduling.OccurrenceNo
import com.deepstudent.platform.background.scheduling.ReminderDispatch
import com.deepstudent.platform.background.scheduling.ReminderFire
import com.deepstudent.platform.background.scheduling.RuntimeReminderScheduler
import com.deepstudent.platform.background.scheduling.RuntimeWorkScheduler
import com.deepstudent.platform.background.scheduling.ScheduleId
import com.deepstudent.platform.background.scheduling.ScheduledWorkOutcome
import com.deepstudent.platform.background.scheduling.ScheduledWorkRequest
import com.deepstudent.services.runtime.api.TaskHandle
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import java.time.Duration
import java.time.Instant
import java.time.ZoneId
import java.util.UUID

/** Device-local schedule store and admission; the application runtime owns execution and recovery. */
internal class MainSchedules(context: Context, runtime: suspend () -> MainRuntimeResources) {
    private val app = context.applicationContext
    private val store = LocalScheduleStore(app)
    private val notes = ScheduledNotes(runtime)
    internal val work = RuntimeWorkScheduler(app)
    internal val reminders = RuntimeReminderScheduler(app)
    private val operations = Mutex()
    val rows get() = store.changes

    suspend fun create(draft: ScheduledDraft, due: Instant, minutes: Int = 0): LocalSchedule =
        register(LocalSchedule(ScheduleId.parse(UUID.randomUUID().toString()), draft, due, periodMinutes = minutes))

    internal suspend fun register(row: LocalSchedule): LocalSchedule = withContext(Dispatchers.IO) {
        check(!row.requiresIdle || app.packageName == "com.deepstudent.app.s2device")
        operations.withLock {
            store.create(row)
            replan(row)
            row
        }
    }

    suspend fun runNow(id: ScheduleId): ScheduledWorkOutcome = run(ScheduledWorkRequest(id, OccurrenceNo.PERIODIC), allowEarly = true)

    suspend fun runWorker(request: ScheduledWorkRequest): ScheduledWorkOutcome = run(request, allowEarly = false)

    private suspend fun run(request: ScheduledWorkRequest, allowEarly: Boolean): ScheduledWorkOutcome = withContext(Dispatchers.IO) {
        val admission = operations.withLock { prepare(request, allowEarly) } ?: return@withContext ScheduledWorkOutcome.Done
        val outcome = notes.execute(admission.handle)
        if (outcome.state.terminal) operations.withLock { acknowledgeWork(admission) }
        log("background.schedule.run", outcome.state.name.lowercase(), outcome.taskId)
        outcome.state.workOutcome()
    }

    private suspend fun prepare(request: ScheduledWorkRequest, allowEarly: Boolean): Admission? {
        val row = store.find(request.scheduleId) ?: return null
        val now = Instant.now()
        if (!row.enabled || row.draft.purpose != SchedulePurpose.NOTE || (!allowEarly && now.isBefore(row.due))) return null
        val occurrence = selectOccurrence(row, request, now) ?: return null
        if (occurrence.value <= row.lastHandled) return null
        if (row.activeOccurrence != occurrence.value) store.put(row.copy(activeOccurrence = occurrence.value))
        return Admission(row.id, occurrence, notes.prepare(row, occurrence))
    }

    private fun selectOccurrence(row: LocalSchedule, request: ScheduledWorkRequest, now: Instant): OccurrenceNo? {
        if (request.occurrence.value == 0L) {
            return if (row.activeOccurrence > 0L) OccurrenceNo(row.activeOccurrence) else row.currentOccurrence(now)
        }
        return request.occurrence.takeIf { row.periodMinutes == 0 && it == row.occurrence }
    }

    private suspend fun acknowledgeWork(admission: Admission) {
        val row = store.find(admission.id) ?: return
        if (row.enabled && row.activeOccurrence == admission.occurrence.value) store.put(row.handled(admission.occurrence))
    }

    suspend fun cancel(id: ScheduleId) = withContext(Dispatchers.IO) {
        operations.withLock {
            val row = store.find(id) ?: return@withLock
            store.put(row.copy(enabled = false))
            if (row.activeOccurrence > 0) notes.cancel(row, OccurrenceNo(row.activeOccurrence))
            cancelPlatform(row)
            store.remove(id)
            log("background.schedule.cancel", "removed")
        }
    }

    suspend fun replanAll(reason: String) = withContext(Dispatchers.IO) {
        operations.withLock {
            for (row in store.current().filter { it.enabled }) replanSafely(row)
            log("background.schedule.replan", reason)
        }
    }

    private suspend fun replanSafely(row: LocalSchedule) {
        try {
            replan(row)
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (_: Exception) {
            log("background.schedule.replan", "platform_failed")
        }
    }

    private suspend fun replan(row: LocalSchedule) {
        when {
            row.draft.purpose == SchedulePurpose.REMINDER -> {
                val fire = ReminderFire(row.id, row.occurrence)
                if (row.due.isAfter(Instant.now())) {
                    reminders.schedule(fire, row.due)
                } else {
                    dispatch(row, fire) { reminders.show(fire, it) }
                }
            }

            row.periodMinutes != 0 -> work.enqueuePeriodic(
                row.id,
                Duration.ofMinutes(row.periodMinutes.toLong()),
                row.due.atZone(ZoneId.systemDefault()),
            )

            else -> work.enqueueOneTime(row.id, row.occurrence, row.due, row.requiresIdle)
        }
    }

    private suspend fun cancelPlatform(row: LocalSchedule) {
        when {
            row.draft.purpose == SchedulePurpose.REMINDER -> {
                val fire = ReminderFire(row.id, row.occurrence)
                reminders.cancel(fire)
                reminders.cancelNotification(fire)
            }

            row.periodMinutes != 0 -> work.cancelPeriodic(row.id)

            else -> work.cancelOneTime(row.id, row.occurrence)
        }
    }

    suspend fun dispatchReminder(fire: ReminderFire, deliver: (ReminderDispatch) -> Boolean) = withContext(Dispatchers.IO) {
        operations.withLock {
            store.find(fire.scheduleId)?.let { dispatch(it, fire, deliver) }
            Unit
        }
    }

    private suspend fun dispatch(row: LocalSchedule, fire: ReminderFire, deliver: (ReminderDispatch) -> Boolean) {
        if (!row.enabled || row.draft.purpose != SchedulePurpose.REMINDER || row.occurrence != fire.occurrence) return
        if (Instant.now().isBefore(row.due)) return
        val delivered = deliver(ReminderDispatch(row.draft.title, row.draft.body))
        if (delivered) store.put(row.handled(fire.occurrence))
        log("background.reminder.delivery", if (delivered) "acknowledged" else "blocked")
    }

    private data class Admission(val id: ScheduleId, val occurrence: OccurrenceNo, val handle: TaskHandle)

    private fun log(event: String, state: String, task: com.deepstudent.core.common.id.TaskId? = null) {
        val fields = JsonValue.Object.Builder().put("owner", "app.scheduling").put("state", state).build()
        AndroidOperationLogger.log(LogRecord(LogLevel.INFO, event, taskId = task, fields = fields))
    }
}
