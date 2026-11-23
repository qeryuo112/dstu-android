package com.deepstudent.platform.background.scheduling

import android.Manifest
import android.app.AlarmManager
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.pm.PackageManager
import android.os.Build
import com.deepstudent.platform.background.R
import java.time.Instant

data class ReminderAlarmResult(val exact: Boolean, val scheduled: Boolean, val skippedPast: Boolean)

/**
 * Alarm + notification adapter for reminders. It owns only the platform delivery mechanism:
 * a separate channel, a stable immutable PendingIntent per schedule+occurrence, exact alarms when
 * the user already allows them and inexact alarms otherwise. It never grants alarm access and never
 * stores due/enabled/idempotence state.
 */
class RuntimeReminderScheduler(context: Context) {
    private val appContext = context.applicationContext
    private val alarmManager: AlarmManager = appContext.getSystemService(AlarmManager::class.java)
    private val notificationManager: NotificationManager =
        appContext.getSystemService(NotificationManager::class.java)

    fun ensureChannel() {
        if (notificationManager.getNotificationChannel(CHANNEL_ID) != null) return
        val channel = NotificationChannel(
            CHANNEL_ID,
            appContext.getString(R.string.scheduling_reminder_channel_name),
            NotificationManager.IMPORTANCE_DEFAULT,
        )
        channel.description = appContext.getString(R.string.scheduling_reminder_channel_description)
        notificationManager.createNotificationChannel(channel)
    }

    /** No permission prompt is issued here; a false result simply selects an inexact alarm. */
    fun canScheduleExact(): Boolean = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) alarmManager.canScheduleExactAlarms() else true

    fun pendingIntent(fire: ReminderFire): PendingIntent = PendingIntent.getBroadcast(
        appContext,
        0,
        ReminderIntents.due(appContext, fire),
        PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
    )

    /** Past occurrences are not scheduled, so an expired reminder cannot be replayed by the platform. */
    fun schedule(fire: ReminderFire, at: Instant): ReminderAlarmResult {
        val triggerAt = at.toEpochMilli()
        if (triggerAt <= System.currentTimeMillis()) {
            return ReminderAlarmResult(exact = false, scheduled = false, skippedPast = true)
        }
        val pending = pendingIntent(fire)
        if (canScheduleExact()) {
            try {
                alarmManager.setExactAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, triggerAt, pending)
                return ReminderAlarmResult(exact = true, scheduled = true, skippedPast = false)
            } catch (_: SecurityException) {
                // Access changed between the capability check and the call; fall back to inexact.
            }
        }
        return try {
            alarmManager.setAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, triggerAt, pending)
            ReminderAlarmResult(exact = false, scheduled = true, skippedPast = false)
        } catch (_: SecurityException) {
            ReminderAlarmResult(exact = false, scheduled = false, skippedPast = false)
        }
    }

    fun cancel(fire: ReminderFire) {
        val pending = pendingIntent(fire)
        alarmManager.cancel(pending)
        pending.cancel()
    }

    /**
     * Returns true only after the notification was actually handed to the system with an enabled
     * channel and notification permission. A missing occurrence tag means no acknowledgement.
     */
    fun show(fire: ReminderFire, dispatch: ReminderDispatch): Boolean {
        ensureChannel()
        if (!notificationsAvailable()) return false
        val contentIntent = contentIntent(fire)
        val notification = Notification.Builder(appContext, CHANNEL_ID)
            .setSmallIcon(android.R.drawable.ic_popup_reminder)
            .setContentTitle(dispatch.title)
            .setContentText(dispatch.body)
            .setStyle(Notification.BigTextStyle().bigText(dispatch.body))
            .setCategory(Notification.CATEGORY_REMINDER)
            .setContentIntent(contentIntent)
            .setAutoCancel(true)
            .setOnlyAlertOnce(true)
            .build()
        return try {
            notificationManager.notify(notificationTag(fire), NOTIFICATION_ID, notification)
            true
        } catch (_: SecurityException) {
            false
        }
    }

    private fun contentIntent(fire: ReminderFire): PendingIntent? {
        val host = appContext as? RuntimeSchedulingHost ?: return null
        val target = host.reminderActivityIntent(fire) ?: return null
        check(target.component?.packageName == appContext.packageName) { "reminder must open an explicit app Activity" }
        target.data = android.net.Uri.parse(ReminderIdentity.encode(fire))
        return PendingIntent.getActivity(appContext, 0, target, PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE)
    }

    fun cancelNotification(fire: ReminderFire) {
        notificationManager.cancel(notificationTag(fire), NOTIFICATION_ID)
    }

    private fun notificationsAvailable(): Boolean {
        if (!notificationManager.areNotificationsEnabled()) return false
        val channel = notificationManager.getNotificationChannel(CHANNEL_ID)
        if (channel == null || channel.importance == NotificationManager.IMPORTANCE_NONE) return false
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU &&
            appContext.checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) !=
            PackageManager.PERMISSION_GRANTED
        ) {
            return false
        }
        return true
    }

    private fun notificationTag(fire: ReminderFire): String = "reminder:${fire.scheduleId.value}:${fire.occurrence.value}"

    companion object {
        const val CHANNEL_ID = "dstu.reminders"
        const val NOTIFICATION_ID = 7420
    }
}
