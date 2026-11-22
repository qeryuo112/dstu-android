package com.deepstudent.features.browser.data

import android.app.Activity
import android.app.Application
import android.os.Bundle
import android.os.ParcelFileDescriptor
import android.view.View
import androidx.test.platform.app.InstrumentationRegistry
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicReference

class BrowserWebTestActivity : Activity()

internal class BrowserWebTestHost {
    private val instrument = InstrumentationRegistry.getInstrumentation()
    private val app = instrument.targetContext.applicationContext as Application
    private val ready = CountDownLatch(1)
    private val current = AtomicReference<BrowserWebTestActivity>()
    private var descriptor: ParcelFileDescriptor? = null
    val activity: BrowserWebTestActivity get() = current.get()
    private val callbacks = object : Application.ActivityLifecycleCallbacks {
        override fun onActivityResumed(value: Activity) { if (value is BrowserWebTestActivity) { current.set(value); ready.countDown() } }
        override fun onActivityCreated(value: Activity, state: Bundle?) = Unit
        override fun onActivityStarted(value: Activity) = Unit
        override fun onActivityPaused(value: Activity) = Unit
        override fun onActivityStopped(value: Activity) = Unit
        override fun onActivitySaveInstanceState(value: Activity, state: Bundle) = Unit
        override fun onActivityDestroyed(value: Activity) = Unit
    }
    fun start() {
        instrument.runOnMainSync { app.registerActivityLifecycleCallbacks(callbacks) }
        val component = "${instrument.targetContext.packageName}/${BrowserWebTestActivity::class.java.name}"
        val handle = instrument.uiAutomation.executeShellCommand("am start -W --activity-clear-task -n $component")
        descriptor = handle
        Thread({ runCatching { ParcelFileDescriptor.AutoCloseInputStream(handle).use { it.readBytes() } } }).apply { isDaemon = true; start() }
        check(ready.await(20, TimeUnit.SECONDS))
    }
    fun attach(view: View) = activity.setContentView(view)
    fun close() {
        instrument.runOnMainSync { app.unregisterActivityLifecycleCallbacks(callbacks); current.get()?.finishAndRemoveTask() }
        runCatching { descriptor?.close() }
    }
}
