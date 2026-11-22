package com.deepstudent.platform.execution

import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.deepstudent.services.execution.api.ExecutionCommand
import com.deepstudent.services.execution.api.ExecutionRequest
import com.deepstudent.services.execution.api.ExecutionResult
import com.deepstudent.services.execution.api.ExecutionSystem
import com.deepstudent.services.execution.api.ShizukuConnectionState
import java.io.File
import kotlinx.coroutines.async
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/**
 * This suite NEVER starts Shizuku or requests/grants/revokes its permission.
 * Missing prerequisites are JUnit skips by default, not passes. With the runner
 * argument requireShizuku=true they fail explicitly. Once READY is observed,
 * every execution/UID/descriptor failure is an assertion failure, never a skip.
 */
@RunWith(AndroidJUnit4::class)
class ShizukuRealBinderDeviceTest {
    @get:Rule
    val foreground = ExecutionForegroundRule()

    @Test
    fun realBackendUidMatchesIdAndPackageQueryIsReadOnly() = runBlocking {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val system = ShizukuExecutionFactory.create(context)
        try {
            system.start()
            requireReadyOrSkip(system)
            val backendUid = system.status.value.backendUid
            assertTrue("backend must be root or shell", backendUid == 0 || backendUid == 2000)
            // Warm both framework paths before measuring local descriptor ownership.
            assertSuccess(system.execute(request(ExecutionCommand.Identity)))
            assertSuccess(system.execute(request(ExecutionCommand.PackageList)))
            val before = descriptorCount()
            repeat(4) {
                val identity = async { system.execute(request(ExecutionCommand.Identity)) }
                val packages = async { system.execute(request(ExecutionCommand.PackageList)) }
                val id = assertSuccess(identity.await())
                val pm = assertSuccess(packages.await())
                assertEquals(0, id.exitCode)
                assertEquals(backendUid, id.identity.backendUid)
                assertEquals(backendUid, id.identity.observedUid)
                assertEquals(backendUid, system.status.value.observedUid)
                assertTrue("id must report the observed UID", id.output.stdout.startsWith("uid=$backendUid("))
                assertEquals(listOf("id"), id.audit.argv)
                assertEquals(listOf("pm", "list", "packages"), pm.audit.argv)
                assertEquals(backendUid, pm.identity.backendUid)
                assertEquals(0, pm.exitCode)
                assertTrue("pm returned no package records", pm.output.stdout.lineSequence().any { it.startsWith("package:") })
            }
            assertFdBaseline(before)
        } finally {
            system.close()
            assertEquals(ShizukuConnectionState.CLOSED, system.status.value.state)
        }
    }

    private suspend fun requireReadyOrSkip(system: ExecutionSystem) {
        val args = InstrumentationRegistry.getArguments()
        val waitMillis = args.getString("waitForShizukuPermissionMillis", "5000").toLong()
        require(waitMillis in 1_000..180_000) { "permission wait must be 1000..180000 ms" }
        val deadline = System.nanoTime() + java.util.concurrent.TimeUnit.MILLISECONDS.toNanos(waitMillis)
        while (System.nanoTime() < deadline) {
            system.refresh()
            if (system.status.value.state == ShizukuConnectionState.READY) return
            delay(100)
        }
        val status = system.status.value
        val missing = status.state in setOf(
            ShizukuConnectionState.NOT_INSTALLED,
            ShizukuConnectionState.NOT_RUNNING,
            ShizukuConnectionState.PERMISSION_REQUIRED,
            ShizukuConnectionState.REVOKED,
        )
        assertTrue("Shizuku failed preflight: ${status.state}, diagnostic=${status.diagnostic}", missing)
        val required = InstrumentationRegistry.getArguments().getString("requireShizuku", "false").toBooleanStrict()
        val reason = "Shizuku prerequisite absent: ${status.state}; the user must start/authorize the test APK in the normal UI"
        if (required) assertEquals(reason, ShizukuConnectionState.READY, status.state)
        assumeTrue("SKIP: $reason", false)
    }

    private suspend fun assertFdBaseline(before: Int) {
        repeat(20) {
            if (descriptorCount() <= before + 2) return
            delay(100)
        }
        assertTrue("local descriptor ownership grew after read-only queries", descriptorCount() <= before + 2)
    }

    private fun assertSuccess(result: ExecutionResult) = when (result) {
        is ExecutionResult.Success -> result.receipt
        is ExecutionResult.Failure -> error("execution failed: ${result.failure.code}, exit=${result.failure.exitCode}")
    }

    private fun descriptorCount(): Int = checkNotNull(File("/proc/self/fd").listFiles()).size

    private fun request(command: ExecutionCommand) = ExecutionRequest(
        taskId = "real-binder-${command.commandId}",
        callerId = "androidTest.realBinder",
        command = command,
    )
}
