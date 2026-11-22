package com.deepstudent.app.runtime

import android.app.NotificationManager
import android.os.ParcelFileDescriptor
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.deepstudent.app.DstuApplication
import com.deepstudent.core.common.id.TaskId
import com.deepstudent.core.common.json.JsonValue
import com.deepstudent.platform.background.ForegroundDutyStarter
import com.deepstudent.platform.background.ForegroundStartResult
import com.deepstudent.services.runtime.api.RuntimeSnapshot
import com.deepstudent.services.runtime.api.RuntimeState
import com.deepstudent.services.runtime.api.RuntimeTaskQuery
import com.deepstudent.services.runtime.api.TaskSubmission
import java.io.Closeable
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import kotlinx.coroutines.withTimeoutOrNull
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import androidx.test.runner.lifecycle.ActivityLifecycleMonitorRegistry
import androidx.test.runner.lifecycle.Stage

@RunWith(AndroidJUnit4::class)
class ForegroundRuntimeDeviceTest {
    private val instrumentation = InstrumentationRegistry.getInstrumentation()
    private val app = instrumentation.targetContext.applicationContext as DstuApplication
    private val notifications get() = app.getSystemService(NotificationManager::class.java)

    @Test fun activityClosesTasksContinueAndNotificationStopsOnlyItsTask(): Unit = runBlocking {
        instrumentation.uiAutomation.grantRuntimePermission(app.packageName, android.Manifest.permission.POST_NOTIFICATIONS)
        shell("am start -W -n ${app.packageName}/.MainActivity")
        val runtime = app.runtime()
        StreamFixturePeer().use { one ->
            StreamFixturePeer().use { two ->
                var firstTask: TaskId? = null
                var secondTask: TaskId? = null
                var firstDuty: Closeable? = null
                var secondDuty: Closeable? = null
                var testFailure: Throwable? = null
                try {
                    val first = runtime.controller.submit(importRequest(one.url)).also { firstTask = it.taskId }
                    val second = runtime.controller.submit(importRequest(two.url)).also { secondTask = it.taskId }
                    awaitBothRunning(runtime.controller, first.taskId, second.taskId)
                    val starter = ForegroundDutyStarter(app, app)
                    val firstStart = starter.startDataSync(first.taskId)
                    check(firstStart is ForegroundStartResult.Started) {
                        "First foreground duty for ${first.taskId.value} was $firstStart, expected Started"
                    }
                    firstDuty = firstStart.reference
                    val secondStart = starter.startDataSync(second.taskId)
                    check(secondStart is ForegroundStartResult.Started) {
                        "Second foreground duty for ${second.taskId.value} was $secondStart, expected Started"
                    }
                    secondDuty = secondStart.reference
                    await {
                        val tags = notifications.activeNotifications.mapNotNull { it.tag }.toSet()
                        first.taskId.value in tags && second.taskId.value in tags
                    }
                    val before = runtime.controller.snapshot(second.taskId)!!.cursor.value
                    val closed = java.util.concurrent.atomic.AtomicReference<android.app.Activity?>()
                    instrumentation.runOnMainSync {
                        val activities = ActivityLifecycleMonitorRegistry.getInstance().getActivitiesInStage(Stage.RESUMED)
                            .filter { it.javaClass.name == "com.deepstudent.app.MainActivity" }
                        assertEquals(1, activities.size)
                        closed.set(activities.single())
                        activities.single().finishAndRemoveTask()
                    }
                    await { checkNotNull(closed.get()).isDestroyed }
                    await { runtime.controller.snapshot(second.taskId)!!.cursor.value > before }
                    val notice = notifications.activeNotifications.first { it.tag == first.taskId.value }
                    notice.notification.actions.single().actionIntent.send()
                    await { runtime.controller.snapshot(first.taskId)?.state == RuntimeState.CANCELLED }
                    assertEquals(RuntimeState.RUNNING, runtime.controller.snapshot(second.taskId)?.state)
                    runtime.supervisor.requestCheckpoint(second.taskId, 1)
                    assertTrue(runtime.controller.snapshot(second.taskId)?.execution?.checkpoint != null)
                    two.complete()
                    await { runtime.controller.snapshot(second.taskId)?.state == RuntimeState.COMPLETED }
                    await { app.foregroundDuties.duties.value.isEmpty() && notifications.activeNotifications.none { it.id == 7410 } }
                    @Suppress("DEPRECATION")
                    val services = app.getSystemService(android.app.ActivityManager::class.java).getRunningServices(100)
                    assertTrue(services.none { it.service.className == "com.deepstudent.platform.background.RuntimeForegroundService" })
                } catch (failure: Throwable) {
                    testFailure = failure
                    throw failure
                } finally {
                    // Submission and either start may fail; clean every resource already acquired.
                    withContext(NonCancellable) {
                        val cleanupFailures = mutableListOf<Throwable>()
                        listOfNotNull(firstDuty, secondDuty).forEach { reference ->
                            runCatching { reference.close() }.onFailure { cleanupFailures += it }
                        }
                        val tasks = listOfNotNull(firstTask, secondTask)
                        tasks.forEach { taskId ->
                            runCatching { runtime.controller.cancel(taskId) }.onFailure { cleanupFailures += it }
                        }
                        var remaining = emptyList<String>()
                        runCatching {
                            val stopped = withTimeoutOrNull(5_000) {
                                while (true) {
                                    remaining = tasks.mapNotNull { taskId ->
                                        runtime.controller.snapshot(taskId)?.takeUnless { it.state.terminal }
                                            ?.let { "${taskId.value}=${it.state}" }
                                    }
                                    if (remaining.isEmpty()) break
                                    delay(25)
                                }
                                true
                            } == true
                            check(stopped) { "Foreground fixture tasks did not terminate during cleanup: $remaining" }
                        }.onFailure { cleanupFailures += it }
                        if (cleanupFailures.isNotEmpty()) {
                            val original = testFailure
                            if (original != null) cleanupFailures.forEach(original::addSuppressed)
                            else throw AssertionError("Foreground fixture cleanup failed", cleanupFailures.first()).apply {
                                cleanupFailures.drop(1).forEach(::addSuppressed)
                            }
                        }
                    }
                }
            }
        }
    }

