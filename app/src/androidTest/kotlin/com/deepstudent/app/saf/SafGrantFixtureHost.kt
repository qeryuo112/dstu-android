package com.deepstudent.app.saf

import android.accessibilityservice.AccessibilityService
import android.app.Activity
import android.app.Application
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.os.ParcelFileDescriptor
import android.os.SystemClock
import android.util.Log
import androidx.activity.ComponentActivity
import androidx.activity.result.ActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.test.platform.app.InstrumentationRegistry
import java.util.UUID
import java.util.concurrent.Callable
import java.util.concurrent.CountDownLatch
import java.util.concurrent.ExecutionException
import java.util.concurrent.FutureTask
import java.util.concurrent.TimeUnit
import java.util.concurrent.TimeoutException
import java.util.concurrent.atomic.AtomicReference

/** A debug-only result owner in the actual App UID, without ActivityScenario/global idle waits. */
internal class FixtureHost : AutoCloseable {
    private val instrumentation = InstrumentationRegistry.getInstrumentation()
    private val app = instrumentation.targetContext.applicationContext as Application
    private val main = Handler(Looper.getMainLooper())
    private val correlationId = UUID.randomUUID().toString()
    private val begin = SystemClock.elapsedRealtime()
    private val resumed = CountDownLatch(1)
    private val destroyed = CountDownLatch(1)
    private val activity = AtomicReference<ComponentActivity>()
    private val callbacks = HostLifecycle()

    init {
        try {
            trace("host.callbacks", "registering")
            onMain("host.callbacks") { app.registerActivityLifecycleCallbacks(callbacks) }
            trace("host.start", "requested")
            startForegroundHost()
            await(resumed, "host.resume", 12)
            trace("host.resume", "observed")
        } catch (failure: Throwable) {
            trace("host.start", "failed")
            keepFailure(failure, ::close)
            throw failure
        }
    }

    // V2232A can freeze the instrumented process when it tries to start a task from the
    // background. Attribute just the debug host launch to adb shell. Both result requests
    // and all URI access still originate in the foreground App UID, not shell.
    private fun startForegroundHost() {
        val command = "am start -W --user 0 -n ${app.packageName}/$HOST_CLASS --es $HOST_ID $correlationId"
        val pipe = instrumentation.uiAutomation.executeShellCommand(command)
        trace("host.start", "dispatched")
        val response = FutureTask(Callable {
            ParcelFileDescriptor.AutoCloseInputStream(pipe).bufferedReader().use { it.readText() }
        })
        Thread(response, "s207-shell-start").apply { isDaemon = true }.start()
        try {
            val lines = response.get(8, TimeUnit.SECONDS).lineSequence().map(String::trim)
            check(lines.any { it == "Status: ok" }) { "S2.07 shell did not start the debug host" }
            trace("host.start", "shell_ok")
        } finally {
            if (response.cancel(true)) runCatching { pipe.close() }
        }
    }

    fun fixture(operation: String): ActivityResult = launchResult(
        Intent().setClassName(S207.PACKAGE, S207.FIXTURE_ACTIVITY).putExtra("s207.operation", operation),
        "fixture.$operation",
        null,
    )

    fun launch(intent: Intent, interact: (() -> Unit)?): ActivityResult =
        launchResult(intent, SYSTEM_PICKER_RESULT, interact, if (interact == null) 65L else 12L)

    private fun launchResult(
        intent: Intent, phase: String, interact: (() -> Unit)?, resultTimeoutSeconds: Long = 12,
    ): ActivityResult {
        trace(phase, "registering")
        val receiver = CountDownLatch(1)
        val result = AtomicReference<ActivityResult>()
        val launcher = onMain(phase) {
            checkNotNull(activity.get()).activityResultRegistry.register(
                UUID.randomUUID().toString(), ActivityResultContracts.StartActivityForResult(),
            ) { value -> result.set(value); receiver.countDown() }
        }
        var original: Throwable? = null
        try {
            trace(phase, "requested")
            onMain(phase) { launcher.launch(intent) }
            trace(phase, "dispatched")
            interact?.invoke()
            trace(phase, "waiting_result")
            await(receiver, phase, resultTimeoutSeconds)
            return checkNotNull(result.get()).also {
                trace(phase, if (it.resultCode == Activity.RESULT_OK) "received" else "cancelled")
                check(it.resultCode == Activity.RESULT_OK) { "$phase was cancelled" }
            }
        } catch (failure: Throwable) {
            original = failure
            trace(phase, "failed")
            if (phase == SYSTEM_PICKER_RESULT) keepFailure(failure, ::dismissOnlyDocumentsUi)
            throw failure
        } finally {
            trace(phase, "unregistering")
            val unregister = { onMain(phase) { launcher.unregister() } }
            if (original == null) unregister() else keepFailure(original, unregister)
        }
    }

