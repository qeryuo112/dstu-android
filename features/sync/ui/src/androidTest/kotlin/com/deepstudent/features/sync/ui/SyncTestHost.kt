package com.deepstudent.features.sync.ui

import android.app.Activity
import android.app.Application
import android.os.ParcelFileDescriptor
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.runtime.Composable
import androidx.test.platform.app.InstrumentationRegistry
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicReference

class SyncTestActivity : ComponentActivity() {
    override fun onCreate(state: Bundle?) {
        super.onCreate(state)
        window.addFlags(android.view.WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
    }
}

internal class SyncTestHost {
    private val instrumentation = InstrumentationRegistry.getInstrumentation()
    private val app = instrumentation.context.applicationContext as Application
    private val activity = AtomicReference<SyncTestActivity>()
    private val ready = CountDownLatch(1)
    private var descriptor: ParcelFileDescriptor? = null
    private val callbacks = object : Application.ActivityLifecycleCallbacks {
        override fun onActivityResumed(value: Activity) {
            if (value is SyncTestActivity) { activity.set(value); ready.countDown() }
        }
        override fun onActivityCreated(value: Activity, state: Bundle?) = Unit
        override fun onActivityStarted(value: Activity) = Unit
        override fun onActivityPaused(value: Activity) = Unit
        override fun onActivityStopped(value: Activity) = Unit
        override fun onActivitySaveInstanceState(value: Activity, state: Bundle) = Unit
        override fun onActivityDestroyed(value: Activity) = Unit
    }

    fun start() {
        instrumentation.runOnMainSync { app.registerActivityLifecycleCallbacks(callbacks) }
        val component = "${instrumentation.context.packageName}/${SyncTestActivity::class.java.name}"
        val handle = instrumentation.uiAutomation.executeShellCommand("am start -W --activity-clear-task -n $component")
        descriptor = handle
        Thread {
            runCatching { ParcelFileDescriptor.AutoCloseInputStream(handle).use { it.readBytes() } }
        }.apply { isDaemon = true; start() }
        check(ready.await(20, TimeUnit.SECONDS)) { "Sync test Activity did not resume" }
    }

    fun setContent(content: @Composable () -> Unit) {
        instrumentation.runOnMainSync { activity.get().setContent(content = content) }
    }

    fun close() {
        instrumentation.runOnMainSync {
            app.unregisterActivityLifecycleCallbacks(callbacks)
            activity.get()?.finishAndRemoveTask()
        }
        runCatching { descriptor?.close() }
    }
}
