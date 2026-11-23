package com.deepstudent.platform.background.scheduling

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNull
import org.junit.Test

class ReminderIdentityTest {
    @Test
    fun roundTripKeepsScheduleAndOccurrenceVersion() {
        val fire = ReminderFire(ScheduleId.parse("todo:reminder-1"), OccurrenceNo.positive(42))
        val encoded = ReminderIdentity.encode(fire)
        assertEquals("dstu-reminder://alarm/todo:reminder-1?occurrence=42", encoded)
        assertEquals(fire, ReminderIdentity.decode(encoded))
    }

    @Test
    fun eachScheduleAndOccurrenceProducesADistinctStableIdentity() {
        val first = ReminderFire(ScheduleId.parse("one"), OccurrenceNo.positive(1))
        val same = ReminderFire(ScheduleId.parse("one"), OccurrenceNo.positive(1))
        val next = ReminderFire(ScheduleId.parse("one"), OccurrenceNo.positive(2))
        val other = ReminderFire(ScheduleId.parse("two"), OccurrenceNo.positive(1))
        assertEquals(ReminderIdentity.encode(first), ReminderIdentity.encode(same))
        assertNotEquals(ReminderIdentity.encode(first), ReminderIdentity.encode(next))
        assertNotEquals(ReminderIdentity.encode(first), ReminderIdentity.encode(other))
    }

    @Test
    fun malformedOrStaleIdentitiesAreRejected() {
        assertNull(ReminderIdentity.decode(null))
        assertNull(ReminderIdentity.decode(""))
        assertNull(ReminderIdentity.decode("dstu-task://alarm/one?occurrence=1"))
        assertNull(ReminderIdentity.decode("dstu-reminder://other/one?occurrence=1"))
        assertNull(ReminderIdentity.decode("dstu-reminder://alarm/one/two?occurrence=1"))
        assertNull(ReminderIdentity.decode("dstu-reminder://alarm/one"))
        assertNull(ReminderIdentity.decode("dstu-reminder://alarm/one?occurrence="))
        assertNull(ReminderIdentity.decode("dstu-reminder://alarm/one?occurrence=0"))
        assertNull(ReminderIdentity.decode("dstu-reminder://alarm/one?occurrence=-1"))
        assertNull(ReminderIdentity.decode("dstu-reminder://alarm/one?occurrence=x"))
        assertNull(ReminderIdentity.decode("dstu-reminder://alarm/one?occurrence=1&other=2"))
        assertNull(ReminderIdentity.decode("dstu-reminder://alarm/bad%20id?occurrence=1"))
    }
}
