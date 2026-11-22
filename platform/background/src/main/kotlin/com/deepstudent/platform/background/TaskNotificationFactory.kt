package com.deepstudent.platform.background

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.os.Build
import com.deepstudent.services.runtime.api.RuntimeState

internal class TaskNotificationFactory(private val context: Context) {
    private val manager = context.getSystemService(NotificationManager::class.java)

    fun createChannel() {
        val channel = NotificationChannel(CHANNEL_ID, context.getString(R.string.background_channel_name), NotificationManager.IMPORTANCE_LOW)
        channel.description = context.getString(R.string.background_channel_description)
        manager.createNotificationChannel(channel)
    }

    fun available(): Boolean {
        if (!manager.areNotificationsEnabled()) return false
        val channel = manager.getNotificationChannel(CHANNEL_ID)
        return channel == null || channel.importance != NotificationManager.IMPORTANCE_NONE
    }

    fun summary(duties: List<ForegroundDuty>): Notification {
        val text = if (duties.isEmpty()) {
            context.getString(R.string.background_notification_starting)
        } else {
            context.getString(R.string.background_notification_count, duties.size)
        }
        return base().setContentText(text).setGroup(GROUP).setGroupSummary(true).build()
    }

    fun task(duty: ForegroundDuty, state: RuntimeState): Notification {
        val stop = PendingIntent.getBroadcast(
            context, 0, StopTaskAction.intent(context, duty),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )
        return base()
            .setContentText(context.getString(R.string.background_notification_task, duty.taskId.value))
            .setSubText(context.getString(state.label()))
            .setProgress(0, 0, true)
            .setGroup(GROUP)
            .addAction(Notification.Action.Builder(null, context.getString(R.string.background_notification_stop), stop).build())
            .build()
    }

    fun show(duty: ForegroundDuty, state: RuntimeState) =
        manager.notify(duty.taskId.value, TASK_NOTIFICATION_ID, task(duty, state))

    fun remove(duty: ForegroundDuty) = manager.cancel(duty.taskId.value, TASK_NOTIFICATION_ID)

    private fun base(): Notification.Builder {
        val builder = Notification.Builder(context, CHANNEL_ID)
            .setSmallIcon(android.R.drawable.stat_sys_upload)
            .setContentTitle(context.getString(R.string.background_notification_title))
            .setOngoing(true)
            .setOnlyAlertOnce(true)
            .setCategory(Notification.CATEGORY_PROGRESS)
        val launch = context.packageManager.getLaunchIntentForPackage(context.packageName)
        if (launch?.component != null) {
            builder.setContentIntent(PendingIntent.getActivity(context, 0, launch, PendingIntent.FLAG_IMMUTABLE))
        }
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            builder.setForegroundServiceBehavior(Notification.FOREGROUND_SERVICE_IMMEDIATE)
        }
        return builder
    }

    companion object {
        const val SUMMARY_ID = 7410
        private const val TASK_NOTIFICATION_ID = 7411
        private const val CHANNEL_ID = "dstu.background.tasks"
        private const val GROUP = "com.deepstudent.background.tasks"
    }
}

internal fun RuntimeState.label(): Int = when (this) {
    RuntimeState.CREATED -> R.string.background_state_created
    RuntimeState.RUNNING -> R.string.background_state_running
    RuntimeState.WAITING_APPROVAL -> R.string.background_state_waiting
    RuntimeState.PAUSED -> R.string.background_state_paused
    RuntimeState.COMPLETED -> R.string.background_state_completed
    RuntimeState.FAILED -> R.string.background_state_failed
    RuntimeState.CANCELLED -> R.string.background_state_cancelled
    RuntimeState.INTERRUPTED -> R.string.background_state_interrupted
}
