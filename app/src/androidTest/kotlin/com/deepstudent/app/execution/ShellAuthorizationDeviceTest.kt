package com.deepstudent.app.execution

import android.view.WindowManager
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.deepstudent.app.DstuApplication
import com.deepstudent.app.editor.EditorTestHost
import com.deepstudent.services.execution.api.ExecutionFailureCode
import com.deepstudent.services.execution.api.ShellCommand
import com.deepstudent.services.execution.api.ShellResult
import com.deepstudent.services.execution.api.ShellTarget
import com.deepstudent.services.execution.api.ShizukuConnectionState
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.selects.select
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File
import java.io.IOException
import java.util.concurrent.TimeUnit

/** An explicit, fixed identity-only Root request from the actual isolated package UID. */
@RunWith(AndroidJUnit4::class)
class ShellAuthorizationDeviceTest {
    @Test fun requestIndependentRootForIsolatedApp(): Unit = runBlocking {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val app = instrumentation.targetContext.applicationContext as DstuApplication
        check(app.packageName == "com.deepstudent.app.s2device") { "isolated test APK required" }
        val host = EditorTestHost()
        try {
            host.start()
            instrumentation.runOnMainSync { host.activity.window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON) }
            val runtime = app.runtime()
            runtime.shell.submit(ShellCommand(ShellTarget.ROOT, "id -u; printf 'S227-verified\\n'; exit 7"))
            val ticket = withTimeout(15_000) {
                val request = async { runtime.capabilities.pending().first { it.isNotEmpty() }.single() }
                val unavailable = async { runtime.shell.state.first { !it.waitingOrRunning } }
                try {
                    select {
                        request.onAwait { it }
                        unavailable.onAwait { null }
                    }
                } finally {
                    request.cancel()
                    unavailable.cancel()
                }
            }
            if (ticket == null) {
                diagnosticSuRequest(app.cacheDir)
                error("ROOT adapter did not expose an approval request")
            }
            assertEquals("ROOT", ticket.binding.target.value)
            assertEquals(0, ticket.binding.uid)
            println("S227_ROOT_REQUEST route=production_consent app_uid=${android.os.Process.myUid()}")
            assertTrue(runtime.capabilities.decide(ticket.address, true))
            val state = withTimeout(30_000) { runtime.shell.state.first { !it.waitingOrRunning } }
            val result = state.result as? ShellResult.Exited ?: error("independent ROOT request returned ${state.result}")
            assertEquals(0, result.receipt.observedUid)
            assertEquals(7, result.receipt.exitCode)
            assertEquals(listOf("0", "S227-verified"), result.receipt.output.stdout.trim().lines())
            println("S227_DEVICE target=ROOT observed_uid=0 verified_exit=7")
        } finally {
            host.close()
        }
    }

    @Test fun independentRootFailsClosedOnUnrootedDevice(): Unit = runBlocking {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val app = instrumentation.targetContext.applicationContext as DstuApplication
        check(app.packageName == "com.deepstudent.app.s2device") { "isolated test APK required" }
        val runtime = app.runtime()
        runtime.shell.submit(ShellCommand(ShellTarget.ROOT, "id -u"))
        val result = withTimeout(5_000) { runtime.shell.state.first { !it.waitingOrRunning } }
        assertEquals(null, result.taskId)
        assertEquals(ShellResult.Unavailable(ExecutionFailureCode.PROCESS_START_FAILED), result.result)
        assertTrue(runtime.capabilities.pending().first().isEmpty())
        println("S227_ROOT_UNAVAILABLE rejected=true no_task=true no_fallback=true")
    }

    private suspend fun diagnosticSuRequest(cwd: File) = withContext(Dispatchers.IO) {
        println("S227_ROOT_REQUEST route=diagnostic_su app_uid=${android.os.Process.myUid()}")
        val process = try {
            ProcessBuilder("/system/bin/su", "-c", "/system/bin/id -u").directory(cwd).redirectErrorStream(true).start()
        } catch (failure: IOException) {
            val code = if (failure.message.orEmpty().contains("error=2")) "ENOENT" else "IO_ERROR"
            println("S227_ROOT_REQUEST process_started=false errno=$code")
            throw IOException("isolated app could not start standard su: $code")
        }
        try {
            check(process.waitFor(20, TimeUnit.SECONDS)) { "system Root authorization did not complete" }
            val uid = process.inputStream.use { input ->
                ByteArray(64).let { bytes ->
                    val size = input.read(bytes).coerceAtLeast(0)
                    String(bytes, 0, size, Charsets.UTF_8).trim().toIntOrNull()
                }
            }
            println("S227_ROOT_REQUEST process_started=true exit=${process.exitValue()} observed_uid=${uid ?: "unknown"}")
        } finally {
            process.destroyForcibly()
            process.outputStream.close()
            process.inputStream.close()
            process.errorStream.close()
        }
    }
}

/** The system grant may be per process, so a new instrumentation run asks again before dispatch. */
internal suspend fun requestShizukuForIsolatedApp(app: DstuApplication) {
    check(app.packageName == "com.deepstudent.app.s2device") { "isolated test APK required" }
    val system = app.execution()
    system.start()
    system.refresh()
    val status = withTimeout(15_000) {
        system.status.first { it.canRequestPermission || it.state == ShizukuConnectionState.READY }
    }
    if (status.canRequestPermission) {
        println("S227_SHIZUKU_REQUEST app_uid=${android.os.Process.myUid()}")
        val grant = CompletableDeferred<Boolean>()
        InstrumentationRegistry.getInstrumentation().runOnMainSync { system.requestPermission { grant.complete(it) } }
        assertTrue("Shizuku grant not given for isolated APK", withTimeout(45_000) { grant.await() })
    }
}
