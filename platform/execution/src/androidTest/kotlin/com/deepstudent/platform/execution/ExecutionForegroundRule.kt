package com.deepstudent.platform.execution

import android.app.Activity
import android.app.Application
import android.os.Bundle
import android.os.ParcelFileDescriptor
import android.view.WindowManager
import android.widget.TextView
import android.widget.Button
import android.widget.LinearLayout
import com.deepstudent.services.execution.api.ExecutionSystem
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch
import androidx.test.platform.app.InstrumentationRegistry
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicReference
import org.junit.rules.ExternalResource

/** Only in the test APK. The user must press the button to open the official dialog. */
class ExecutionTestActivity : Activity() {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private lateinit var system: ExecutionSystem

    override fun onCreate(state: Bundle?) {
        super.onCreate(state)
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        system = ShizukuExecutionFactory.create(this)
        val status = TextView(this)
        val request = Button(this).apply {
            text = "请求 Shizuku 授权（由用户决定）"
            isEnabled = false
            setOnClickListener {
                isEnabled = false
                // This is the only permission trigger: never auto-grant or mutate flags.
                system.requestPermission { scope.launch { system.refresh() } }
            }
        }
        val refresh = Button(this).apply {
            text = "刷新 Shizuku 状态"
            setOnClickListener { scope.launch { system.refresh() } }
        }
        setContentView(LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            addView(TextView(this@ExecutionTestActivity).apply { text = "S1.22 前台设备测试 · 不自动授权" })
            addView(status)
            addView(request)
            addView(refresh)
        })
        system.start()
        scope.launch {
            system.status.collect {
                val snapshot = it
                runOnUiThread {
                    status.text = "状态 ${snapshot.state} · 后端 UID ${snapshot.backendUid} · 实际 UID ${snapshot.observedUid}"
                    request.isEnabled = snapshot.canRequestPermission
                }
            }
        }
    }

    override fun onDestroy() {
        system.close()
        scope.cancel()
        super.onDestroy()
    }
}

/** Keep this OEM's instrumentation process foreground instead of allowing it to be frozen. */
class ExecutionForegroundRule : ExternalResource() {
    private val instrument = InstrumentationRegistry.getInstrumentation()
    private val app = instrument.targetContext.applicationContext as Application
    private val ready = CountDownLatch(1)
    private val activity = AtomicReference<ExecutionTestActivity?>()
    private var descriptor: ParcelFileDescriptor? = null
    private var reader: Thread? = null
    private val callbacks = object : Application.ActivityLifecycleCallbacks {
        override fun onActivityResumed(value: Activity) {
            if (value is ExecutionTestActivity) {
                activity.set(value)
                ready.countDown()
            }
        }
        override fun onActivityCreated(value: Activity, state: Bundle?) = Unit
        override fun onActivityStarted(value: Activity) = Unit
        override fun onActivityPaused(value: Activity) = Unit
        override fun onActivityStopped(value: Activity) = Unit
        override fun onActivitySaveInstanceState(value: Activity, state: Bundle) = Unit
        override fun onActivityDestroyed(value: Activity) = Unit
    }

    override fun before() {
        try {
            instrument.runOnMainSync { app.registerActivityLifecycleCallbacks(callbacks) }
            val component = "${instrument.targetContext.packageName}/${ExecutionTestActivity::class.java.name}"
            val handle = instrument.uiAutomation.executeShellCommand("am start -W --activity-clear-task -n $component")
            descriptor = handle
            reader = Thread {
                runCatching { ParcelFileDescriptor.AutoCloseInputStream(handle).use { it.readBytes() } }
            }.apply { isDaemon = true; start() }
            check(ready.await(20, TimeUnit.SECONDS)) { "S1.22 foreground Activity did not resume" }
            reader?.join(2_000)
            check(reader?.isAlive == false) { "S1.22 foreground launch command did not finish" }
        } catch (failure: Throwable) {
            after()
            throw failure
        }
    }

    override fun after() {
        instrument.runOnMainSync {
            app.unregisterActivityLifecycleCallbacks(callbacks)
            activity.getAndSet(null)?.finishAndRemoveTask()
        }
        runCatching { descriptor?.close() }
        reader?.join(2_000)
    }
}
