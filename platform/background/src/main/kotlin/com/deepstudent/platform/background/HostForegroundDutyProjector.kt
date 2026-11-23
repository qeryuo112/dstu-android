package com.deepstudent.platform.background

import com.deepstudent.core.common.logging.Logger
import com.deepstudent.services.runtime.api.RuntimeProjectionResult
import com.deepstudent.services.runtime.api.RuntimeRecordKey
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import java.util.concurrent.ConcurrentHashMap

/**
 * Writes/releases one persistent duty projection per registry reference.
 *
 * The registry has no Android or App dependency: the App constructs
 * `DutyRegistry(HostForegroundDutyProjector({ foregroundScope }, this, logger))`.
 * register and release for one key are serialized (release joins register), so a late projection
 * write can never recreate a released row.
 */
class HostForegroundDutyProjector(
    private val scope: () -> CoroutineScope,
    private val host: RuntimeForegroundHost,
    private val logger: Logger,
) : ForegroundDutyProjector {
    private val pending = ConcurrentHashMap<RuntimeRecordKey, Job>()

    override fun acquired(duty: ForegroundDuty, recordKey: RuntimeRecordKey) {
        val job = runCatching {
            scope().launch(Dispatchers.IO) {
                write("register", duty, recordKey) { host.registerForegroundDuty(duty, recordKey) }
            }
        }.getOrNull() ?: return
        pending[recordKey] = job
        job.invokeOnCompletion { cause ->
            if (cause != null) pending.remove(recordKey, job)
        }
    }

    override fun released(duty: ForegroundDuty, recordKey: RuntimeRecordKey) {
        val registered = pending.remove(recordKey)
        runCatching {
            scope().launch(Dispatchers.IO) {
                registered?.join()
                write("release", duty, recordKey) { host.releaseForegroundDuty(duty, recordKey) }
            }
        }.onFailure { log(duty, "release_launch_failed") }
    }

    private suspend fun write(
        operation: String,
        duty: ForegroundDuty,
        recordKey: RuntimeRecordKey,
        call: suspend () -> RuntimeProjectionResult,
    ) {
        val result = try {
            call()
        } catch (cancelled: kotlinx.coroutines.CancellationException) {
            throw cancelled
        } catch (_: Exception) {
            log(duty, "${operation}_failed")
            return
        }
        log(duty, "${operation}_${result.name.lowercase()}")
    }

    private fun log(duty: ForegroundDuty, state: String) {
        runCatching { ForegroundLog(logger).event("background.projection", duty, state) }
    }
}
