package com.deepstudent.app.execution

import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.deepstudent.app.DstuApplication
import com.deepstudent.app.editor.EditorTestHost
import com.deepstudent.services.execution.api.ShellCommand
import com.deepstudent.services.execution.api.ShellResult
import com.deepstudent.services.execution.api.ShellTarget
import com.deepstudent.services.execution.api.ShizukuConnectionState
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

/** Select explicitly from the host: requires real provisioned backend, no simulation or fallback. */
@RunWith(AndroidJUnit4::class)
class ShellTargetDeviceReplayTest {
    private val args get() = InstrumentationRegistry.getArguments()
    private val app get() = InstrumentationRegistry.getInstrumentation().targetContext.applicationContext as DstuApplication

    @Test fun selectedTargetReportsActualIdentity(): Unit = runBlocking {
        check(app.packageName == "com.deepstudent.app.s2device") { "isolated test APK required" }
        val target = ShellTarget.valueOf(requireNotNull(args.getString("s227Target")))
        val expected = requireNotNull(args.getString("s227ExpectedUid")).toInt()
        val host = EditorTestHost()
        try {
            if (target == ShellTarget.SHIZUKU) requireAuthorizedShizuku(host)
            val runtime = app.runtime()
            runtime.shell.submit(ShellCommand(target, "id -u; printf 'S227-verified\\n'; exit 7"))
            val pending = withTimeout(15_000) { runtime.capabilities.pending().first { it.isNotEmpty() }.single() }
            assertEquals(target.name, pending.binding.target.value)
            assertEquals(expected, pending.binding.uid)
            assertTrue(runtime.capabilities.decide(pending.address, true))
            val state = withTimeout(30_000) { runtime.shell.state.first { !it.waitingOrRunning } }
            val result = state.result as? ShellResult.Exited ?: error("selected target did not execute: ${state.result}")
            assertEquals(expected, result.receipt.observedUid)
            assertEquals(7, result.receipt.exitCode)
            assertEquals(listOf(expected.toString(), "S227-verified"), result.receipt.output.stdout.trim().lines())
            println("S227_DEVICE target=$target observed_uid=$expected verified_exit=7")
        } finally {
            host.close()
        }
    }

    private suspend fun requireAuthorizedShizuku(host: EditorTestHost) {
        host.start()
        val system = app.execution()
        system.start()
        system.refresh()
        val status = withTimeout(15_000) {
            system.status.first { it.canRequestPermission || it.state == ShizukuConnectionState.READY }
        }
        if (status.canRequestPermission) {
            val decision = CompletableDeferred<Boolean>()
            InstrumentationRegistry.getInstrumentation().runOnMainSync { system.requestPermission { decision.complete(it) } }
            assertTrue("Shizuku grant not given for isolated APK", withTimeout(40_000) { decision.await() })
        }
    }
}
