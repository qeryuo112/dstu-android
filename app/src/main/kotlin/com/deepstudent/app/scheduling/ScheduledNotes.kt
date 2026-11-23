package com.deepstudent.app.scheduling

import com.deepstudent.app.runtime.MainRuntimeResources
import com.deepstudent.core.common.json.JsonValue
import com.deepstudent.platform.background.scheduling.OccurrenceNo
import com.deepstudent.platform.background.scheduling.ScheduledWorkOutcome
import com.deepstudent.services.runtime.api.RuntimeState
import com.deepstudent.services.runtime.api.ScheduledRunOutcome
import com.deepstudent.services.runtime.api.TaskHandle
import com.deepstudent.services.runtime.api.TaskSubmission
import com.deepstudent.services.runtime.backend.RuntimeController

/** Both user and system entry points submit through the same ledger identity and note handler. */
internal class ScheduledNotes(private val load: suspend () -> MainRuntimeResources) {
    suspend fun prepare(row: LocalSchedule, occurrence: OccurrenceNo): TaskHandle =
        controller().prepareScheduled(submission(row, occurrence))

    suspend fun execute(handle: TaskHandle): ScheduledRunOutcome = controller().runPreparedScheduled(handle)

    suspend fun cancel(row: LocalSchedule, occurrence: OccurrenceNo) {
        val controller = controller()
        val task = controller.prepareScheduled(submission(row, occurrence)).taskId
        val generation = controller.snapshot(task)?.ownership?.generation ?: return
        controller.cancelIfGeneration(task, generation)
    }

    private suspend fun controller(): RuntimeController = load().controller
}

internal fun submission(row: LocalSchedule, occurrence: OccurrenceNo): TaskSubmission = TaskSubmission(
    MainRuntimeResources.SCHEDULED_NOTE_CREATE,
    JsonValue.obj("title" to JsonValue.text(row.draft.title), "content" to JsonValue.text(row.draft.body)),
    dedupeKey = "schedule:${row.id.value}:${occurrence.value}",
)

internal fun RuntimeState.workOutcome(): ScheduledWorkOutcome = when (this) {
    RuntimeState.COMPLETED, RuntimeState.CANCELLED -> ScheduledWorkOutcome.Done
    RuntimeState.FAILED, RuntimeState.INTERRUPTED -> ScheduledWorkOutcome.Failed
    else -> ScheduledWorkOutcome.Retry
}
