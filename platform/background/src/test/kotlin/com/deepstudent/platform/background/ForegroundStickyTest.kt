package com.deepstudent.platform.background

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ForegroundStickyTest {
    private val terminal = continuousDuty("terminal-1")

    @Test
    fun defaultHostIsNeverSticky() {
        val host = TestForegroundHost(DutyRegistry(), query(null))
        assertFalse(ForegroundSticky.shouldStick(host, listOf(terminal)))
    }

    @Test
    fun optInAloneIsNotStickyWithoutAColdRecoverableDuty() {
        val host = TestForegroundHost(DutyRegistry(), query(null), stickyOptIn = true)
        assertFalse(ForegroundSticky.shouldStick(host, listOf(terminal)))
        assertFalse(ForegroundSticky.shouldStick(host, emptyList()))
    }

    @Test
    fun recoverableDutyAloneIsNotStickyWithoutUserOptIn() {
        val host = TestForegroundHost(
            DutyRegistry(),
            query(null),
            coldRecoverable = { it == terminal },
        )
        assertFalse(ForegroundSticky.shouldStick(host, listOf(terminal)))
    }

    @Test
    fun stickyRequiresOptInAndARecoverableContinuousDuty() {
        val host = TestForegroundHost(
            DutyRegistry(),
            query(null),
            stickyOptIn = true,
            coldRecoverable = { it == terminal },
        )
        assertTrue(ForegroundSticky.shouldStick(host, listOf(terminal)))
    }
}
