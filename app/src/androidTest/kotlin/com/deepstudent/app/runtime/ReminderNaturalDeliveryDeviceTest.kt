package com.deepstudent.app.runtime

import android.app.NotificationManager
import android.os.SystemClock
import android.service.notification.StatusBarNotification
import android.view.WindowManager
import androidx.test.platform.app.InstrumentationRegistry
import com.deepstudent.app.editor.EditorTestHost
import com.deepstudent.app.scheduling.LocalSchedule
import com.deepstudent.app.scheduling.LocalScheduleStore
import com.deepstudent.app.scheduling.SchedulePurpose
import com.deepstudent.app.scheduling.ScheduledDraft
import com.deepstudent.platform.background.scheduling.RuntimeReminderScheduler
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withContext
import org.junit.Test
import java.time.Instant

/** Observes a natural AlarmManager delivery through the production scheduler and receiver. */
class ReminderNaturalDeliveryDeviceTest {
    @Test(timeout = 100_000)
    fun allowedNotificationNaturallyDeliversOnce() = runBlocking {
        val fixture = ScheduledDeviceFixture()
        val args = InstrumentationRegistry.getArguments()
        val exact = fixture.schedules.reminders.canScheduleExact()
        args.getString("s225NaturalExact")?.let { expected ->
            check(exact == expected.toBooleanStrict()) { "Expected exact-alarm access $expected, observed $exact" }
        }
        val manager = fixture.app.getSystemService(NotificationManager::class.java)
        check(manager.areNotificationsEnabled()) { "The isolated package needs notification access" }

        val host = EditorTestHost()
        try {
            host.start()
            fixture.instrumentation.runOnMainSync {
                host.activity.window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
            }
            val due = Instant.ofEpochMilli(System.currentTimeMillis() + 5_000)
            val schedule = fixture.schedules.create(
                ScheduledDraft("S225 natural delivery", "Synthetic reminder", SchedulePurpose.REMINDER),
                due,
            )
            try {
                val result = observeDelivery(manager, LocalScheduleStore(fixture.app), schedule)
                fixture.evidence(
                    "S225_NATURAL exact=$exact planned_at_ms=${due.toEpochMilli()} " +
                        "receipt_observed_at_ms=${result.receiptAt} notification_posted_at_ms=${result.postedAt} " +
                        "delay_ms=${result.postedAt - due.toEpochMilli()} duplicate_observation_ms=2000 duplicates=0",
                )
            } finally {
                withContext(NonCancellable) { fixture.schedules.cancel(schedule.id) }
            }
        } finally {
            host.close()
        }
    }

    private suspend fun observeDelivery(manager: NotificationManager, store: LocalScheduleStore, schedule: LocalSchedule): Delivery {
        val tag = "reminder:${schedule.id.value}:${schedule.occurrence.value}"
        val deadline = SystemClock.elapsedRealtime() + 60_000
        var receiptAt: Long? = null
        while (SystemClock.elapsedRealtime() < deadline) {
            if (receiptAt == null && store.find(schedule.id)?.lastHandled == schedule.occurrence.value) {
                receiptAt = System.currentTimeMillis()
            }
            val posted = manager.postedReminder(tag)
            if (posted != null && receiptAt != null) {
                check(posted.postTime >= schedule.due.toEpochMilli()) { "Reminder posted before it was due" }
                val intent = posted.notification.contentIntent
                check(intent != null && intent.isActivity && intent.isImmutable) { "Unexpected notification action" }
                verifySinglePost(manager, tag, posted.postTime)
                return Delivery(receiptAt, posted.postTime)
            }
            delay(50)
        }
        error(
            "Natural delivery was not observed within 60000ms (due=${schedule.due}, receipt=$receiptAt, posted=${manager.postedReminder(
                tag,
            )?.postTime})",
        )
    }

    private suspend fun verifySinglePost(manager: NotificationManager, tag: String, postTime: Long) {
        val deadline = SystemClock.elapsedRealtime() + 2_000
        while (SystemClock.elapsedRealtime() < deadline) {
            check(manager.postedReminder(tag)?.postTime == postTime) { "Reminder was removed or posted again" }
            delay(50)
        }
    }

    private fun NotificationManager.postedReminder(tag: String): StatusBarNotification? =
        activeNotifications.singleOrNull { it.tag == tag && it.id == RuntimeReminderScheduler.NOTIFICATION_ID }

    private data class Delivery(val receiptAt: Long, val postedAt: Long)
}
