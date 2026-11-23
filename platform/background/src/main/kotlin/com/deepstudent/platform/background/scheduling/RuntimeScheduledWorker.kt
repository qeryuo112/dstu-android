package com.deepstudent.platform.background.scheduling

import android.content.Context
import androidx.work.CoroutineWorker
import androidx.work.Data
import androidx.work.WorkerParameters
import androidx.work.workDataOf
import kotlinx.coroutines.CancellationException

/**
 * Same-process WorkManager worker. It reads only the opaque schedule id and occurrence, delegates
 * every business wait/lease/checkpoint to [RuntimeSchedulingHost], and never runs business logic
 * itself or attaches a business payload to InputData.
 */
class RuntimeScheduledWorker(appContext: Context, workerParams: WorkerParameters) : CoroutineWorker(appContext, workerParams) {
    override suspend fun doWork(): Result {
        val request = readRequest()
        val host = applicationContext as? RuntimeSchedulingHost
        if (request == null) {
            host?.let { SchedulingLog(it.schedulingLogger).event("background.schedule.work", "invalid_input") }
            return Result.failure()
        }
        if (host == null) return Result.failure()
        val startedAt = System.currentTimeMillis()
        return try {
            when (host.runScheduledWork(request)) {
                ScheduledWorkOutcome.Done -> {
                    log(host, request, "done", startedAt)
                    Result.success()
                }

                ScheduledWorkOutcome.Failed -> {
                    log(host, request, "failed", startedAt)
                    Result.failure()
                }

                ScheduledWorkOutcome.Retry -> {
                    log(host, request, "retry", startedAt)
                    retryOrFail()
                }
            }
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (_: Exception) {
            log(host, request, "exception", startedAt)
            retryOrFail()
        }
    }

    private fun readRequest(): ScheduledWorkRequest? {
        val occurrence = if (inputData.keyValueMap.containsKey(SchedulingCodec.KEY_OCCURRENCE)) {
            inputData.getLong(SchedulingCodec.KEY_OCCURRENCE, -1L)
        } else {
            null
        }
        return SchedulingCodec.requestFrom(inputData.getString(SchedulingCodec.KEY_SCHEDULE_ID), occurrence)
    }

    private fun retryOrFail(): Result = if (SchedulingPlan.shouldRetry(runAttemptCount)) Result.retry() else Result.failure()

    private fun log(host: RuntimeSchedulingHost, request: ScheduledWorkRequest, state: String, startedAt: Long) {
        SchedulingLog(host.schedulingLogger).event(
            event = "background.schedule.work",
            state = state,
            request = request,
            elapsedMs = System.currentTimeMillis() - startedAt,
        )
    }

    internal companion object {
        fun workInput(request: ScheduledWorkRequest): Data = workDataOf(
            SchedulingCodec.KEY_SCHEDULE_ID to request.scheduleId.value,
            SchedulingCodec.KEY_OCCURRENCE to request.occurrence.value,
        )
    }
}