    override fun close() {
        trace("host.close", "requested")
        try {
            if (activity.get() != null) {
                onMain("host.close") { activity.get()?.finishAndRemoveTask() }
                await(destroyed, "host.close", 8)
                trace("host.close", "destroyed")
            }
        } finally {
            trace("host.callbacks", "unregistering")
            onMain("host.callbacks") { app.unregisterActivityLifecycleCallbacks(callbacks) }
        }
        trace("host.close", "complete")
    }

    private fun dismissOnlyDocumentsUi() {
        trace("system_picker.dismiss", "requested")
        val automation = instrumentation.uiAutomation
        val root = automation.rootInActiveWindow ?: return
        try {
            if (root.packageName?.toString() == "com.android.documentsui") {
                automation.performGlobalAction(AccessibilityService.GLOBAL_ACTION_BACK)
            }
        } finally { @Suppress("DEPRECATION") root.recycle() }
        trace("system_picker.dismiss", "complete")
    }

    private fun await(latch: CountDownLatch, phase: String, seconds: Long) {
        if (!latch.await(seconds, TimeUnit.SECONDS)) {
            trace(phase, "timeout")
            throw AssertionError("S2.07 $phase timed out (${seconds}s)")
        }
    }

    private fun <T> onMain(phase: String, action: () -> T): T {
        val task = FutureTask(Callable { action() })
        check(main.post(task)) { "S2.07 main looper rejected $phase" }
        return try {
            task.get(8, TimeUnit.SECONDS)
        } catch (failure: TimeoutException) {
            task.cancel(false)
            trace(phase, "main_timeout")
            throw AssertionError("S2.07 $phase main-thread dispatch timed out", failure)
        } catch (failure: InterruptedException) {
            task.cancel(false)
            trace(phase, "interrupted")
            throw failure
        } catch (failure: ExecutionException) {
            trace(phase, "main_failed")
            throw AssertionError("S2.07 $phase main-thread dispatch failed", failure.cause)
        }
    }

    private fun trace(phase: String, state: String) {
        val elapsed = SystemClock.elapsedRealtime() - begin
        logStage(phase, state, elapsed)
        // Emit runner status only from the test thread, never inside an Activity callback.
        safRunnerStage(phase, state, correlationId, elapsed)
    }

    private fun logStage(phase: String, state: String, elapsed: Long = SystemClock.elapsedRealtime() - begin) {
        Log.i("DstuSafGrant", "event=resources.s207.host correlationId=$correlationId owner=app.s207 " +
            "phase=$phase state=$state elapsedMs=$elapsed")
    }

    private inner class HostLifecycle : Application.ActivityLifecycleCallbacks {
        private fun owns(candidate: Activity) = candidate is ComponentActivity &&
            candidate.javaClass.name == HOST_CLASS && candidate.intent.getStringExtra(HOST_ID) == correlationId

        override fun onActivityCreated(candidate: Activity, state: Bundle?) {
            if (owns(candidate)) {
                activity.set(candidate as ComponentActivity)
                logStage("host.create", "observed")
            }
        }
        override fun onActivityResumed(candidate: Activity) {
            if (owns(candidate)) resumed.countDown()
        }
        override fun onActivityDestroyed(candidate: Activity) {
            if (owns(candidate)) {
                activity.compareAndSet(candidate as ComponentActivity, null)
                destroyed.countDown()
            }
        }
        override fun onActivityStarted(candidate: Activity) = Unit
        override fun onActivityPaused(candidate: Activity) = Unit
        override fun onActivityStopped(candidate: Activity) = Unit
        override fun onActivitySaveInstanceState(candidate: Activity, state: Bundle) = Unit
    }

    private companion object {
        const val HOST_CLASS = "com.deepstudent.app.saf.SafGrantHostActivity"
        const val HOST_ID = "s207.hostId"
        const val SYSTEM_PICKER_RESULT = "system_picker.result"
    }
}

/** Runner stdout breadcrumb (status 2), never a pass, failure, or skip result. */
internal fun safRunnerStage(phase: String, state: String, correlationId: String? = null, elapsedMs: Long = 0L) {
    InstrumentationRegistry.getInstrumentation().sendStatus(2, Bundle().apply {
        putString("s207.owner", "app.s207")
        putString("s207.phase", phase)
        putString("s207.state", state)
        putLong("s207.elapsedMs", elapsedMs)
        if (correlationId != null) putString("s207.correlationId", correlationId)
    })
}

/** Android may finish detaching the transient URI grant owner after the Activity callback. */
internal fun awaitReadGrantExpiry(context: Context, uri: Uri) {
    val deadline = SystemClock.elapsedRealtime() + 5_000
    do {
        try { context.contentResolver.openFileDescriptor(uri, "r")?.close() }
        catch (_: SecurityException) { return }
        Thread.sleep(50)
    } while (SystemClock.elapsedRealtime() < deadline)
    throw AssertionError("S2.07 provider still opened the descriptor after the read grant should have ended")
}

private fun keepFailure(original: Throwable, cleanup: () -> Unit) {
    try { cleanup() } catch (failure: Throwable) { original.addSuppressed(failure) }
}
