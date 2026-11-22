package com.deepstudent.design

import android.app.Activity
import android.app.Application
import android.os.Bundle
import android.os.ParcelFileDescriptor
import android.util.Log
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.runtime.Composable
import androidx.test.platform.app.InstrumentationRegistry
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicReference

class DstuTestActivity : ComponentActivity()

internal class DstuTestHost {
    private val instrumentation = InstrumentationRegistry.getInstrumentation()
    private val application = instrumentation.targetContext.applicationContext as Application
    private val resumed = CountDownLatch(1)
    private val activity = AtomicReference<DstuTestActivity?>()
    private var registered = false
    private var descriptor: ParcelFileDescriptor? = null

    private val callback = object : Application.ActivityLifecycleCallbacks {
        override fun onActivityResumed(activity: Activity) {
            if (activity !is DstuTestActivity) return
            this@DstuTestHost.activity.set(activity)
            Log.i(TAG, "stage=activity.resumed instance=${System.identityHashCode(activity)}")
            resumed.countDown()
        }

        override fun onActivityCreated(activity: Activity, savedInstanceState: Bundle?) = Unit
        override fun onActivityStarted(activity: Activity) = Unit
        override fun onActivityPaused(activity: Activity) = Unit
        override fun onActivityStopped(activity: Activity) = Unit
        override fun onActivitySaveInstanceState(activity: Activity, outState: Bundle) = Unit
        override fun onActivityDestroyed(activity: Activity) = Unit
    }

    fun start() {
        Log.i(TAG, "stage=host.register")
        instrumentation.runOnMainSync { application.registerActivityLifecycleCallbacks(callback) }
        registered = true
        val component = "${instrumentation.targetContext.packageName}/${DstuTestActivity::class.java.name}"
        Log.i(TAG, "stage=host.launch component=$component")
        val handle = instrumentation.uiAutomation.executeShellCommand("am start -W --activity-clear-task -n $component")
        descriptor = handle
        Thread({ readOutput(handle) }, "s111-shell-start").apply { isDaemon = true }.start()
        check(resumed.await(20, TimeUnit.SECONDS)) { "Timed out waiting for DstuTestActivity RESUMED; see $TAG" }
    }

    fun setContent(content: @Composable () -> Unit) {
        val host = checkNotNull(activity.get()) { "DstuTestActivity not started" }
        instrumentation.runOnMainSync { host.setContent(content = content) }
    }

    fun close() {
        Log.i(TAG, "stage=host.close")
        if (registered) instrumentation.runOnMainSync { application.unregisterActivityLifecycleCallbacks(callback) }
        registered = false
        activity.getAndSet(null)?.let { host ->
            instrumentation.runOnMainSync { if (!host.isFinishing && !host.isDestroyed) host.finishAndRemoveTask() }
        }
        runCatching { descriptor?.close() }
        descriptor = null
    }

    private fun readOutput(handle: ParcelFileDescriptor) {
        val result = runCatching {
            ParcelFileDescriptor.AutoCloseInputStream(handle).bufferedReader().use { it.readText() }
        }.getOrElse { "read failed: ${it.javaClass.simpleName}" }
        Log.i(TAG, "stage=shell.output result=${result.trim().replace('\n', ' ').take(2000)}")
    }

    private companion object { const val TAG = "DstuS111" }
}
