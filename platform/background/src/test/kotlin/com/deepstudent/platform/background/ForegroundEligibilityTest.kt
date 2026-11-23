package com.deepstudent.platform.background

import android.content.pm.ServiceInfo
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class ForegroundEligibilityTest {
    @Test
    fun dataSyncNeedsApi29AndNeverAppearsBelowIt() {
        assertEquals(0, ForegroundEligibility.type(ForegroundPurpose.DATA_SYNC, 26))
        assertEquals(0, ForegroundEligibility.type(ForegroundPurpose.DATA_SYNC, 28))
        assertEquals(ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC, ForegroundEligibility.type(ForegroundPurpose.DATA_SYNC, 29))
        assertEquals(ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC, ForegroundEligibility.type(ForegroundPurpose.DATA_SYNC, 36))
    }

    @Test
    fun terminalUsesSpecialUseOnlyFromApi34() {
        assertEquals(0, ForegroundEligibility.type(ForegroundPurpose.TERMINAL, 26))
        assertEquals(0, ForegroundEligibility.type(ForegroundPurpose.TERMINAL, 33))
        assertEquals(
            ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE,
            ForegroundEligibility.type(ForegroundPurpose.TERMINAL, 34),
        )
        assertEquals(
            ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE,
            ForegroundEligibility.type(ForegroundPurpose.TERMINAL, 36),
        )
    }

    @Test
    fun mixedSlotsUseTheUnionOfTheirRealTypes() {
        val slots = listOf(
            ForegroundSlot.Task(duty("sync")),
            ForegroundSlot.Continuous(continuousDuty("terminal-1")),
        )
        assertEquals(
            ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC or ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE,
            ForegroundEligibility.types(slots, 34),
        )
        assertEquals(ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC, ForegroundEligibility.types(slots, 33))
    }

    @Test
    fun timeoutTypeOnlyWithdrawsItsOwnPurpose() {
        assertEquals(
            setOf(ForegroundPurpose.DATA_SYNC),
            ForegroundEligibility.purposesForTimeoutType(ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC),
        )
        assertEquals(
            setOf(ForegroundPurpose.TERMINAL),
            ForegroundEligibility.purposesForTimeoutType(ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE),
        )
        assertTrue(ForegroundEligibility.purposesForTimeoutType(0).isEmpty())
        assertTrue(
            ForegroundEligibility.purposesForTimeoutType(ServiceInfo.FOREGROUND_SERVICE_TYPE_MICROPHONE).isEmpty(),
        )
    }
}
