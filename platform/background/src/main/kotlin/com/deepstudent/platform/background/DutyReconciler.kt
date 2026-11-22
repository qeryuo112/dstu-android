package com.deepstudent.platform.background

import com.deepstudent.services.runtime.api.RuntimeTaskCatalog
import com.deepstudent.services.runtime.api.RuntimeTaskQuery

/** A ledger entry alone never grants foreground eligibility or restarts work. */
internal class DutyReconciler(
    private val registry: DutyRegistry,
    private val query: RuntimeTaskQuery,
    private val catalog: RuntimeTaskCatalog,
) {
    suspend fun reconcile(): Boolean {
        val hasActiveTasks = catalog.activeSnapshots(afterTaskId = null, limit = 1).isNotEmpty()
        for (duty in registry.duties.value) {
            val snapshot = query.snapshot(duty.taskId)
            if (snapshot == null || snapshot.state.terminal || snapshot.ownership.generation != duty.generation) {
                registry.retire(duty.taskId, duty.generation)
            }
        }
        return hasActiveTasks
    }
}
