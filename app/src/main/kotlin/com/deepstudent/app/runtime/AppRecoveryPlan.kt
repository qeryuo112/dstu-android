package com.deepstudent.app.runtime

import com.deepstudent.app.storage.AndroidOperationLogger
import com.deepstudent.core.common.logging.Logger
import com.deepstudent.core.common.time.MonotonicClock
import com.deepstudent.core.common.time.SystemMonotonicClock
import com.deepstudent.core.common.time.SystemWallClock
import com.deepstudent.services.runtime.api.DeadOwnerPredicate
import com.deepstudent.services.runtime.api.RecoveryPublisher
import com.deepstudent.services.runtime.backend.application.recovery.RecoveryActions
import com.deepstudent.services.runtime.backend.application.recovery.RecoveryCoordinator

/**
 * S2.22 startup composition. It only wires the existing coordinator around [AppRecoveryPolicy].
 * It is intentionally not called from [RuntimeStartupRecovery] or the default application flow
 * yet, so the current S1 assembly keeps running unchanged.
 */
internal object AppRecoveryPlan {
    fun coordinator(
        actions: RecoveryActions,
        policy: AppRecoveryPolicy,
        publisher: RecoveryPublisher,
        logger: Logger,
        monotonicClock: MonotonicClock,
    ): RecoveryCoordinator = RecoveryCoordinator(actions, policy.registrations, publisher, logger, monotonicClock)

    fun forRuntime(
        runtime: MainRuntimeResources,
        deadOwner: DeadOwnerPredicate,
        publisher: RecoveryPublisher,
        logger: Logger = AndroidOperationLogger,
    ): RecoveryCoordinator = coordinator(
        RecoveryActions(
            runtime.ledger,
            runtime.supervisor,
            SystemWallClock,
            runtime.ownerId,
            MainRuntimeResources.LEASE_DURATION,
            deadOwner,
        ),
        AppRecoveryPolicy.forRuntime(runtime),
        publisher,
        logger,
        SystemMonotonicClock,
    )
}
