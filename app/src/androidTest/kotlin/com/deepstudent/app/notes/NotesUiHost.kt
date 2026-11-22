package com.deepstudent.app.notes

import android.app.Activity
import android.app.Application
import android.os.Bundle
import android.os.ParcelFileDescriptor
import androidx.activity.compose.setContent
import androidx.compose.runtime.Composable
import androidx.test.platform.app.InstrumentationRegistry
import com.deepstudent.app.MainActivity
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicReference

internal class NotesUiHost : AutoCloseable {
    private val instrumentation = InstrumentationRegistry.getInstrumentation()
    private val application = instrumentation.targetContext.applicationContext as Application
    private val resumed = CountDownLatch(1)
    private val activity = AtomicReference<MainActivity?>()
    private var descriptor: ParcelFileDescriptor? = null
    private val callbacks = object : Application.ActivityLifecycleCallbacks {
        override fun onActivityResumed(value: Activity) {
            if (value is MainActivity) { activity.set(value); resumed.countDown() }
        }
        override fun onActivityCreated(value: Activity, state: Bundle?) = Unit
        override fun onActivityStarted(value: Activity) = Unit
        override fun onActivityPaused(value: Activity) = Unit
        override fun onActivityStopped(value: Activity) = Unit
        override fun onActivitySaveInstanceState(value: Activity, state: Bundle) = Unit
        override fun onActivityDestroyed(value: Activity) = Unit
    }

    fun start() {
        instrumentation.runOnMainSync { application.registerActivityLifecycleCallbacks(callbacks) }
        val component = "${instrumentation.targetContext.packageName}/${MainActivity::class.java.name}"
        val handle = instrumentation.uiAutomation.executeShellCommand("am start -W --activity-clear-task -n $component")
        descriptor = handle
        Thread({ runCatching { ParcelFileDescriptor.AutoCloseInputStream(handle).use { it.readBytes() } } }).apply {
            isDaemon = true
            start()
        }
        check(resumed.await(20, TimeUnit.SECONDS)) { "MainActivity did not resume" }
    }

    fun setContent(content: @Composable () -> Unit) {
        instrumentation.runOnMainSync { checkNotNull(activity.get()).setContent(content = content) }
    }

    override fun close() {
        instrumentation.runOnMainSync {
            application.unregisterActivityLifecycleCallbacks(callbacks)
            activity.getAndSet(null)?.let { if (!it.isFinishing && !it.isDestroyed) it.finishAndRemoveTask() }
        }
        runCatching { descriptor?.close() }
    }
}
