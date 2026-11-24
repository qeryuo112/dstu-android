package com.deepstudent.app.execution

import android.os.ParcelFileDescriptor
import android.view.WindowManager
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.deepstudent.app.DstuApplication
import com.deepstudent.app.editor.EditorTestHost
import com.deepstudent.services.execution.api.ShellCommand
import com.deepstudent.services.execution.api.ShellResult
import com.deepstudent.services.execution.api.ShellTarget
import com.deepstudent.services.runtime.api.TaskCursor
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File
import java.util.UUID

/** Select a pre-authorized execution target. The observer never signals a process. */
@RunWith(AndroidJUnit4::class)
class ShellProcessCancellationDeviceTest {
    private val instrumentation get() = InstrumentationRegistry.getInstrumentation()
    private val app get() = instrumentation.targetContext.applicationContext as DstuApplication

    @Test fun revokeTerminatesSelectedCommandAndChild(): Unit = runBlocking {
        check(app.packageName == "com.deepstudent.app.s2device") { "isolated test APK required" }
        val args = InstrumentationRegistry.getArguments()
        val target = ShellTarget.valueOf(requireNotNull(args.getString("s227Target")))
        val expected = requireNotNull(args.getString("s227ExpectedUid")).toInt()
        val host = EditorTestHost()
        try {
            host.start()
            instrumentation.runOnMainSync { host.activity.window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON) }
            if (target == ShellTarget.SHIZUKU) requestShizukuForIsolatedApp(app)
            executeAndRevoke(target, expected)
        } finally {
            host.close()
        }
    }

    private suspend fun executeAndRevoke(target: ShellTarget, expected: Int) {
        val marker = marker(target)
        val runtime = app.runtime()
        try {
            runtime.shell.submit(ShellCommand(target, marker.command))
            val ticket = withTimeout(15_000) { runtime.capabilities.pending().first { it.isNotEmpty() }.single() }
            assertEquals(target.name, ticket.binding.target.value)
            assertEquals(expected, ticket.binding.uid)
            assertTrue(runtime.capabilities.decide(ticket.address, true))
            val processes = withTimeout(8_000) {
                var found = marker.processes()
                while (found == null) {
                    delay(50)
                    found = marker.processes()
                }
                found
            }
            assertTrue("both test processes should be running before revocation", live(processes).containsAll(processes))
            runtime.shell.revoke(ticket.address.task.taskId.value)
            val ended = withTimeout(5_000) { runtime.shell.state.first { !it.waitingOrRunning } }
            assertEquals(ShellResult.Blocked("CANCELLED"), ended.result)
            withTimeout(3_000) { while (live(processes).isNotEmpty()) delay(50) }
            assertTrue("no delayed success marker", !marker.read().contains("late"))
            val codes = runtime.ledger.audit(ticket.address.task, TaskCursor.ZERO, 10).map { it.code.value }
            assertTrue("durable revoke and cancelled audit rows", codes.containsAll(listOf("authorized", "revoked", "cancelled")))
            println("S227_CANCEL target=$target uid=$expected command_and_child_not_live=true audit=true")
        } finally {
            val active = runtime.shell.state.value
            if (active.waitingOrRunning) active.taskId?.let { runtime.shell.revoke(it) }
            marker.clean()
        }
    }

    private suspend fun marker(target: ShellTarget): Marker {
        val name = "dstu-s227-cancel-${UUID.randomUUID().toString().replace("-", "")}"
        return when (target) {
            ShellTarget.APP -> Marker(name, File(app.filesDir, "terminal/$name"))

            ShellTarget.LINUX -> {
                val generation = checkNotNull(app.environments().state.value.activeGeneration)
                Marker(name, File(app.filesDir, "environments/generations/$generation/rootfs/root/$name"))
            }

            ShellTarget.ROOT, ShellTarget.SHIZUKU -> Marker("/data/local/tmp/$name", null)
        }
    }

    private fun live(pids: List<Int>): List<Int> = shell("/system/bin/ps -A -o PID,STAT").lineSequence().mapNotNull { line ->
        val fields = line.trim().split(Regex("\\s+"), limit = 2)
        val pid = fields.firstOrNull()?.toIntOrNull() ?: return@mapNotNull null
        val state = fields.getOrNull(1)?.firstOrNull()
        pid.takeIf { it in pids && state != null && state != 'Z' && state != 'X' }
    }.toList()

    private fun shell(command: String): String = ParcelFileDescriptor.AutoCloseInputStream(
        instrumentation.uiAutomation.executeShellCommand(command),
    ).bufferedReader().use { it.readText() }

    private inner class Marker(private val path: String, private val local: File?) {
        val command = "umask 022; sleep 15 & child=\$!; printf '%s %s\\n' \"\$\$\" \"\$child\" > '$path'; " +
            "wait \"\$child\"; printf late >> '$path'"

        fun read(): String = if (local != null) {
            if (local.isFile) local.readText() else ""
        } else {
            shell("/system/bin/cat $path")
        }

        fun processes(): List<Int>? = read().lineSequence().firstOrNull()?.trim()?.split(' ')
            ?.mapNotNull(String::toIntOrNull)?.takeIf { it.size == 2 && it.all { pid -> pid > 0 } }

        fun clean() {
            if (local != null) local.delete() else shell("/system/bin/rm -- $path")
        }
    }
}
