package com.deepstudent.features.environments.data

import android.app.Activity
import android.app.Application
import android.os.Bundle
import android.os.ParcelFileDescriptor
import androidx.test.platform.app.InstrumentationRegistry
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicReference

class EnvironmentTestActivity : Activity()

internal class EnvironmentForegroundHost {
    private val instrument = InstrumentationRegistry.getInstrumentation()
    private val app = instrument.targetContext.applicationContext as Application
    private val ready = CountDownLatch(1)
    private val activity = AtomicReference<EnvironmentTestActivity?>()
    private val callbacks = object : Application.ActivityLifecycleCallbacks {
        override fun onActivityResumed(value: Activity) {
            if (value is EnvironmentTestActivity) {
                activity.set(value)
                ready.countDown()
            }
        }

        override fun onActivityCreated(value: Activity, state: Bundle?) = Unit
        override fun onActivityStarted(value: Activity) = Unit
        override fun onActivityStopped(value: Activity) = Unit
        override fun onActivityPaused(value: Activity) = Unit
        override fun onActivitySaveInstanceState(value: Activity, state: Bundle) = Unit
        override fun onActivityDestroyed(value: Activity) = Unit
    }

    fun start() {
        instrument.runOnMainSync { app.registerActivityLifecycleCallbacks(callbacks) }
        val component = "${instrument.targetContext.packageName}/${EnvironmentTestActivity::class.java.name}"
        val handle = instrument.uiAutomation.executeShellCommand("am start -W --activity-clear-task -n $component")
        Thread { ParcelFileDescriptor.AutoCloseInputStream(handle).use { it.readBytes() } }
            .apply { isDaemon = true; start() }
        check(ready.await(20, TimeUnit.SECONDS)) { "environment foreground host unavailable" }
    }

    fun close() {
        instrument.runOnMainSync {
            app.unregisterActivityLifecycleCallbacks(callbacks)
            activity.getAndSet(null)?.finishAndRemoveTask()
        }
    }
}
