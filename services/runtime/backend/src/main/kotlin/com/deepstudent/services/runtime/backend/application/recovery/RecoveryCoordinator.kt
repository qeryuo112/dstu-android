package com.deepstudent.services.runtime.backend.application.recovery

import com.deepstudent.core.common.logging.Logger
import com.deepstudent.core.common.time.MonotonicClock
import com.deepstudent.services.runtime.api.RecoveryPublisher
import com.deepstudent.services.runtime.api.RecoveryRegistration
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext

/** One startup pass. The ledger and supervisor remain the sole task authorities. */
class RecoveryCoordinator(
    private val actions: RecoveryActions,
    registrations: List<RecoveryRegistration>,
    private val publisher: RecoveryPublisher,
    logger: Logger,
    clock: MonotonicClock,
    private val batchSize: Int = 100,
) {
    private val registry = RecoveryRegistry(registrations)
    private val log = RecoveryLog(logger, clock)
    private val mutex = Mutex()

    init {
        require(batchSize in 1..1000) { "invalid recovery batch size" }
    }

    suspend fun recover(): RecoveryReport = mutex.withLock {
        val session = RecoverySession(actions, registry, publisher, log, batchSize)
        var failure: Throwable? = null
        try {
            if (session.claimCandidates()) {
                session.reconcilePending()
                session.findCompletions()
                session.resolveUnfinished()
            }
        } catch (thrown: Throwable) {
            failure = thrown
            throw thrown
        } finally {
            try {
                withContext(NonCancellable) { session.finish() }
            } catch (cleanup: Throwable) {
                if (failure == null) throw cleanup
                if (cleanup !== failure) failure.addSuppressed(cleanup)
            }
        }
        session.report()
    }
}

data class RecoveryReport(
    val claimed: Int,
    val reconciled: Int,
    val resumed: Int,
    val interrupted: Int,
    val confirmationRequired: Int,
    val cancelled: Int,
    val blocked: Int,
    val failures: Int,
)
