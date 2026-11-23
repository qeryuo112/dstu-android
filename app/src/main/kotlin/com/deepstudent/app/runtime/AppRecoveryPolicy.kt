package com.deepstudent.app.runtime

import com.deepstudent.app.runtime.script.MainScriptResources
import com.deepstudent.features.chat.backend.application.ChatCheckpointSource
import com.deepstudent.features.chat.backend.application.RuntimeChatStreams
import com.deepstudent.services.runtime.api.BusinessCompletionSource
import com.deepstudent.services.runtime.api.RecoveryDecision
import com.deepstudent.services.runtime.api.RecoveryHandler
import com.deepstudent.services.runtime.api.RecoveryRegistration
import com.deepstudent.services.runtime.api.RuntimeSnapshot
import com.deepstudent.services.runtime.api.RuntimeState
import com.deepstudent.services.runtime.api.TaskKind

/** The four S1 task kinds the main process can still own and must settle on startup. */
internal enum class AppRecoverableTask(val kind: TaskKind) {
    NOTE_CREATE(MainRuntimeResources.NOTE_CREATE),
    NOTE_IMPORT(MainRuntimeResources.NOTE_IMPORT),
    CHAT(RuntimeChatStreams.KIND),
    SCRIPT(MainScriptResources.KIND),
}

/**
 * S2.22 startup policy for [AppRecoverableTask]. Decisions are inspection only: the policy never
 * writes the ledger, starts a handler or commits a business fact.
 *
 * The coordinator always runs its authoritative fact reconciliation before asking for a decision,
 * so reaching this policy already means either a completion was projected or the business source
 * proved there is none (a failed lookup blocks the task instead). This policy therefore never
 * repeats that read, and resumption is limited to work whose side effect is one idempotent
 * business transaction keyed by task id and generation.
 *
 * - `notes.create` commits the note, its resource, history and the completion fact in one VFS
 *   transaction under a unique `(task_id, generation)` identity, so a retry cannot duplicate it.
 *   It resumes only after the fact proof above and only when no cancellation was requested.
 * - `notes.import_stream` and `chat.minimal_stream` keep a partial checkpoint as visible work.
 *   Resuming them would replay transport or append the same text twice, so they settle as
 *   `INTERRUPTED` and the checkpoint stays readable.
 * - `script.ptc_minimal` settles as `confirmationRequired`. A started script has no receipt, so
 *   whether its tool calls completed is unknown; rerunning it could repeat an effect, and the
 *   current `echo`/`add` tools do not make that inference safe for future tools.
 *
 * `WAITING_APPROVAL` and `PAUSED` are never resumed or terminated: they return
 * [RecoveryDecision.KeepPaused], which leaves the gate, checkpoint and confirmation marker exactly
 * as they were while only releasing the recovery claim.
 */
internal class AppRecoveryPolicy(private val completionSources: Map<AppRecoverableTask, BusinessCompletionSource>) {
    init {
        require(completionSources.keys == AppRecoverableTask.entries.toSet()) {
            "every recoverable task kind needs one completion source"
        }
    }

    val registrations: List<RecoveryRegistration> = AppRecoverableTask.entries.map { task ->
        RecoveryRegistration(
            task.kind,
            completionSources.getValue(task),
            RecoveryHandler { snapshot -> decide(task, snapshot) },
        )
    }

    fun decide(task: AppRecoverableTask, snapshot: RuntimeSnapshot): RecoveryDecision {
        require(snapshot.kind == task.kind) { "recovery registration does not match task kind" }
        if (snapshot.execution.cancelRequested) return RecoveryDecision.Interrupted
        if (snapshot.state == RuntimeState.WAITING_APPROVAL || snapshot.state == RuntimeState.PAUSED) {
            return RecoveryDecision.KeepPaused
        }
        return when (task) {
            AppRecoverableTask.NOTE_CREATE -> RecoveryDecision.Resume
            AppRecoverableTask.NOTE_IMPORT, AppRecoverableTask.CHAT -> RecoveryDecision.Interrupted
            AppRecoverableTask.SCRIPT -> RecoveryDecision.ConfirmationRequired
        }
    }

    companion object {
        fun forRuntime(runtime: MainRuntimeResources): AppRecoveryPolicy = AppRecoveryPolicy(
            mapOf(
                AppRecoverableTask.NOTE_CREATE to runtime.completionSource,
                AppRecoverableTask.NOTE_IMPORT to runtime.completionSource,
                AppRecoverableTask.CHAT to ChatCheckpointSource,
                AppRecoverableTask.SCRIPT to runtime.script.completionSource,
            ),
        )
    }
}
