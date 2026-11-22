package com.deepstudent.services.runtime.backend.application.recovery

import com.deepstudent.core.common.time.WallClock
import com.deepstudent.services.runtime.api.DeadOwnerPredicate
import com.deepstudent.services.runtime.backend.TaskLedger
import com.deepstudent.services.runtime.backend.TaskSupervisor
import java.time.Duration

/** Configuration for the existing ledger and supervisor, not another task store. */
class RecoveryActions(
    val ledger: TaskLedger,
    val supervisor: TaskSupervisor,
    val clock: WallClock,
    val ownerId: String,
    val leaseDuration: Duration,
    val deadOwner: DeadOwnerPredicate = DeadOwnerPredicate { false },
) {
    init {
        require(ownerId.isNotBlank()) { "invalid recovery owner" }
        require(!leaseDuration.isZero && !leaseDuration.isNegative) { "invalid recovery lease" }
    }
}
