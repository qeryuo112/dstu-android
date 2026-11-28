package com.deepstudent.features.chat.backend.application

import com.deepstudent.core.common.id.TaskId
import com.deepstudent.core.common.logging.Logger
import com.deepstudent.core.common.logging.NoopLogger
import com.deepstudent.core.common.time.SystemWallClock
import com.deepstudent.core.common.time.WallClock
import com.deepstudent.features.chat.api.ChatTurnContract
import com.deepstudent.features.chat.api.ChatTurnRecoveryPort
import com.deepstudent.services.runtime.api.RecoveryDecision
import com.deepstudent.services.runtime.api.RecoveryHandler
import com.deepstudent.services.runtime.api.RuntimeSnapshot
import com.deepstudent.services.runtime.api.RuntimeState

/**
 * Reads the authoritative shared DB before ledger interruption. It settles only the running
 * block; a fact-less error block must never become a fabricated FAILED business completion.
 */
class ChatTurnRecoveryHandler(
    private val recovery: ChatTurnRecoveryPort,
    private val clock: WallClock = SystemWallClock,
    private val logger: Logger = NoopLogger,
) : RecoveryHandler {
    override suspend fun decide(snapshot: RuntimeSnapshot): RecoveryDecision {
        recover(snapshot)
        return RecoveryDecision.Interrupted
    }

    /** Shared by [decide] and application recovery bookkeeping before the ledger terminal write. */
    suspend fun recover(snapshot: RuntimeSnapshot): RuntimeState? = recovery.settleInterrupted(
        snapshot.taskId,
        snapshot.ownership.generation,
        ChatTurnContract.sessionId(snapshot.taskId),
        clock.now(),
    )

    /** Exposed for the startup composition to inspect durable text without the runtime checkpoint. */
    suspend fun loadInFlight(taskId: TaskId, generation: Long): com.deepstudent.features.chat.api.ChatTurnInFlight? =
        recovery.loadInFlight(taskId, generation, ChatTurnContract.sessionId(taskId))
}
