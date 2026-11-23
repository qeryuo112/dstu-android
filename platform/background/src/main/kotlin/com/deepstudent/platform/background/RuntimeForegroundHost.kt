package com.deepstudent.platform.background

import com.deepstudent.core.common.logging.Logger
import com.deepstudent.services.runtime.api.RuntimeProjectionResult
import com.deepstudent.services.runtime.api.RuntimeRecordKey
import com.deepstudent.services.runtime.api.RuntimeSnapshot
import com.deepstudent.services.runtime.api.RuntimeTaskCanceller
import com.deepstudent.services.runtime.api.RuntimeTaskCatalog
import com.deepstudent.services.runtime.api.RuntimeTaskObserver
import com.deepstudent.services.runtime.api.RuntimeTaskQuery
import kotlinx.coroutines.CoroutineScope

/**
 * Implemented by the main-process Application and wired to the real runtime.
 *
 * Methods after [isDataSyncForegroundTask] have defaults so older hosts and tests keep compiling.
 * Defaults never grant a purpose, never stick and never persist a projection.
 */
interface RuntimeForegroundHost {
    val foregroundDuties: DutyRegistry
    val foregroundTasks: ForegroundTaskPorts
    val foregroundScope: CoroutineScope
    val foregroundLogger: Logger

    /** True only for task kinds performing eligible data transfer or synchronization. */
    fun isDataSyncForegroundTask(snapshot: RuntimeSnapshot): Boolean

    /** Only real task kinds may own [purpose]; default keeps the previous data-sync contract. */
    fun isForegroundPurposeEligible(snapshot: RuntimeSnapshot, purpose: ForegroundPurpose): Boolean =
        purpose == ForegroundPurpose.DATA_SYNC && isDataSyncForegroundTask(snapshot)

    /** True only when the user explicitly opted into background residency. Default: never sticky. */
    fun isBackgroundStickyOptedIn(): Boolean = false

    /** A continuous owner is cold-recoverable only with an explicit recovery protocol. Default false. */
    fun isColdRecoverableContinuousDuty(duty: ContinuousDuty): Boolean = false

    /** Live eligibility of a process-local owner (e.g. the terminal session still exists). */
    fun isContinuousDutyEligible(duty: ContinuousDuty): Boolean = true

    /**
     * Called for a null/empty rebuild intent before anything is promoted. The host may re-establish
     * real duties and acquire their references; it must never fabricate a task or grant new work.
     */
    suspend fun reconcileBackgroundResidency() {}

    /**
     * Persists one registry reference. [recordKey] is unique per reference (`fgs.<uuid>`);
     * [releaseForegroundDuty] receives the same pair. Failures are advisory and must not stop work.
     */
    suspend fun registerForegroundDuty(duty: ForegroundDuty, recordKey: RuntimeRecordKey): RuntimeProjectionResult =
        RuntimeProjectionResult.UNCHANGED

    /** Releases the same reference key. */
    suspend fun releaseForegroundDuty(duty: ForegroundDuty, recordKey: RuntimeRecordKey): RuntimeProjectionResult =
        RuntimeProjectionResult.UNCHANGED

    /**
     * User asked to close background residency. The host disables its own opt-in/keep-alive state
     * here; continuous purposes are withdrawn by the platform afterwards. Imports are not cancelled.
     */
    suspend fun disableBackground() {}

    fun onForegroundServiceDestroyed() {}

    /** Return only after the task owner commits its checkpoint or reports failure. */
    suspend fun checkpointForegroundTask(duty: ForegroundDuty)
}

data class ForegroundTaskPorts(
    val query: RuntimeTaskQuery,
    val catalog: RuntimeTaskCatalog,
    val observer: RuntimeTaskObserver,
    val canceller: RuntimeTaskCanceller,
)
