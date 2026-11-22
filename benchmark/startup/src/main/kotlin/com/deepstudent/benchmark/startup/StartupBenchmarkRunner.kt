package com.deepstudent.benchmark.startup

import android.content.Context
import android.os.Build
import android.os.Bundle
import android.os.Environment
import android.util.Log
import androidx.test.runner.AndroidJUnitRunner
import java.io.File

internal const val TEST_PACKAGE = "com.deepstudent.benchmark.startup"
internal const val TARGET_PACKAGE = "com.deepstudent.app.benchmark"
internal const val TEST_SELECTION = "$TEST_PACKAGE.ColdStartupBenchmark#coldStartup"
private const val LOG_TAG = "DstuStartupBenchmark"

/** Reject unsafe arguments before AndroidJUnitRunner can initialize benchmark output. */
class StartupBenchmarkRunner : AndroidJUnitRunner() {
    override fun onCreate(arguments: Bundle?) {
        val args = requireNotNull(arguments) { "S1.27 requires explicit benchmark consent and arguments" }
        check(context.packageName == TEST_PACKAGE && targetContext.packageName == TEST_PACKAGE) {
            "S1.27 instrumentation must target its own dedicated test APK"
        }
        validateArguments(args, targetContext)
        Log.i(LOG_TAG, "s1.27.startup.guard.accepted target=$TARGET_PACKAGE warmup=3 iterations=30")
        super.onCreate(args)
    }
}

private fun validateArguments(args: Bundle, context: Context) {
    val fields = listOf("appId", "apkSha256", "sourceFingerprintSha256", "corpusSha256")
    val payloadPrefix = "androidx.benchmark.output.payload."
    val allowed = setOf("class", "benchmarkConsent", "additionalTestOutputDir", "androidx.benchmark.output.enable") +
        fields.map { payloadPrefix + it }
    check(args.keySet() == allowed) { "S1.27 refuses missing or unexpected instrumentation arguments" }
    check(args.getString("benchmarkConsent") == "disposable") { "S1.27 needs explicit disposable benchmark consent" }
    check(args.getString("class") == TEST_SELECTION) { "S1.27 test selection is fixed" }
    check(args.getString("androidx.benchmark.output.enable") == "true") { "Official JSON output is mandatory" }
    check(args.getString(payloadPrefix + "appId") == TARGET_PACKAGE) { "S1.27 target attribution mismatch" }
    for (field in fields.drop(1)) {
        check(args.getString(payloadPrefix + field)?.matches(Regex("[0-9a-f]{64}")) == true) {
            "S1.27 requires valid SHA-256 attribution"
        }
    }
    val root = if (Build.VERSION.SDK_INT >= 29) {
        context.externalMediaDirs.firstOrNull { Environment.getExternalStorageState(it) == Environment.MEDIA_MOUNTED }
    } else {
        context.externalCacheDir
    } ?: error("S1.27 test APK has no writable external output directory")
    val output = File(requireNotNull(args.getString("additionalTestOutputDir")))
    val canonical = output.canonicalFile
    check(output.isAbsolute && canonical.parentFile == root.canonicalFile &&
        canonical.name.matches(Regex("s127-[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}")) &&
        !canonical.exists()) {
        "S1.27 output must be a new s127-UUID directory in this test APK's own media/cache directory"
    }
}
