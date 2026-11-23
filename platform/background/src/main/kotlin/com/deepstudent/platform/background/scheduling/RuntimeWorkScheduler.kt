package com.deepstudent.platform.background.scheduling

import android.content.Context
import androidx.work.BackoffPolicy
import androidx.work.Constraints
import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.ExistingWorkPolicy
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.PeriodicWorkRequestBuilder
import androidx.work.WorkInfo
import androidx.work.WorkManager
import androidx.work.await
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import java.time.Duration
import java.time.Instant
import java.time.ZonedDateTime
import java.util.UUID
import java.util.concurrent.TimeUnit

/** Observable platform-side WorkManager state; contains no business payload. */
data class ScheduledWorkSnapshot(
    val workId: UUID,
    val state: WorkInfo.State,
    val runAttemptCount: Int,
    val tags: Set<String>,
    val nextScheduledAtMillis: Long,
)

/**
 * Thin WorkManager adapter. One-time work uses a unique name per schedule+occurrence with
 * [ExistingWorkPolicy.KEEP]; periodic work uses one unique name per schedule with
 * [ExistingPeriodicWorkPolicy.UPDATE], so re-planning does not reset the existing period anchor.
 * Callers persist their own due/occurrence ledger before or after these suspend calls succeed.
 */
class RuntimeWorkScheduler(context: Context) {
    private val appContext = context.applicationContext
    private val workManager: WorkManager get() = WorkManager.getInstance(appContext)

    /**
     * KEEP makes a duplicate enqueue for the same occurrence a no-op.
     * [requiresDeviceIdle] is a test-only knob for a real JobScheduler stop/force-run check; it
     * opts out of greedy scheduling so the host can inspect and force the job. Production defaults
     * stay false.
     */
    suspend fun enqueueOneTime(scheduleId: ScheduleId, occurrence: OccurrenceNo, notBefore: Instant, requiresDeviceIdle: Boolean = false) {
        require(occurrence.value > 0) { "one-time work needs a positive occurrence" }
        val delay = SchedulingPlan.initialDelayMillis(Instant.now(), notBefore)
        val constraints = Constraints.Builder()
            .setRequiresDeviceIdle(requiresDeviceIdle)
            .build()
        val request = OneTimeWorkRequestBuilder<RuntimeScheduledWorker>()
            .setConstraints(constraints)
            .setInputData(RuntimeScheduledWorker.workInput(ScheduledWorkRequest(scheduleId, occurrence)))
            .setInitialDelay(delay, TimeUnit.MILLISECONDS)
            .addTag(SchedulingNames.tag(scheduleId))
            .apply {
                if (!requiresDeviceIdle) setBackoffCriteria(BackoffPolicy.EXPONENTIAL, SchedulingPlan.BACKOFF_SECONDS, TimeUnit.SECONDS)
            }
            .build()
        workManager.enqueueUniqueWork(
            SchedulingNames.oneTime(scheduleId, occurrence),
            ExistingWorkPolicy.KEEP,
            request,
        ).await()
    }

    /** UPDATE replaces the specification without resetting an active periodic anchor. */
    suspend fun enqueuePeriodic(scheduleId: ScheduleId, repeatEvery: Duration, nextSlot: ZonedDateTime?) {
        SchedulingPlan.requirePeriodic(repeatEvery)
        val uniqueName = SchedulingNames.periodic(scheduleId)
        val existing = workManager.getWorkInfosForUniqueWorkFlow(uniqueName).first()
        val active = existing.firstOrNull { !it.state.isFinished }
        val builder = PeriodicWorkRequestBuilder<RuntimeScheduledWorker>(
            repeatEvery.toMillis(),
            TimeUnit.MILLISECONDS,
        )
            .setInputData(
                RuntimeScheduledWorker.workInput(ScheduledWorkRequest(scheduleId, OccurrenceNo.PERIODIC)),
            )
            .setBackoffCriteria(BackoffPolicy.EXPONENTIAL, SchedulingPlan.BACKOFF_SECONDS, TimeUnit.SECONDS)
            .addTag(SchedulingNames.tag(scheduleId))
        // UPDATE preserves lastEnqueueTime and periodCount but replaces initialDelay.
        val firstDelay = active?.initialDelayMillis ?: nextSlot?.let { SchedulingPlan.initialDelayMillis(Instant.now(), it) }
        if (firstDelay != null) builder.setInitialDelay(firstDelay, TimeUnit.MILLISECONDS)
        workManager.enqueueUniquePeriodicWork(
            uniqueName,
            ExistingPeriodicWorkPolicy.UPDATE,
            builder.build(),
        ).await()
    }

    /** Cancels by the exact unique name for this occurrence. */
    suspend fun cancelOneTime(scheduleId: ScheduleId, occurrence: OccurrenceNo) {
        require(occurrence.value > 0) { "one-time work needs a positive occurrence" }
        workManager.cancelUniqueWork(SchedulingNames.oneTime(scheduleId, occurrence)).await()
    }

    /** Cancels the single periodic unique name for this schedule. */
    suspend fun cancelPeriodic(scheduleId: ScheduleId) {
        workManager.cancelUniqueWork(SchedulingNames.periodic(scheduleId)).await()
    }

    /** Device tests read WorkInfo UUID/state/attempt count here; production can log the same data. */
    fun observe(scheduleId: ScheduleId): Flow<List<ScheduledWorkSnapshot>> =
        workManager.getWorkInfosByTagFlow(SchedulingNames.tag(scheduleId))
            .map { infos -> infos.map(::toSnapshot).sortedBy { it.workId.toString() } }

    suspend fun snapshot(scheduleId: ScheduleId): List<ScheduledWorkSnapshot> = observe(scheduleId).first()

    private fun toSnapshot(info: WorkInfo): ScheduledWorkSnapshot =
        ScheduledWorkSnapshot(info.id, info.state, info.runAttemptCount, info.tags, info.nextScheduleTimeMillis)
}
