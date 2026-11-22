import org.jetbrains.kotlin.gradle.dsl.JvmTarget

plugins {
    id("com.android.test")
    alias(libs.plugins.kotlin.android)
}

android {
    namespace = "com.deepstudent.benchmark.startup"
    compileSdk = 36

    defaultConfig {
        minSdk = 26
        targetSdk = 36
        testApplicationId = "com.deepstudent.benchmark.startup"
        testInstrumentationRunner = "com.deepstudent.benchmark.startup.StartupBenchmarkRunner"
    }

    // Only this build type may be matched to :app. No fallback to its real debug/release APK.
    buildTypes {
        create("benchmark") {
            signingConfig = signingConfigs.getByName("debug")
        }
    }
    targetProjectPath = ":app"
    experimentalProperties["android.experimental.self-instrumenting"] = true

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
}

androidComponents {
    beforeVariants(selector().all()) { variant ->
        if (variant.buildType != "benchmark") variant.enable = false
    }
}

kotlin {
    compilerOptions { jvmTarget.set(JvmTarget.JVM_17) }
}

dependencies {
    implementation("androidx.benchmark:benchmark-macro-junit4:1.4.1")
    implementation(libs.androidx.test.runner)
    implementation(libs.androidx.test.junit)
    implementation("androidx.test.uiautomator:uiautomator:2.3.0")
}

// AGP connected tests can install the linked :app before instrumentation starts.
// Fail at graph-ready time, *before* any install task runs. Install the isolated
// APKs explicitly and invoke the guarded runner as described in the handoff.
gradle.taskGraph.whenReady {
    val unsafe = allTasks.firstOrNull { task ->
        task.project.path == ":benchmark:startup" &&
            (task.name.startsWith("connected") || task.name.endsWith("AndroidTest"))
    }
    if (unsafe != null) {
        throw GradleException("S1.27: ${unsafe.path} is forbidden; install the isolated APKs and run the guarded instrumentation explicitly")
    }
}
