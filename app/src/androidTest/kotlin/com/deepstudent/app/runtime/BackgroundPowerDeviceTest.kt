package com.deepstudent.app.runtime

import android.Manifest
import android.app.NotificationManager
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import androidx.compose.ui.test.assertIsOff
import androidx.compose.ui.test.assertIsOn
import androidx.compose.ui.test.assertTextEquals
import androidx.compose.ui.test.junit4.createEmptyComposeRule
import androidx.compose.ui.test.onAllNodesWithTag
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performClick
import androidx.test.core.app.ActivityScenario
import androidx.test.platform.app.InstrumentationRegistry
import com.deepstudent.app.DstuApplication
import com.deepstudent.app.MainActivity
import com.deepstudent.app.R
import com.deepstudent.app.storage.AndroidOperationLogger
import com.deepstudent.platform.background.WakeAcquireResult
import com.deepstudent.platform.background.WakeLockController
import com.deepstudent.platform.background.WakeLockFactory
import com.deepstudent.platform.background.WakeLockHandle
import com.deepstudent.platform.background.WakeLockLimits
import com.deepstudent.platform.background.WakeOwner
import com.deepstudent.platform.background.WakeOwnerKind
import com.deepstudent.platform.background.WakePhase
import com.deepstudent.platform.background.androidWakeLockFactory
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test

class BackgroundPowerDeviceTest {
    @get:Rule val compose = createEmptyComposeRule()
    private val instrumentation = InstrumentationRegistry.getInstrumentation()
    private val app get() = DstuApplication.from(instrumentation.targetContext)
    private val notices get() = app.getSystemService(NotificationManager::class.java)

    @Test(timeout = 90_000)
    fun realTerminalDutyTracksTheSavedSwitchAndDoesNotHoldAnIdleLock() = runBlocking {
        checkIsolated()
        if (Build.VERSION.SDK_INT >=
            33
        ) {
            instrumentation.uiAutomation.grantRuntimePermission(app.packageName, Manifest.permission.POST_NOTIFICATIONS)
        }
        withPowerPanel {
            val sessions = app.terminal()
            val switch = compose.onNodeWithTag("background-keep-alive")
            switch.assertIsOff().performClick()
            awaitRuntime { app.terminalResidency.enabled.value }
            assertTrue(app.foregroundDuties.slots.value.isEmpty())
            assertTrue(notices.activeNotifications.none { it.id == 7410 || it.id == 7412 })
            val first = sessions.open()
            val second = sessions.open()
            try {
                awaitRuntime { notices.activeNotifications.count { it.id == 7412 } == 2 }
                sessions.write(first, "printf 'S224-TERMINAL\\n'\n")
                awaitRuntime {
                    sessions.observe(first)?.value?.screen?.lines?.any { row ->
                        row.joinToString("") { it.text }.contains("S224-TERMINAL")
                    } ==
                        true
                }
                assertFalse(app.terminalResidency.hasActivePowerWindow())
                sessions.terminate(second)
                awaitRuntime { notices.activeNotifications.count { it.id == 7412 } == 1 }
                notices.activeNotifications.single { it.id == 7412 }.notification.actions.single().actionIntent.send()
                awaitRuntime { !app.terminalResidency.enabled.value && notices.activeNotifications.none { it.id == 7410 || it.id == 7412 } }
                assertTrue(sessions.observe(first)?.value?.phase == com.deepstudent.features.terminal.api.TerminalPhase.RUNNING)
                switch.assertIsOff().performClick()
                awaitRuntime { app.terminalResidency.enabled.value && notices.activeNotifications.any { it.id == 7412 } }
                println("S224_TERMINAL_DUTIES active=2 isolated=true no_idle_wakelock=true")
            } finally {
                sessions.close(first)
                sessions.close(second)
                awaitRuntime { notices.activeNotifications.none { it.id == 7410 || it.id == 7412 } }
            }
        }
    }

    @Test(timeout = 45_000)
    fun restartKeepsOnlyPreferenceAndReadsCurrentSystemBatteryState() = runBlocking {
        checkIsolated()
        withPowerPanel {
            awaitRuntime { app.terminalResidency.enabled.value }
            compose.onNodeWithTag("background-keep-alive").assertIsOn()
            assertTrue(app.foregroundDuties.continuousDuties.value.isEmpty())
            assertBatteryText()
            app.terminalResidency.setEnabled(false)
        }
    }

    @Test(timeout = 45_000)
    fun batteryPageReflectsSystemState() = runBlocking {
        checkIsolated()
        withPowerPanel { assertBatteryText() }
    }

    @Test(timeout = 45_000)
    fun realWakeLockTimesOutAndControllerReleasesOnStop() = runBlocking {
        checkIsolated()
        assertEquals(PackageManager.PERMISSION_GRANTED, app.checkSelfPermission(Manifest.permission.WAKE_LOCK))
        val owner = WakeOwner(WakeOwnerKind.CONTINUOUS, "s224-fixture")
        val realLocks = androidWakeLockFactory(app)
        lateinit var actual: WakeLockHandle
        val factory = WakeLockFactory { key, phase -> realLocks.create(key, phase).also { actual = it } }
        WakeLockController(factory, AndroidOperationLogger, { WakeLockLimits(2_000, 6_000) }).use { locks ->
            assertTrue(locks.acquire(owner, WakePhase.WORK) is WakeAcquireResult.Held)
            assertTrue(actual.isHeld)
            // Read the real system lock before touching the controller, so this proves OS timeout.
            awaitRuntime { !actual.isHeld }
            assertTrue(locks.heldPhases(owner).isEmpty())
            assertTrue(locks.acquire(owner, WakePhase.WORK) is WakeAcquireResult.Held)
            assertTrue(actual.isHeld)
            locks.release(owner)
            assertFalse(actual.isHeld)
            assertTrue(locks.acquire(owner, WakePhase.WORK) is WakeAcquireResult.Held)
            locks.releaseAll()
            assertFalse(actual.isHeld)
        }
        assertFalse(actual.isHeld)
        evidence("S224_WAKELOCK timeout=true owner_release=true service_release=true")
    }

    private suspend fun withPowerPanel(block: suspend () -> Unit) {
        val activity = ActivityScenario.launch<MainActivity>(Intent(app, MainActivity::class.java))
        try {
            awaitTag("workspace-open-background-power")
            compose.onNodeWithTag("workspace-open-background-power").performClick()
            awaitTag("background-power-panel")
            block()
        } finally {
            activity.close()
        }
    }

    private fun assertBatteryText() {
        val ignore = app.getSystemService(android.os.PowerManager::class.java).isIgnoringBatteryOptimizations(app.packageName)
        compose.onNodeWithTag("background-battery-status").assertTextEquals(
            app.getString(
                if (ignore) R.string.background_power_battery_ignored else R.string.background_power_battery_optimized,
            ),
        )
        val expected = InstrumentationRegistry.getArguments().getString("s224BatteryIgnoring")
        if (expected != null) assertEquals(expected.toBooleanStrict(), ignore)
        evidence("S224_REAL_BATTERY ignoring=$ignore")
    }

    private fun awaitTag(tag: String) = compose.waitUntil(15_000) {
        compose.onAllNodesWithTag(tag).fetchSemanticsNodes(atLeastOneRootRequired = false).isNotEmpty()
    }

    private fun evidence(message: String) {
        instrumentation.sendStatus(2, android.os.Bundle().apply { putString("stream", "\n$message\n") })
    }

    private fun checkIsolated() = check(app.packageName == "com.deepstudent.app.s2device")
}
