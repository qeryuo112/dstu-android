package com.deepstudent.services.backup.data

import android.app.Activity
import android.os.Bundle
import android.os.ParcelFileDescriptor
import android.view.WindowManager
import androidx.test.platform.app.InstrumentationRegistry
import androidx.test.runner.lifecycle.ActivityLifecycleMonitorRegistry
import androidx.test.runner.lifecycle.Stage
import java.util.concurrent.FutureTask
import java.util.concurrent.TimeUnit
import org.junit.rules.ExternalResource

class BackupIdentityTestActivity : Activity() {
    override fun onCreate(state: Bundle?) {
        super.onCreate(state)
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
    }
}

class BackupIdentityForegroundRule : ExternalResource() {
    private val runner = InstrumentationRegistry.getInstrumentation()
    override fun before() {
        val host = BackupIdentityTestActivity::class.java.name
        val component = "${runner.context.packageName}/$host"
        val handle = runner.uiAutomation.executeShellCommand("am start -W --user 0 -n $component")
        val response = FutureTask { ParcelFileDescriptor.AutoCloseInputStream(handle).bufferedReader().use { it.readText() } }
        Thread(response, "identity-test-foreground").apply { isDaemon = true }.start()
        try { check(response.get(15, TimeUnit.SECONDS).lineSequence().any { it.trim() == "Status: ok" }) }
        finally { if (response.cancel(true)) runCatching { handle.close() } }
    }

    override fun after() {
        runner.runOnMainSync {
            ActivityLifecycleMonitorRegistry.getInstance().getActivitiesInStage(Stage.RESUMED)
                .filterIsInstance<BackupIdentityTestActivity>().forEach { it.finishAndRemoveTask() }
        }
    }
}
