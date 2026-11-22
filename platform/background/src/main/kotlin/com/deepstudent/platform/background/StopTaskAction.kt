package com.deepstudent.platform.background

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.net.Uri
import com.deepstudent.core.common.id.TaskId
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch

internal object StopTaskAction {
    const val ACTION = "com.deepstudent.platform.background.STOP_TASK"
    private const val SCHEME = "dstu-task"
    private const val AUTHORITY = "foreground"

    fun intent(context: Context, duty: ForegroundDuty): Intent =
        Intent(context, StopTaskReceiver::class.java).setAction(ACTION).setData(
            Uri.Builder().scheme(SCHEME).authority(AUTHORITY)
                .appendPath(duty.taskId.value).appendPath(duty.generation.toString()).build(),
        )

    fun parse(intent: Intent): Pair<TaskId, Long>? {
        val data = intent.data ?: return null
        if (intent.action != ACTION || data.scheme != SCHEME || data.authority != AUTHORITY) return null
        if (data.pathSegments.size != 2) return null
        val generation = data.pathSegments[1].toLongOrNull()?.takeIf { it > 0 } ?: return null
        val taskId = try {
            TaskId.parse(data.pathSegments[0])
        } catch (_: IllegalArgumentException) {
            return null
        } catch (_: com.deepstudent.core.common.error.DstuException) {
            return null
        }
        return taskId to generation
    }
}

class StopTaskReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        val identity = StopTaskAction.parse(intent) ?: return
        val host = context.applicationContext as? RuntimeForegroundHost ?: return
        val duty = host.foregroundDuties.duties.value.firstOrNull {
            it.taskId == identity.first && it.generation == identity.second
        } ?: return
        val pending = goAsync()
        host.foregroundScope.launch(Dispatchers.IO) {
            try {
                cancelIfCurrent(host, duty)
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (_: Exception) {
                ForegroundLog(host.foregroundLogger).event("background.stop", duty, "failed")
            }
        }.invokeOnCompletion { pending.finish() }
    }

    private suspend fun cancelIfCurrent(host: RuntimeForegroundHost, duty: ForegroundDuty) {
        if (duty !in host.foregroundDuties.duties.value) return
        DutyCancellation.cancel(host, duty, ForegroundLog(host.foregroundLogger), "background.stop")
    }
}
