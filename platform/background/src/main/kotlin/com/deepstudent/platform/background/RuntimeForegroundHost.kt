package com.deepstudent.platform.background

import com.deepstudent.core.common.logging.Logger
import com.deepstudent.services.runtime.api.RuntimeTaskCanceller
import com.deepstudent.services.runtime.api.RuntimeTaskCatalog
import com.deepstudent.services.runtime.api.RuntimeTaskObserver
import com.deepstudent.services.runtime.api.RuntimeTaskQuery
import com.deepstudent.services.runtime.api.RuntimeSnapshot
import kotlinx.coroutines.CoroutineScope

/** Implemented by the main-process Application and wired to the real runtime. */
interface RuntimeForegroundHost {
    val foregroundDuties: DutyRegistry
    val foregroundTasks: ForegroundTaskPorts
    val foregroundScope: CoroutineScope
    val foregroundLogger: Logger

    /** True only for task kinds performing eligible data transfer or synchronization. */
    fun isDataSyncForegroundTask(snapshot: RuntimeSnapshot): Boolean

    /** Return only after the task owner commits its checkpoint or reports failure. */
    suspend fun checkpointForegroundTask(duty: ForegroundDuty)
}

data class ForegroundTaskPorts(
    val query: RuntimeTaskQuery,
    val catalog: RuntimeTaskCatalog,
    val observer: RuntimeTaskObserver,
    val canceller: RuntimeTaskCanceller,
)
