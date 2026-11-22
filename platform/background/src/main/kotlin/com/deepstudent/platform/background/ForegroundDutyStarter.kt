package com.deepstudent.platform.background

import android.content.Context
import android.content.Intent
import android.os.Build
import android.os.Looper
import com.deepstudent.core.common.id.TaskId
import com.deepstudent.services.runtime.api.RuntimeSnapshot
import java.io.Closeable
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.withContext

sealed interface ForegroundStartResult {
    data class Started(val reference: Closeable) : ForegroundStartResult
    data object MissingOrTerminal : ForegroundStartResult
    data object NotificationsUnavailable : ForegroundStartResult
    data object UnsupportedPurpose : ForegroundStartResult
    data object Rejected : ForegroundStartResult
}

/** Called from a system-allowed foreground start context by the task owner. */
class ForegroundDutyStarter(private val context: Context, private val host: RuntimeForegroundHost) {
    suspend fun startDataSync(taskId: TaskId): ForegroundStartResult {
        val snapshot = host.foregroundTasks.query.snapshot(taskId)
        if (snapshot == null || snapshot.state.terminal) return ForegroundStartResult.MissingOrTerminal
        if (!host.isDataSyncForegroundTask(snapshot)) return ForegroundStartResult.UnsupportedPurpose
        return withContext(kotlinx.coroutines.Dispatchers.Main.immediate) { start(snapshot) }
    }

    private fun start(snapshot: RuntimeSnapshot): ForegroundStartResult {
        check(Looper.myLooper() == Looper.getMainLooper())
        check(context.applicationContext === host) { "foreground host must be the Application" }
        val factory = TaskNotificationFactory(context)
        factory.createChannel()
        if (!factory.available()) return ForegroundStartResult.NotificationsUnavailable
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU &&
            context.checkSelfPermission(android.Manifest.permission.POST_NOTIFICATIONS) != android.content.pm.PackageManager.PERMISSION_GRANTED
        ) return ForegroundStartResult.NotificationsUnavailable
        val duty = ForegroundDuty(snapshot.taskId, snapshot.ownership.generation, snapshot.correlationId, ForegroundPurpose.DATA_SYNC)
        val reference = host.foregroundDuties.acquire(duty)
        return try {
            val intent = Intent(context, RuntimeForegroundService::class.java).setAction(RuntimeForegroundService.ACTION_START)
            context.startForegroundService(intent)
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
    }
}
