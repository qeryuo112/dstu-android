package com.deepstudent.benchmark.startup

import android.content.ComponentName
import android.content.Intent
import android.content.pm.ApplicationInfo
import android.os.SystemClock
import android.util.Log
import androidx.benchmark.macro.BaselineProfileMode
import androidx.benchmark.macro.CompilationMode
import androidx.benchmark.macro.StartupMode
import androidx.benchmark.macro.StartupTimingMetric
import androidx.benchmark.macro.junit4.MacrobenchmarkRule
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import androidx.test.uiautomator.By
import androidx.test.uiautomator.Until
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

private const val READY_TAG = "s127-workspace-ready"
private const val READY_TIMEOUT_MS = 30_000L
private const val DRAW_SETTLE_MS = 1_000L
private const val LOG_TAG = "DstuStartupBenchmark"

@RunWith(AndroidJUnit4::class)
class ColdStartupBenchmark {
    @get:Rule
    val benchmarkRule = MacrobenchmarkRule()

    @Test
    fun coldStartup() {
        verifyIsolatedTarget()
        Log.i(LOG_TAG, "s1.27.startup.begin target=$TARGET_PACKAGE warmup=3 iterations=30 mode=COLD")
        benchmarkRule.measureRepeated(
            packageName = TARGET_PACKAGE,
            metrics = listOf(StartupTimingMetric()),
            compilationMode = CompilationMode.Partial(
                baselineProfileMode = BaselineProfileMode.Disable,
                warmupIterations = 3,
            ),
            iterations = 30,
            startupMode = StartupMode.COLD,
            setupBlock = { pressHome() },
        ) {
            // Explicit component ensures no intent can resolve to the real user APK.
            val intent = Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_LAUNCHER)
                .setComponent(ComponentName(TARGET_PACKAGE, "com.deepstudent.app.MainActivity"))
            startActivityAndWait(intent)
            check(device.wait(Until.hasObject(By.res(READY_TAG).pkg(TARGET_PACKAGE)), READY_TIMEOUT_MS)) {
                "S1.27 workspace readiness marker was not observed"
            }
            device.waitForIdle(READY_TIMEOUT_MS)
            // Keep tracing past readiness for the frame containing reportFullyDrawn.
            // This delay is not a timing source: AndroidX extracts TTID/TTFD from Perfetto.
            SystemClock.sleep(DRAW_SETTLE_MS)
            Log.i(LOG_TAG, "s1.27.startup.ready iteration=${iteration ?: "warmup"}")
        }
        Log.i(LOG_TAG, "s1.27.startup.finished; official JSON and traces still require validation")
    }
}

@Suppress("DEPRECATION")
private fun verifyIsolatedTarget() {
    val instrumentation = InstrumentationRegistry.getInstrumentation()
    check(instrumentation.context.packageName == TEST_PACKAGE && instrumentation.targetContext.packageName == TEST_PACKAGE) {
        "S1.27 self-instrumentation identity changed"
    }
    val pm = instrumentation.context.packageManager
    val packageInfo = pm.getPackageInfo(TARGET_PACKAGE, 0)
    val app = requireNotNull(packageInfo.applicationInfo) { "S1.27 isolated target is not installed" }
    check(app.packageName == TARGET_PACKAGE && app.enabled && app.flags and ApplicationInfo.FLAG_DEBUGGABLE == 0) {
        "S1.27 requires the non-debuggable isolated target"
    }
    check(packageInfo.sharedUserId == null && app.uid != instrumentation.context.applicationInfo.uid) {
        "S1.27 refuses a shared-UID target"
    }
    val activity = pm.getActivityInfo(ComponentName(TARGET_PACKAGE, "com.deepstudent.app.MainActivity"), 0)
    check(activity.enabled && activity.exported && activity.packageName == TARGET_PACKAGE) {
        "S1.27 isolated launcher is unavailable"
    }
}
