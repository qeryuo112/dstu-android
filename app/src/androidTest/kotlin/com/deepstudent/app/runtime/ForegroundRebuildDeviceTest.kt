package com.deepstudent.app.runtime

import android.Manifest
import android.app.NotificationManager
import android.content.Intent
import android.os.Build
import android.os.Process
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.deepstudent.app.DstuApplication
import com.deepstudent.app.MainActivity
import com.deepstudent.core.common.id.TaskId
import com.deepstudent.platform.background.RuntimeForegroundService
import com.deepstudent.services.runtime.api.RuntimeState
import com.deepstudent.services.runtime.api.RuntimeTaskGeneration
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withContext
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File

@RunWith(AndroidJUnit4::class)
class ForegroundRebuildDeviceTest {
    private val instrumentation = InstrumentationRegistry.getInstrumentation()
    private val app get() = DstuApplication.from(instrumentation.targetContext)
    private val marker get() = File(app.cacheDir, "s223-duty-rebuild")
    private val notifications get() = app.getSystemService(NotificationManager::class.java)
    private val serviceIntent get() = Intent(app, RuntimeForegroundService::class.java)

    @Test fun sameProcessRebuildRestoresCurrentDuty(): Unit = runBlocking {
        val activity = startInForeground()
        StreamFixturePeer().use { peer ->
            val started = app.noteImport.start(peer.url, "S223 same-process rebuild")
            try {
                assertTrue(started.background)
                awaitRuntime { posted(started.taskId) }
                assertTrue(app.stopService(serviceIntent))
                awaitRuntime { !posted(started.taskId) }
                withContext(Dispatchers.Main) { checkNotNull(app.startService(serviceIntent)) }
                awaitRuntime { posted(started.taskId) }
                assertEquals(RuntimeState.RUNNING, app.runtime().controller.snapshot(started.taskId)?.state)
            } finally {
                app.noteImport.cancel(started.taskId)
                awaitRuntime { !posted(started.taskId) }
                instrumentation.runOnMainSync { activity.finish() }
            }
        }
    }

    @Test fun seedLiveDutyForHostProcessDeath(): Unit = runBlocking {
        val activity = startInForeground()
        check(!marker.exists())
        val peer = StreamFixturePeer()
        val started = app.noteImport.start(peer.url, "S223 old foreground owner")
        assertTrue(started.background)
        val runtime = app.runtime()
        val address = RuntimeTaskGeneration(started.taskId, 1)
        awaitRuntime {
            posted(started.taskId) && runtime.ledger.duties(address, null, 10).any { it.releasedAt == null } &&
                runtime.controller.snapshot(started.taskId)?.execution?.checkpoint != null
        }
        marker.writeText("${Process.myPid()}\n${started.taskId.value}\n")
        check(!activity.isDestroyed)
        instrumentation.sendStatus(
            2,
            android.os.Bundle().apply {
                putString("stream", "\nS223_LIVE_DUTY pid=${Process.myPid()}\n")
            },
        )
        Process.killProcess(Process.myPid())
        error("expected the isolated process to exit")
    }

    @Test fun coldRebuildClosesLostReferenceAndDoesNotRerunImport(): Unit = runBlocking {
        val activity = startInForeground()
        try {
            val (pid, key) = marker.readLines()
            assertNotEquals(pid.toInt(), Process.myPid())
            val id = TaskId.parse(key)
            val runtime = app.runtime()
            val snapshot = checkNotNull(runtime.controller.snapshot(id))
            assertEquals(RuntimeState.INTERRUPTED, snapshot.state)
            assertNotNull(snapshot.execution.checkpoint)
            val rows = runtime.ledger.duties(RuntimeTaskGeneration(id, 1), null, 10)
            assertTrue(rows.isNotEmpty() && rows.all { it.releasedAt != null })
            val firstCursor = snapshot.cursor
            withContext(Dispatchers.Main) { checkNotNull(app.startService(serviceIntent)) }
            awaitRuntime { serviceReconciledEmpty() }
            awaitRuntime { app.foregroundDuties.duties.value.isEmpty() && !posted(id) && !serviceRunning() }
            assertEquals(firstCursor, runtime.controller.snapshot(id)?.cursor)
            assertTrue(app.storage().notes.list().none { it.title == "S223 old foreground owner" })
            println("S223_COLD_DUTY_RECONCILED previous_pid=$pid pid=${Process.myPid()} released=${rows.size}")
            check(marker.delete())
        } finally {
            instrumentation.runOnMainSync { activity.finish() }
        }
    }

    private fun startInForeground(): android.app.Activity {
        check(app.packageName == "com.deepstudent.app.s2device")
        if (Build.VERSION.SDK_INT >= 33) {
            instrumentation.uiAutomation.grantRuntimePermission(app.packageName, Manifest.permission.POST_NOTIFICATIONS)
        }
        return instrumentation.startActivitySync(Intent(app, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
    }

    private fun serviceReconciledEmpty(): Boolean {
        val command = "logcat -d --pid=${Process.myPid()} -s DstuOperation:I"
        return android.os.ParcelFileDescriptor.AutoCloseInputStream(instrumentation.uiAutomation.executeShellCommand(command))
            .bufferedReader().useLines { lines -> lines.any { "event=background.reconcile" in it && "state=empty" in it } }
    }

    private fun posted(id: TaskId): Boolean = notifications.activeNotifications.any { it.tag == id.value && it.id == 7411 }

    @Suppress("DEPRECATION")
    private fun serviceRunning(): Boolean = app.getSystemService(android.app.ActivityManager::class.java).getRunningServices(100)
        .any { it.service.className == RuntimeForegroundService::class.java.name }
}
