package com.deepstudent.app.editor

import android.app.Activity
import android.app.Application
import android.os.Bundle
import android.os.ParcelFileDescriptor
import android.util.Log
import androidx.activity.compose.setContent
import androidx.compose.runtime.Composable
import androidx.test.platform.app.InstrumentationRegistry
import com.deepstudent.app.MainActivity
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicReference

internal class EditorTestHost {
    private val instrumentation = InstrumentationRegistry.getInstrumentation()
    private val application = instrumentation.targetContext.applicationContext as Application
    private val resumed = CountDownLatch(1)
    private val reference = AtomicReference<MainActivity?>()
    private var descriptor: ParcelFileDescriptor? = null
    val activity: MainActivity get() = checkNotNull(reference.get())

    private val callbacks = object : Application.ActivityLifecycleCallbacks {
        override fun onActivityResumed(activity: Activity) {
            if (activity is MainActivity) { reference.set(activity); resumed.countDown() }
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
        val component = "${instrumentation.targetContext.packageName}/${MainActivity::class.java.name}"
        val handle = instrumentation.uiAutomation.executeShellCommand("am start -W --activity-clear-task -n $component")
        descriptor = handle
        Thread({
            val output = runCatching { ParcelFileDescriptor.AutoCloseInputStream(handle).bufferedReader().use { it.readText() } }
            Log.i("DstuS114", "host.start ${output.getOrNull()?.trim()?.take(300)}")
        }, "s114-shell-start").apply { isDaemon = true }.start()
        check(resumed.await(20, TimeUnit.SECONDS)) { "MainActivity did not resume" }
    }

    fun setContent(content: @Composable () -> Unit) {
        instrumentation.runOnMainSync { activity.setContent(content = content) }
    }

    fun close() {
        instrumentation.runOnMainSync {
            application.unregisterActivityLifecycleCallbacks(callbacks)
            reference.getAndSet(null)?.let { if (!it.isFinishing && !it.isDestroyed) it.finishAndRemoveTask() }
        }
        runCatching { descriptor?.close() }
    }
}
