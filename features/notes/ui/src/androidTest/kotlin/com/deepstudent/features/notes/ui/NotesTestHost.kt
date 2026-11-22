package com.deepstudent.features.notes.ui

import android.app.Activity
import android.app.Application
import android.content.pm.ActivityInfo
import android.content.res.Configuration
import android.os.Bundle
import android.os.ParcelFileDescriptor
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.runtime.Composable
import androidx.test.platform.app.InstrumentationRegistry
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicReference

class NotesTestActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        content?.let { setContent(content = it) }
    }

    companion object { var content: (@Composable () -> Unit)? = null }
}

internal class NotesTestHost {
    private val instrumentation = InstrumentationRegistry.getInstrumentation()
    private val application = instrumentation.targetContext.applicationContext as Application
    @Volatile private var resumed = CountDownLatch(1)
    private val previous = AtomicReference<NotesTestActivity?>()
    private val activity = AtomicReference<NotesTestActivity?>()
    private var descriptor: ParcelFileDescriptor? = null
    private val callbacks = object : Application.ActivityLifecycleCallbacks {
        override fun onActivityResumed(activity: Activity) {
            if (activity is NotesTestActivity && activity !== previous.get()) {
                this@NotesTestHost.activity.set(activity)
                resumed.countDown()
            }
        }
        override fun onActivityCreated(activity: Activity, state: Bundle?) = Unit
        override fun onActivityStarted(activity: Activity) = Unit
        override fun onActivityPaused(activity: Activity) = Unit
        override fun onActivityStopped(activity: Activity) = Unit
        override fun onActivitySaveInstanceState(activity: Activity, state: Bundle) = Unit
        override fun onActivityDestroyed(activity: Activity) = Unit
    }

    fun start() {
        instrumentation.runOnMainSync { application.registerActivityLifecycleCallbacks(callbacks) }
        val component = "${instrumentation.targetContext.packageName}/${NotesTestActivity::class.java.name}"
        val handle = instrumentation.uiAutomation.executeShellCommand("am start -W --activity-clear-task -n $component")
        descriptor = handle
        Thread({ runCatching { ParcelFileDescriptor.AutoCloseInputStream(handle).use { it.readBytes() } } }).apply {
            isDaemon = true
            start()
        }
        check(resumed.await(20, TimeUnit.SECONDS)) { "Timed out waiting for NotesTestActivity" }
    }

    fun setContent(content: @Composable () -> Unit) {
        val host = checkNotNull(activity.get())
        instrumentation.runOnMainSync {
            NotesTestActivity.content = content
            host.setContent(content = content)
        }
    }

    fun recreate() {
        val host = checkNotNull(activity.get())
        previous.set(host)
        resumed = CountDownLatch(1)
        instrumentation.runOnMainSync { host.recreate() }
        check(resumed.await(20, TimeUnit.SECONDS)) { "Timed out waiting for recreated NotesTestActivity" }
    }

    fun rotate() {
        val host = checkNotNull(activity.get())
        previous.set(host)
        resumed = CountDownLatch(1)
        instrumentation.runOnMainSync {
            host.requestedOrientation = if (host.resources.configuration.orientation == Configuration.ORIENTATION_LANDSCAPE) {
                ActivityInfo.SCREEN_ORIENTATION_PORTRAIT
            } else {
                ActivityInfo.SCREEN_ORIENTATION_LANDSCAPE
            }
        }
        check(resumed.await(20, TimeUnit.SECONDS)) { "Timed out waiting for rotated NotesTestActivity" }
    }

    fun close() {
        instrumentation.runOnMainSync { application.unregisterActivityLifecycleCallbacks(callbacks) }
        activity.getAndSet(null)?.let { host ->
            instrumentation.runOnMainSync { if (!host.isFinishing && !host.isDestroyed) host.finishAndRemoveTask() }
        }
        instrumentation.runOnMainSync { NotesTestActivity.content = null }
        runCatching { descriptor?.close() }
    }
}
