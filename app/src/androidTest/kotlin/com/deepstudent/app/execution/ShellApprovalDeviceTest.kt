package com.deepstudent.app.execution

import android.os.Process
import android.view.WindowManager
import android.view.accessibility.AccessibilityNodeInfo
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.deepstudent.app.DstuApplication
import com.deepstudent.app.editor.EditorTestHost
import com.deepstudent.core.common.id.TaskId
import com.deepstudent.features.workspace.api.OpenPanel
import com.deepstudent.features.workspace.api.PanelKey
import com.deepstudent.services.execution.api.ShellCommand
import com.deepstudent.services.execution.api.ShellResult
import com.deepstudent.services.execution.api.ShellTarget
import com.deepstudent.services.runtime.api.RuntimeTaskGeneration
import com.deepstudent.services.runtime.api.TaskCursor
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File
import java.util.UUID

@RunWith(AndroidJUnit4::class)
class ShellApprovalDeviceTest {
    private val instrumentation get() = InstrumentationRegistry.getInstrumentation()
    private val app get() = instrumentation.targetContext.applicationContext as DstuApplication

    @Test fun deniedAfterActivityRecreationNeverCreatesFile(): Unit = runBlocking {
        check(app.packageName == "com.deepstudent.app.s2device") { "isolated test APK required" }
        val runtime = app.runtime()
        val marker = File(app.filesDir, "terminal/s226-${UUID.randomUUID()}")
        val host = EditorTestHost()
        try {
            runtime.shell.submit(ShellCommand(ShellTarget.APP, "printf denied > '${marker.name}'"))
            val ticket = withTimeout(15_000) { runtime.capabilities.pending().first { it.isNotEmpty() }.single() }
            host.start()
            val workspace = app.workspace().workspace
            val approvals = PanelKey.parse("approvals")
            val panel = workspace.state.value.layout.panels.firstOrNull { it.key == approvals }
            if (panel == null) workspace.open(OpenPanel(approvals)) else workspace.focus(panel.id)
            awaitControl("拒绝")
            println("S226_APPROVAL phone_ui_visible=true")
            val original = host.activity
            instrumentation.runOnMainSync {
                original.window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
                original.recreate()
            }
            withTimeout(15_000) { while (host.activity === original) delay(25) }
            instrumentation.runOnMainSync { host.activity.window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON) }
            assertEquals(ticket.address, runtime.capabilities.pending().first().single().address)
            awaitControl("拒绝", click = true)
            println("S226_APPROVAL activity_recreated=true same_ticket=true clicked_deny=true")
            val result = withTimeout(15_000) { runtime.shell.state.first { !it.waitingOrRunning }.result }
            assertEquals(ShellResult.Blocked("DENIED"), result)
            assertFalse(marker.exists())
            val audit = runtime.ledger.audit(ticket.address.task, TaskCursor.ZERO, 10).map { it.code.value }
            assertEquals(listOf("shell.execute", "DENIED", "denied"), audit)
        } finally {
            host.close()
            marker.delete()
        }
    }

    @Test fun appCommandReturnsActualUidAndRevocationStopsOwnedPty(): Unit = runBlocking {
        check(app.packageName == "com.deepstudent.app.s2device") { "isolated test APK required" }
        val runtime = app.runtime()
        val marker = File(app.filesDir, "terminal/s227-${UUID.randomUUID()}")
        try {
            runtime.shell.submit(ShellCommand(ShellTarget.APP, "printf hello; exit 7"))
            val first = withTimeout(15_000) { runtime.capabilities.pending().first { it.isNotEmpty() }.single() }
            assertTrue(runtime.capabilities.decide(first.address, true))
            val state = withTimeout(15_000) { runtime.shell.state.first { !it.waitingOrRunning } }
            val receipt = (state.result as ShellResult.Exited).receipt
            assertEquals(Process.myUid(), receipt.observedUid)
            assertEquals(7, receipt.exitCode)
            assertEquals("hello", receipt.output.stdout)
            runtime.shell.submit(ShellCommand(ShellTarget.APP, "printf live > '${marker.name}'; sleep 8; printf late >> '${marker.name}'"))
            val second = withTimeout(15_000) { runtime.capabilities.pending().first { it.isNotEmpty() }.single() }
            assertTrue(runtime.capabilities.decide(second.address, true))
            withTimeout(5_000) { while (!marker.exists()) delay(25) }
            runtime.shell.revoke(second.address.task.taskId.value)
            val stopped = withTimeout(5_000) { runtime.shell.state.first { !it.waitingOrRunning } }
            assertEquals(ShellResult.Blocked("CANCELLED"), stopped.result)
            assertEquals("live", marker.readText())
            assertTrue(
                runtime.ledger.audit(RuntimeTaskGeneration(TaskId.parse(stopped.taskId!!), 1), TaskCursor.ZERO, 10)
                    .any { it.code.value == "revoked" },
            )
        } finally {
            marker.delete()
        }
    }

    // The production activity renders its own composition. No Compose test clock or replacement
    // content participates in destroying/recreating the window.
    private suspend fun awaitControl(label: String, click: Boolean = false) = withTimeout(15_000) {
        while (!findControl(label, click)) delay(100)
    }

    private fun findControl(label: String, click: Boolean): Boolean {
        val root = instrumentation.uiAutomation.rootInActiveWindow ?: return false
        if (root.packageName?.toString() != app.packageName) return false
        return visitControl(root, label, click)
    }

    private fun visitControl(node: AccessibilityNodeInfo, label: String, click: Boolean): Boolean {
        val matched = node.text?.toString()?.trim() == label || node.contentDescription?.toString()?.trim() == label
        if (matched && node.isVisibleToUser && (!click || clickControl(node))) return true
        // Compose exposes virtual children even on devices whose find-by-text bridge omits them.
        for (index in 0 until node.childCount) {
            val child = node.getChild(index) ?: continue
            if (visitControl(child, label, click)) return true
        }
        return false
    }

    private fun clickControl(node: AccessibilityNodeInfo): Boolean {
        var current: AccessibilityNodeInfo? = node
        repeat(4) {
            val control = current ?: return false
            if (control.packageName?.toString() != app.packageName) return false
            if (control.isClickable && control.isEnabled) return control.performAction(AccessibilityNodeInfo.ACTION_CLICK)
            current = control.parent
        }
        return false
    }
}
