package com.deepstudent.features.environments.backend

import com.deepstudent.core.common.time.WallClock
import com.deepstudent.features.environments.api.EnvironmentCommandException
import com.deepstudent.features.environments.api.EnvironmentFailure
import com.deepstudent.features.environments.api.EnvironmentFailureCode
import com.deepstudent.features.environments.api.EnvironmentPhase
import com.deepstudent.features.environments.api.EnvironmentProgress
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.withContext

/** Persist intent before effects, and completion before publishing it to observers. */
internal class EnvironmentTransaction(
    private val store: EnvironmentJournalStore,
    private val clock: WallClock,
    initial: EnvironmentJournal,
    private val update: EnvironmentUpdate,
) {
    var journal: EnvironmentJournal = initial
        private set

    fun commit(next: EnvironmentJournal) {
        val durable = next.copy(updatedAt = clock.now())
        try {
            store.save(durable)
        } catch (failure: Exception) {
            EnvironmentLog.failure("journal-write-rejected", failure, "checkpoint=${next.checkpoint}")
            // Never overwrite an uncertain commit with a stale failure journal.
            throw EnvironmentJournalWriteException(failure)
        }
        journal = durable
        EnvironmentLog.event("journal-committed", fields())
    }

    fun checkpoint(next: EnvironmentCheckpoint) {
        commit(journal.copy(checkpoint = next, failure = null))
        progress(checkpointPhase(next), EnvironmentProgress(stage = checkpointStage(next)))
    }

    fun progress(phase: EnvironmentPhase, progress: EnvironmentProgress) {
        update(journal, phase, progress)
    }

    suspend fun run(operation: suspend () -> Unit): EnvironmentJournal {
        try {
            operation()
        } catch (cancelled: CancellationException) {
            withContext(NonCancellable) { fail(EnvironmentFailureCode.CANCELLED, true) }
            throw cancelled
        } catch (failure: EnvironmentJournalWriteException) {
            throw failure
        } catch (failure: Exception) {
            val typed = (failure as? EnvironmentCommandException)?.failure
            val code = typed?.code ?: if (journal.pendingRecipeId != null) {
                EnvironmentFailureCode.PACKAGE_INSTALL_FAILED
            } else environmentFailureCode(failure)
            EnvironmentLog.failure("operation-failed", failure, "${fields()} code=$code")
            // Only controlled messages enter durable state; exceptions can contain URIs or scripts.
            fail(code, typed?.recoverable ?: true)
        }
        return journal
    }

    private fun fail(code: EnvironmentFailureCode, recoverable: Boolean) {
        commit(journal.copy(failure = EnvironmentFailure(code, EnvironmentFailureMessages.forCode(code), recoverable)))
        val phase = if (code == EnvironmentFailureCode.CANCELLED) EnvironmentPhase.CANCELLED else EnvironmentPhase.FAILED
        progress(phase, EnvironmentProgress(stage = checkpointStage(journal.checkpoint)))
    }

    private fun fields(): String = "task=${journal.lastTaskId} checkpoint=${journal.checkpoint} " +
        "generation=${journal.generationId} active=${journal.activeGeneration != null} " +
        "pending=${journal.pendingRecipeId} receipts=${journal.receipts.size}"
}

internal class EnvironmentJournalWriteException(cause: Throwable) : EnvironmentCommandException(
    EnvironmentFailure(EnvironmentFailureCode.CORRUPT_STATE, "environment journal could not be committed", false),
    cause,
)
