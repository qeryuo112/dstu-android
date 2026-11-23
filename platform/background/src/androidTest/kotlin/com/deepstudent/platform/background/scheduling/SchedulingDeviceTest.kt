package com.deepstudent.platform.background.scheduling

import android.app.AlarmManager
import android.app.NotificationManager
import android.content.ComponentName
import android.content.Context
import android.content.pm.PackageManager
import android.os.Build
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import androidx.work.WorkInfo
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import java.time.Instant

@RunWith(AndroidJUnit4::class)
class SchedulingDeviceTest {
    private val context: Context = InstrumentationRegistry.getInstrumentation().targetContext

    @Test
    fun reminderChannelIsSeparateAndAdaptersUseIt() {
        val scheduler = RuntimeReminderScheduler(context)
        scheduler.ensureChannel()
        val manager = context.getSystemService(NotificationManager::class.java)
        val channel = manager.getNotificationChannel(RuntimeReminderScheduler.CHANNEL_ID)
        assertNotNull(channel)
        assertNotEquals(NotificationManager.IMPORTANCE_NONE, channel.importance)
        assertNotEquals("dstu.background.tasks", channel.id)
    }

    @Test
    fun alarmPendingIntentsAreExplicitImmutableAndVersioned() {
        val first = ReminderFire(ScheduleId.parse("device-1"), OccurrenceNo.positive(1))
        val same = ReminderFire(ScheduleId.parse("device-1"), OccurrenceNo.positive(1))
        val next = ReminderFire(ScheduleId.parse("device-1"), OccurrenceNo.positive(2))
        val scheduler = RuntimeReminderScheduler(context)
        val firstPending = scheduler.pendingIntent(first)
        val samePending = scheduler.pendingIntent(same)
        val nextPending = scheduler.pendingIntent(next)
        assertTrue(firstPending.isImmutable)
        assertTrue(firstPending.isBroadcast)
        assertEquals(firstPending, samePending)
        assertNotEquals(firstPending, nextPending)

        val dueIntent = ReminderIntents.due(context, first)
        assertEquals(ReminderAlarmReceiver::class.java.name, dueIntent.component?.className)
        assertEquals(ReminderActions.DUE, dueIntent.action)
        assertEquals(first, ReminderIntents.parse(dueIntent, ReminderActions.DUE))
        assertNull(ReminderIntents.parse(dueIntent, "unexpected.action"))
    }

    @Test
    fun pastReminderIsNotScheduledAndFutureAlarmCanBeCancelled() {
        val scheduler = RuntimeReminderScheduler(context)
        val past = ReminderFire(ScheduleId.parse("device-past"), OccurrenceNo.positive(1))
        val pastResult = scheduler.schedule(past, Instant.now().minusSeconds(60))
        assertTrue(pastResult.skippedPast)
        assertFalse(pastResult.scheduled)

        val future = ReminderFire(ScheduleId.parse("device-future"), OccurrenceNo.positive(1))
        val futureResult = scheduler.schedule(future, Instant.now().plusSeconds(3_600))
        assertTrue(futureResult.scheduled)
        assertEquals(futureResult.exact, scheduler.canScheduleExact())
        scheduler.cancel(future)
        scheduler.cancelNotification(future)
    }

    @Test
    fun exactAlarmCapabilityUsesTheSystemFlagWithoutGrantingIt() {
        val alarmManager = context.getSystemService(AlarmManager::class.java)
        val scheduler = RuntimeReminderScheduler(context)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            assertEquals(alarmManager.canScheduleExactAlarms(), scheduler.canScheduleExact())
        } else {
            assertTrue(scheduler.canScheduleExact())
        }
    }

    @Test
    fun rescheduleAndReminderReceiversArePrivateManifestComponents() {
        val pm = context.packageManager
        listOf(
            ReminderAlarmReceiver::class.java,
            SchedulingRescheduleReceiver::class.java,
        ).forEach { type ->
            val info = pm.getReceiverInfo(ComponentName(context, type), 0)
            assertFalse(type.simpleName, info.exported)
        }
        assertEquals(PackageManager.PERMISSION_GRANTED, context.checkSelfPermission("android.permission.RECEIVE_BOOT_COMPLETED"))
    }

    @Test
    fun oneTimeWorkIsVisibleByWorkInfoAndCancellableByUniqueName() = runBlocking {
        val scheduler = RuntimeWorkScheduler(context)
        val scheduleId = ScheduleId.parse("device-work-${System.currentTimeMillis()}")
        val occurrence = OccurrenceNo.positive(1)
        scheduler.enqueueOneTime(scheduleId, occurrence, Instant.now().plusSeconds(3_600))
        val enqueued = withTimeout(10_000) { scheduler.snapshot(scheduleId) }
        val active = enqueued.firstOrNull { it.state == WorkInfo.State.ENQUEUED }
        assertNotNull(active)
        assertTrue(active!!.tags.contains(SchedulingNames.tag(scheduleId)))
        assertFalse(active.workId.toString().isBlank())
        scheduler.enqueueOneTime(scheduleId, occurrence, Instant.now().plusSeconds(7_200))
        assertEquals(active.workId, scheduler.snapshot(scheduleId).single { !it.state.isFinished }.workId)
        scheduler.cancelOneTime(scheduleId, occurrence)
        val afterCancel = withTimeout(10_000) { scheduler.snapshot(scheduleId) }
        assertTrue(afterCancel.isEmpty() || afterCancel.all { it.state == WorkInfo.State.CANCELLED })
    }

    @Test
    fun periodicReplanKeepsOneWorkIdAndItsFirstDueTime() = runBlocking {
        val scheduler = RuntimeWorkScheduler(context)
        val id = ScheduleId.parse("device-period-${System.nanoTime()}")
        val slot = java.time.ZonedDateTime.now().plusHours(1)
        val interval = java.time.Duration.ofMinutes(30)
        try {
            scheduler.enqueuePeriodic(id, interval, slot)
            val first = scheduler.snapshot(id).single { !it.state.isFinished }
            assertTrue(first.nextScheduledAtMillis > Instant.now().plusSeconds(3_500).toEpochMilli())
            repeat(2) { scheduler.enqueuePeriodic(id, interval, slot) }
            val replanned = scheduler.snapshot(id).single { !it.state.isFinished }
            assertEquals(first.workId, replanned.workId)
            assertEquals(first.nextScheduledAtMillis, replanned.nextScheduledAtMillis)
        } finally {
            scheduler.cancelPeriodic(id)
        }
        assertTrue(scheduler.snapshot(id).all { it.state.isFinished })
    }

    @Test
    fun workInputDataCarriesOnlyScheduleIdentity() {
        val request = ScheduledWorkRequest(ScheduleId.parse("device-data"), OccurrenceNo.positive(3))
        val data = RuntimeScheduledWorker.workInput(request)
        assertEquals(
            setOf(SchedulingCodec.KEY_SCHEDULE_ID, SchedulingCodec.KEY_OCCURRENCE),
            data.keyValueMap.keys,
        )
        assertEquals("device-data", data.getString(SchedulingCodec.KEY_SCHEDULE_ID))
        assertEquals(3L, data.getLong(SchedulingCodec.KEY_OCCURRENCE, -1))
    }
}
