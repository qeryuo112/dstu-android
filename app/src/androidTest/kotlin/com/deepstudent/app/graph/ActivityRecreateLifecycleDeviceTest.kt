package com.deepstudent.app.graph

import android.app.Activity
import android.app.Application
import android.content.Context
import android.os.Bundle
import android.os.ParcelFileDescriptor
import android.util.Log
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.deepstudent.app.DstuApplication
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicReference
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotSame
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

/**
 * Device-only recreate acceptance. The activity is started by the system shell
 * because API 36 instrumentation START stalled before creating MainActivity.
 */
@RunWith(AndroidJUnit4::class)
class ActivityRecreateLifecycleDeviceTest {
    private val instrumentation = InstrumentationRegistry.getInstrumentation()
    private val context: Context get() = instrumentation.targetContext
    private val application: DstuApplication
        get() = context.applicationContext as DstuApplication

    @Test(timeout = 60_000)
    fun mainActivityRecreateKeepsApplicationGraph() {
        val before = application.mainGraph
        val session = MainActivityLifecycleSession()
        try {
            session.start()
            val first = session.awaitFirst()
            assertSame(before, DstuApplication.from(first).mainGraph)

            session.recreate(first)
            val second = session.awaitRecreated(first)

            assertNotSame(first, second)
            assertEquals(MAIN_ACTIVITY_CLASS, second.javaClass.name)
            assertSame(before, DstuApplication.from(second).mainGraph)
            assertEquals(before.graphId, DstuApplication.from(second).mainGraph.graphId)
        } finally {
            session.close()
        }

        assertSame(before, application.mainGraph)
        assertSame(before, application.mainGraph)
    }

    private inner class MainActivityLifecycleSession {
        private val firstLatch = CountDownLatch(1)
        private val recreatedLatch = CountDownLatch(1)
        private val firstActivity = AtomicReference<Activity?>()
        private val recreatedActivity = AtomicReference<Activity?>()
        private val recreateDispatched = AtomicBoolean(false)
        private var callbackRegistered = false
        private var shellDescriptor: ParcelFileDescriptor? = null

        private val lifecycleCallback = object : Application.ActivityLifecycleCallbacks {
            override fun onActivityResumed(activity: Activity) {
                if (!isMainActivity(activity)) return
                val first = firstActivity.get()
                if (first == null) {
                    if (firstActivity.compareAndSet(null, activity)) {
                        stage(
                            "activity.first.resumed",
                            mapOf(
                                "class" to activity.javaClass.name,
                                "instance" to System.identityHashCode(activity),
                            ),
                        )
                        firstLatch.countDown()
                    }
                    return
                }
                if (recreateDispatched.get() && first !== activity &&
                    recreatedActivity.compareAndSet(null, activity)
                ) {
                    stage(
                        "activity.second.resumed",
                        mapOf(
                            "class" to activity.javaClass.name,
                            "instance" to System.identityHashCode(activity),
                        ),
                    )
                    recreatedLatch.countDown()
                }
            }

            override fun onActivityCreated(activity: Activity, savedInstanceState: Bundle?) = Unit

            override fun onActivityStarted(activity: Activity) = Unit

            override fun onActivityPaused(activity: Activity) = Unit

            override fun onActivityStopped(activity: Activity) = Unit

            override fun onActivitySaveInstanceState(activity: Activity, outState: Bundle) = Unit

            override fun onActivityDestroyed(activity: Activity) = Unit
        }

        fun start() {
            stage("lifecycle.register")
            instrumentation.runOnMainSync {
                application.registerActivityLifecycleCallbacks(lifecycleCallback)
            }
            callbackRegistered = true

            val component = "${context.packageName}/$MAIN_ACTIVITY_CLASS"
            val command =
                "am start -W --activity-clear-task -n $component"
            stage("shell.start", mapOf("command" to command))
            val descriptor = instrumentation.uiAutomation.executeShellCommand(command)
            shellDescriptor = descriptor
            readShellOutput(descriptor, command)
        }

        fun awaitFirst(timeoutMs: Long = ACTIVITY_TIMEOUT_MS): Activity {
            stage("lifecycle.await.first")
            await(firstLatch, timeoutMs, "first MainActivity RESUMED")
            return checkNotNull(firstActivity.get()) {
                "MainActivity resumed callback had no instance"
            }
        }

        fun recreate(activity: Activity) {
            recreateDispatched.set(true)
            stage("recreate.dispatch", mapOf("instance" to System.identityHashCode(activity)))
            instrumentation.runOnMainSync { activity.recreate() }
        }

        fun awaitRecreated(
            previous: Activity,
            timeoutMs: Long = ACTIVITY_TIMEOUT_MS,
        ): Activity {
            stage("lifecycle.await.recreated")
            await(recreatedLatch, timeoutMs, "recreated MainActivity RESUMED")
            val activity = checkNotNull(recreatedActivity.get()) {
                "Recreated MainActivity callback had no instance"
            }
            assertNotSame(previous, activity)
            return activity
        }

        fun close() {
            stage("cleanup.begin")
            if (callbackRegistered) {
                runCatching {
                    instrumentation.runOnMainSync {
                        application.unregisterActivityLifecycleCallbacks(lifecycleCallback)
                    }
                }
                callbackRegistered = false
            }
            val activity = recreatedActivity.get() ?: firstActivity.get()
            if (activity != null) {
                runCatching {
                    instrumentation.runOnMainSync {
                        if (!activity.isFinishing && !activity.isDestroyed) {
                            activity.finishAndRemoveTask()
                        }
                    }
                }
            }
            runCatching { shellDescriptor?.close() }
            shellDescriptor = null
            stage("cleanup.done")
        }

        private fun readShellOutput(descriptor: ParcelFileDescriptor, command: String) {
            Thread({
                val output = runCatching {
                    ParcelFileDescriptor.AutoCloseInputStream(descriptor)
                        .bufferedReader()
                        .use { it.readText() }
                }.fold(
                    onSuccess = { it.trim().replace('\n', ' ').take(MAX_SHELL_OUTPUT) },
                    onFailure = { "read.failed class=${it.javaClass.name}" },
                )
                stage("shell.output", mapOf("command" to command, "output" to output))
            }, "s102-shell-start").apply { isDaemon = true }.start()
        }

        private fun await(latch: CountDownLatch, timeoutMs: Long, description: String) {
            assertTrue(
                "Timed out after ${timeoutMs}ms waiting for $description; see DstuS102 stages",
                latch.await(timeoutMs, TimeUnit.MILLISECONDS),
            )
        }
    }

    private fun stage(name: String, fields: Map<String, Any?> = emptyMap()) {
        val rendered = fields.entries.joinToString(" ") { "${it.key}=${it.value}" }
        Log.i(TAG, if (rendered.isEmpty()) "stage=$name" else "stage=$name $rendered")
        val status = Bundle().apply {
            putString("stage", name)
            fields.forEach { (key, value) -> putString(key, value?.toString()) }
        }
        runCatching { instrumentation.sendStatus(2, status) }
    }

    private fun isMainActivity(activity: Activity): Boolean =
        activity.javaClass.name == MAIN_ACTIVITY_CLASS

    private companion object {
        private const val TAG = "DstuS102"
        private const val ACTIVITY_TIMEOUT_MS = 20_000L
        private const val MAX_SHELL_OUTPUT = 2_000
        private const val MAIN_ACTIVITY_CLASS = "com.deepstudent.app.MainActivity"
    }
}
