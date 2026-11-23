package com.deepstudent.app.runtime

import android.Manifest
import android.app.NotificationManager
import android.os.Build
import com.deepstudent.app.scheduling.LocalSchedule
import com.deepstudent.app.scheduling.LocalScheduleStore
import com.deepstudent.app.scheduling.SchedulePurpose
import com.deepstudent.app.scheduling.ScheduledDraft
import com.deepstudent.platform.background.scheduling.ReminderFire
import com.deepstudent.platform.background.scheduling.ReplanReason
import com.deepstudent.platform.background.scheduling.RuntimeReminderScheduler
import com.deepstudent.platform.background.scheduling.ScheduleId
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.Instant

class ReminderReplanDeviceTest {
    @Test(timeout = 60_000)
    fun bootPathRestoresAlarmAndRepeatedDeliveryCannotNotifyAgain() = runBlocking {
        val f = ScheduledDeviceFixture()
        if (Build.VERSION.SDK_INT >=
            33
        ) {
            f.instrumentation.uiAutomation.grantRuntimePermission(f.app.packageName, Manifest.permission.POST_NOTIFICATIONS)
        }
        val row = reminderRow("s225-reminder", Instant.now().plusSeconds(3))
        val fire = ReminderFire(row.id, row.occurrence)
        val scheduler = f.schedules.reminders
        val manager = f.app.getSystemService(NotificationManager::class.java)
        val tag = "reminder:${row.id.value}:${row.occurrence.value}"
        f.schedules.register(row)
        try {
            scheduler.cancel(fire)
            f.app.replanSchedules(ReplanReason.BOOT_COMPLETED)
            // A pending intent registered again after a simulated loss uses the real private receiver.
            val pending = scheduler.pendingIntent(fire)
            delay(3_400)
            pending.send()
            val store = LocalScheduleStore(f.app)
            awaitRuntime { store.find(row.id)?.lastHandled == fire.occurrence.value }
            awaitRuntime { manager.activeNotifications.any { it.tag == tag } }
            val posted = manager.activeNotifications.single { it.tag == tag }.notification
            assertTrue(posted.contentIntent.isActivity)
            assertTrue(posted.contentIntent.isImmutable)
            scheduler.cancelNotification(fire)
            pending.send()
            f.app.replanSchedules(ReplanReason.TIMEZONE_CHANGED)
            delay(250)
            assertFalse(manager.activeNotifications.any { it.tag == tag })
            assertNotNull(store.find(row.id))
            assertEquals(fire.occurrence.value, store.find(row.id)?.lastHandled)
            f.evidence("S225_REMINDER replan=true acknowledged=true duplicates=0 exact=${scheduler.canScheduleExact()}")
        } finally {
            f.schedules.cancel(row.id)
        }
    }

    @Test(timeout = 45_000)
    fun cancelledAndStaleOccurrenceDoNotDispatch() = runBlocking {
        val f = ScheduledDeviceFixture()
        val row = reminderRow("s225-stale", Instant.now().plusSeconds(30))
        f.schedules.register(row)
        val calls = java.util.concurrent.atomic.AtomicInteger()
        val stale = ReminderFire(row.id, com.deepstudent.platform.background.scheduling.OccurrenceNo(row.occurrence.value - 1))
        f.app.dispatchReminder(stale) {
            calls.incrementAndGet()
            true
        }
        f.schedules.cancel(row.id)
        f.app.replanSchedules(ReplanReason.BOOT_COMPLETED)
        f.app.dispatchReminder(ReminderFire(row.id, row.occurrence)) {
            calls.incrementAndGet()
            true
        }
        assertEquals(0, calls.get())
        assertTrue(LocalScheduleStore(f.app).find(row.id) == null)
    }

    private fun reminderRow(id: String, due: Instant) = LocalSchedule(
        ScheduleId.parse(id),
        ScheduledDraft("S225 reminder", "Synthetic reminder", SchedulePurpose.REMINDER),
        due,
    )
}
