package com.deepstudent.platform.background

import android.content.Context
import android.content.Intent
import android.os.Build
import android.os.Looper
import com.deepstudent.core.common.id.TaskId
import com.deepstudent.services.runtime.api.RuntimeSnapshot
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.Closeable

sealed interface ForegroundStartResult {
    data class Started(val reference: Closeable) : ForegroundStartResult
    data object MissingOrTerminal : ForegroundStartResult
    data object NotificationsUnavailable : ForegroundStartResult
    data object UnsupportedPurpose : ForegroundStartResult
    data object Rejected : ForegroundStartResult
}

/**
 * Called from a system-allowed foreground start context by the work owner.
 *
 * Task duties carry a real ledger [TaskId]; continuous duties carry only an opaque owner id
 * (a terminal session id in S2.24). Start from a visible caller; the service may find that the
 * duty has already ended before delivery. A regular start does not promise a fake promotion.
 */
class ForegroundDutyStarter(private val context: Context, private val host: RuntimeForegroundHost) {
    suspend fun startDataSync(taskId: TaskId): ForegroundStartResult = startTask(taskId, ForegroundPurpose.DATA_SYNC)

    /** Keeps a real terminal session owner alive; never fabricates a [TaskId]. */
    suspend fun startTerminal(ownerId: String): ForegroundStartResult {
        if (!ownerId.matches(OPAQUE_OWNER)) return ForegroundStartResult.Rejected
        return withContext(Dispatchers.Main.immediate) {
            startContinuous(ContinuousDuty(ownerId, ForegroundPurpose.TERMINAL))
        }
    }

    /** Ends every reference for this terminal owner without cancelling any task. */
    fun retireTerminal(ownerId: String) {
        host.foregroundDuties.retireContinuous(ownerId)
    }

    private suspend fun startTask(taskId: TaskId, purpose: ForegroundPurpose): ForegroundStartResult {
        val snapshot = host.foregroundTasks.query.snapshot(taskId)
        if (snapshot == null || snapshot.state.terminal) return ForegroundStartResult.MissingOrTerminal
        if (!host.isForegroundPurposeEligible(snapshot, purpose)) return ForegroundStartResult.UnsupportedPurpose
        return withContext(Dispatchers.Main.immediate) { startTaskOnMain(snapshot, purpose) }
    }

    private fun startTaskOnMain(snapshot: RuntimeSnapshot, purpose: ForegroundPurpose): ForegroundStartResult {
        checkMain()
        if (!notificationsReady()) return ForegroundStartResult.NotificationsUnavailable
        val duty = ForegroundDuty(snapshot.taskId, snapshot.ownership.generation, snapshot.correlationId, purpose)
        val reference = host.foregroundDuties.acquire(duty)
        return launch(reference)
    }

    private fun startContinuous(duty: ContinuousDuty): ForegroundStartResult {
        checkMain()
        if (!notificationsReady()) return ForegroundStartResult.NotificationsUnavailable
        val reference = try {
            host.foregroundDuties.acquireContinuous(duty)
        } catch (_: IllegalArgumentException) {
            return ForegroundStartResult.Rejected
        }
        if (!host.isContinuousDutyEligible(duty)) {
            reference.close()
            return ForegroundStartResult.UnsupportedPurpose
        }
        return launch(reference)
    }

    private fun notificationsReady(): Boolean {
        check(context.applicationContext === host) { "foreground host must be the Application" }
        val factory = TaskNotificationFactory(context)
        factory.createChannel()
        if (!factory.available()) return false
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU &&
            context.checkSelfPermission(android.Manifest.permission.POST_NOTIFICATIONS) !=
            android.content.pm.PackageManager.PERMISSION_GRANTED
        ) {
            return false
        }
        return true
    }

    private fun checkMain() {
        check(Looper.myLooper() == Looper.getMainLooper()) { "foreground start must run on MAIN" }
    }

    private fun launch(reference: Closeable): ForegroundStartResult = try {
        val intent = Intent(context, RuntimeForegroundService::class.java).setAction(RuntimeForegroundService.ACTION_START)
        checkNotNull(context.startService(intent)) { "foreground adapter unavailable" }
        ForegroundStartResult.Started(reference)
    } catch (cancelled: CancellationException) {
        reference.close()
        throw cancelled
    } catch (_: SecurityException) {
        reference.close()
        ForegroundStartResult.Rejected
    } catch (_: IllegalStateException) {
        reference.close()
        ForegroundStartResult.Rejected
    }

    private companion object {
        val OPAQUE_OWNER = Regex("[A-Za-z0-9][A-Za-z0-9_.:-]{0,127}")
    }
}