    private fun importRequest(url: String) = TaskSubmission(MainRuntimeResources.NOTE_IMPORT,
        JsonValue.obj("url" to JsonValue.text(url), "title" to JsonValue.text("FGS import fixture")))

    private suspend fun awaitBothRunning(query: RuntimeTaskQuery, firstId: TaskId, secondId: TaskId) {
        var first: RuntimeSnapshot? = null
        var second: RuntimeSnapshot? = null
        fun details() = "${firstId.value}=${describe(first)}, ${secondId.value}=${describe(second)}"
        val ready = withTimeoutOrNull(15_000) {
            while (true) {
                first = query.snapshot(firstId)
                second = query.snapshot(secondId)
                check(first?.state?.terminal != true && second?.state?.terminal != true) {
                    "A foreground fixture task terminated before both were RUNNING: ${details()}"
                }
                if (first?.state == RuntimeState.RUNNING && second?.state == RuntimeState.RUNNING) break
                delay(25)
            }
            true
        } == true
        check(ready) { "Timed out waiting for both foreground fixture tasks to be RUNNING: ${details()}" }
    }

    private fun describe(snapshot: RuntimeSnapshot?): String = snapshot?.let {
        "${it.state}(generation=${it.ownership.generation}, owner=${it.ownership.ownerId}, error=${it.execution.error?.code})"
    } ?: "missing"

    private suspend fun await(predicate: suspend () -> Boolean) = withTimeout(15000) {
        while (!predicate()) delay(25)
    }

    private fun shell(command: String) {
        ParcelFileDescriptor.AutoCloseInputStream(instrumentation.uiAutomation.executeShellCommand(command)).use {
            it.readBytes()
        }
    }
}
