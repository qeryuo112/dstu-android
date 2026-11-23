package com.deepstudent.platform.background.scheduling

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.Duration
import java.time.Instant
import java.time.ZoneId
import java.time.ZonedDateTime

class SchedulingPlanTest {
    @Test
    fun scheduleIdsAreOpaqueBoundedAndStrict() {
        val id = ScheduleId.parse("todo:reminder_1")
        assertEquals("todo:reminder_1", id.value)
        assertEquals(id, ScheduleId.parseOrNull("todo:reminder_1"))
        assertNull(ScheduleId.parseOrNull(null))
        assertNull(ScheduleId.parseOrNull(""))
        assertNull(ScheduleId.parseOrNull("-leading"))
        assertNull(ScheduleId.parseOrNull("has space"))
        assertNull(ScheduleId.parseOrNull("a".repeat(129)))
    }

    @Test
    fun occurrenceVersionsKeepPeriodicSentinelAndPositiveReminderIdentitySeparate() {
        assertEquals(0L, OccurrenceNo.PERIODIC.value)
        assertEquals(7L, OccurrenceNo.positive(7).value)
        assertThrows { OccurrenceNo(-1) }
        assertThrows { OccurrenceNo.positive(0) }
        assertThrows { ReminderFire(ScheduleId.parse("s"), OccurrenceNo.PERIODIC) }
    }

    @Test
    fun uniqueNamesAreStableAndDistinctPerOccurrenceAndMode() {
        val id = ScheduleId.parse("todo-1")
        val other = ScheduleId.parse("todo-2")
        assertEquals(SchedulingNames.oneTime(id, OccurrenceNo.positive(3)), SchedulingNames.oneTime(id, OccurrenceNo.positive(3)))
        assertNotEquals(SchedulingNames.oneTime(id, OccurrenceNo.positive(3)), SchedulingNames.oneTime(id, OccurrenceNo.positive(4)))
        assertNotEquals(SchedulingNames.oneTime(id, OccurrenceNo.positive(3)), SchedulingNames.oneTime(other, OccurrenceNo.positive(3)))
        assertNotEquals(SchedulingNames.periodic(id), SchedulingNames.oneTime(id, OccurrenceNo.positive(3)))
        assertEquals("dstu.schedule.todo-1", SchedulingNames.tag(id))
    }

    @Test
    fun workInputAcceptsOnlyTheTwoOpaqueKeys() {
        val request = SchedulingCodec.requestFrom("schedule-1", 5)
        assertEquals(ScheduledWorkRequest(ScheduleId.parse("schedule-1"), OccurrenceNo.positive(5)), request)
        assertEquals(OccurrenceNo.PERIODIC, SchedulingCodec.requestFrom("schedule-1", 0)?.occurrence)
        assertNull(SchedulingCodec.requestFrom(null, 1))
        assertNull(SchedulingCodec.requestFrom("schedule-1", null))
        assertNull(SchedulingCodec.requestFrom("schedule-1", -1))
        assertNull(SchedulingCodec.requestFrom("bad id", 1))
    }

    @Test
    fun initialDelayClampsPastTimesAndAlignsFutureSlots() {
        val now = Instant.parse("2026-11-24T00:00:00Z")
        assertEquals(0L, SchedulingPlan.initialDelayMillis(now, now.minusSeconds(30)))
        assertEquals(60_000L, SchedulingPlan.initialDelayMillis(now, now.plusSeconds(60)))
        val slot = ZonedDateTime.of(2026, 11, 24, 7, 0, 0, 0, ZoneId.of("Asia/Shanghai"))
        assertEquals(0L, SchedulingPlan.initialDelayMillis(now, slot))
        assertEquals(Duration.ofHours(23).toMillis(), SchedulingPlan.initialDelayMillis(now, slot.plusDays(1)))
    }

    @Test
    fun periodicIntervalAndFiniteBackoffAreExplicit() {
        SchedulingPlan.requirePeriodic(Duration.ofMinutes(15))
        SchedulingPlan.requirePeriodic(Duration.ofHours(24))
        assertThrows { SchedulingPlan.requirePeriodic(Duration.ofMinutes(14)) }
        assertThrows { SchedulingPlan.requirePeriodic(Duration.ofMinutes(-15)) }
        assertTrue(SchedulingPlan.shouldRetry(0))
        assertTrue(SchedulingPlan.shouldRetry(3))
        assertFalse(SchedulingPlan.shouldRetry(4))
        assertEquals(5, SchedulingPlan.MAX_ATTEMPTS)
    }

    @Test
    fun everyReplanActionMapsAndUnknownActionsAreIgnored() {
        assertEquals(ReplanReason.BOOT_COMPLETED, ReplanReason.fromAction("android.intent.action.BOOT_COMPLETED"))
        assertEquals(ReplanReason.USER_UNLOCKED, ReplanReason.fromAction("android.intent.action.USER_UNLOCKED"))
        assertEquals(ReplanReason.TIME_SET, ReplanReason.fromAction("android.intent.action.TIME_SET"))
        assertEquals(ReplanReason.TIMEZONE_CHANGED, ReplanReason.fromAction("android.intent.action.TIMEZONE_CHANGED"))
        assertEquals(ReplanReason.PACKAGE_REPLACED, ReplanReason.fromAction("android.intent.action.MY_PACKAGE_REPLACED"))
        assertEquals(
            ReplanReason.EXACT_ALARM_ACCESS_CHANGED,
            ReplanReason.fromAction("android.app.action.SCHEDULE_EXACT_ALARM_PERMISSION_STATE_CHANGED"),
        )
        assertNull(ReplanReason.fromAction(null))
        assertNull(ReplanReason.fromAction("android.intent.action.BATTERY_LOW"))
    }

    private fun assertThrows(block: () -> Unit) {
        try {
            block()
        } catch (_: IllegalArgumentException) {
            return
        }
        throw AssertionError("expected IllegalArgumentException")
    }
}
