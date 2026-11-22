package com.deepstudent.app.backup

import android.os.ParcelFileDescriptor
import androidx.test.platform.app.InstrumentationRegistry
import androidx.test.runner.lifecycle.ActivityLifecycleMonitorRegistry
import androidx.test.runner.lifecycle.Stage
import java.util.concurrent.FutureTask
import java.util.concurrent.TimeUnit
import org.junit.rules.ExternalResource

/** Keeps the real instrumented UID foreground without opening the ordinary App workspace. */
class BackupForegroundRule : ExternalResource() {
    private val runner = InstrumentationRegistry.getInstrumentation()

    override fun before() {
        // Same debug-only, shell-guarded host used for the real SAF contract tests; no settings changes.
        val command = "am start -W --user 0 -n ${runner.targetContext.packageName}/$HOST"
        val descriptor = runner.uiAutomation.executeShellCommand(command)
        val response = FutureTask {
            ParcelFileDescriptor.AutoCloseInputStream(descriptor).bufferedReader().use { it.readText() }
        }
        Thread(response, "backup-test-foreground").apply { isDaemon = true }.start()
        try {
            check(response.get(12, TimeUnit.SECONDS).lineSequence().any { it.trim() == "Status: ok" }) {
                "backup fixture host did not enter foreground"
            }
        } finally {
            if (response.cancel(true)) runCatching { descriptor.close() }
        }
    }

    override fun after() {
        runner.runOnMainSync {
            val monitor = ActivityLifecycleMonitorRegistry.getInstance()
            monitor.getActivitiesInStage(Stage.RESUMED).filter { it.javaClass.name == HOST }
                .forEach { it.finishAndRemoveTask() }
        }
    }

    private companion object { const val HOST = "com.deepstudent.app.saf.SafGrantHostActivity" }
}
