package com.deepstudent.platform.background

import com.deepstudent.core.common.id.TaskId
import com.deepstudent.core.common.logging.Logger
import com.deepstudent.core.common.logging.NoopLogger
import com.deepstudent.services.runtime.api.RuntimeCancelResult
import com.deepstudent.services.runtime.api.RuntimeSnapshot
import com.deepstudent.services.runtime.api.RuntimeTaskCanceller
import com.deepstudent.services.runtime.api.RuntimeTaskCatalog
import com.deepstudent.services.runtime.api.RuntimeTaskObserver
import com.deepstudent.services.runtime.api.RuntimeTaskQuery
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.emptyFlow

internal class TestForegroundHost(
    override val foregroundDuties: DutyRegistry = DutyRegistry(),
    private val query: RuntimeTaskQuery,
    private val eligible: (RuntimeSnapshot, ForegroundPurpose) -> Boolean = { _, _ -> true },
    private val continuousEligible: (ContinuousDuty) -> Boolean = { true },
    private val stickyOptIn: Boolean = false,
    private val coldRecoverable: (ContinuousDuty) -> Boolean = { false },
    private val onReconcile: suspend () -> Unit = {},
) : RuntimeForegroundHost {
    private val observer = RuntimeTaskObserver { _, _ -> emptyFlow() }
    private val canceller = object : RuntimeTaskCanceller {
        override suspend fun cancel(taskId: TaskId): RuntimeCancelResult = RuntimeCancelResult.StaleGeneration(taskId)
        override suspend fun cancelIfGeneration(taskId: TaskId, generation: Long): RuntimeCancelResult =
            RuntimeCancelResult.Requested(taskId)
    }
    private val catalog = object : RuntimeTaskCatalog {
        override suspend fun activeSnapshots(afterTaskId: String?, limit: Int): List<RuntimeSnapshot> = emptyList()
    }

    override val foregroundTasks = ForegroundTaskPorts(query, catalog, observer, canceller)
    override val foregroundScope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    override val foregroundLogger: Logger = NoopLogger

    override fun isDataSyncForegroundTask(snapshot: RuntimeSnapshot): Boolean = eligible(snapshot, ForegroundPurpose.DATA_SYNC)

    override fun isForegroundPurposeEligible(snapshot: RuntimeSnapshot, purpose: ForegroundPurpose): Boolean = eligible(snapshot, purpose)

    override fun isBackgroundStickyOptedIn(): Boolean = stickyOptIn

    override fun isColdRecoverableContinuousDuty(duty: ContinuousDuty): Boolean = coldRecoverable(duty)

    override fun isContinuousDutyEligible(duty: ContinuousDuty): Boolean = continuousEligible(duty)

    override suspend fun reconcileBackgroundResidency() = onReconcile()

    override suspend fun checkpointForegroundTask(duty: ForegroundDuty) = Unit
}
