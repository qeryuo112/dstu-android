package com.deepstudent.platform.background

import com.deepstudent.services.runtime.api.RuntimeTaskQuery

/**
 * Same-process re-verification of live registry entries. It never scans the task ledger and a
 * ledger/projection row alone never grants eligibility or restarts work.
 */
internal class DutyReconciler(
    private val registry: DutyRegistry,
    private val query: RuntimeTaskQuery,
    private val host: RuntimeForegroundHost,
) {
    suspend fun reconcile(): List<ForegroundSlot> {
        for (duty in registry.duties.value) {
            val snapshot = query.snapshot(duty.taskId)
            if (snapshot == null ||
                snapshot.state.terminal ||
                snapshot.ownership.generation != duty.generation ||
                !host.isForegroundPurposeEligible(snapshot, duty.purpose)
            ) {
                registry.retire(duty.taskId, duty.generation)
            }
        }
        for (duty in registry.continuousDuties.value) {
            if (!host.isContinuousDutyEligible(duty)) registry.retireContinuous(duty.ownerId)
        }
        return registry.slots.value
    }
}
