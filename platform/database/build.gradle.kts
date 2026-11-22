plugins {
    id("dstu.android.library")
}

android {
    namespace = "com.deepstudent.platform.database"
    compileSdk = 36

    defaultConfig {
        minSdk = 26
        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"

        ndk {
            abiFilters += "arm64-v8a"
        }
    }

    packaging {
        jniLibs {
            useLegacyPackaging = true
        }
    }

    sourceSets["main"].assets.srcDir(layout.buildDirectory.dir("generated/frozenMigrations"))
}

dependencies {
    implementation(libs.androidx.sqlite.bundled)
    implementation(libs.kotlinx.serialization.json)

    testImplementation(libs.junit)
    // Local JVM probes must use the desktop native slice of the same artifact.
    testRuntimeOnly("androidx.sqlite:sqlite-bundled-jvm:2.6.2")
    testImplementation("androidx.sqlite:sqlite-jvm:2.6.2")

    androidTestImplementation(libs.androidx.test.runner)
    androidTestImplementation(libs.androidx.test.core)
    androidTestImplementation(libs.androidx.test.junit)
}

val frozenMigrationAssets by tasks.registering(Sync::class) {
    from(rootProject.layout.projectDirectory) {
        include("config/schema/history/**/*.sql")
        include("config/schema/coordinated/**/*.sql")
        include("config/schema/final-lock.json")
        include("config/schema/s2-04-repair.json")
    }
    into(layout.buildDirectory.dir("generated/frozenMigrations"))
}

tasks.named("preBuild").configure { dependsOn(frozenMigrationAssets) }

// Unit tests run on a desktop JVM. Replace the Android AAR and Android variant
// of the API with the JVM artifacts on local-test classpaths only.
configurations.configureEach {
    if (name.contains("UnitTest") && name.endsWith("Classpath")) {
        exclude(group = "androidx.sqlite", module = "sqlite-bundled")
        exclude(group = "androidx.sqlite", module = "sqlite-bundled-android")
        exclude(group = "androidx.sqlite", module = "sqlite")
        exclude(group = "androidx.sqlite", module = "sqlite-android")
    }
}

// Identical real-driver migration contracts run on the host and Android; the frozen
// upgrade fixture is packaged only in the instrumentation APK.
android.sourceSets["test"].java.srcDir("src/sharedTest/kotlin")
android.sourceSets["androidTest"].java.srcDir("src/sharedTest/kotlin")
// The exit probe is never present in the platform/product runtime artifacts.
val exitProbeSources = rootProject.layout.projectDirectory.dir("testing/android-process-exit/src")
android.sourceSets["androidTest"].java.srcDir(exitProbeSources.dir("androidTest/java"))
android.sourceSets["androidTest"].java.srcDir(exitProbeSources.dir("sharedTest/java"))
android.sourceSets["test"].java.srcDir(exitProbeSources.dir("sharedTest/java"))
android.sourceSets["test"].java.srcDir(exitProbeSources.dir("test/java"))
val migrationUpgradeTestAssets by tasks.registering(Sync::class) {
    from(rootProject.layout.projectDirectory) { include("config/baseline/schema-upgrade.json") }
    into(layout.buildDirectory.dir("generated/migrationUpgradeTestAssets"))
}
android.sourceSets["androidTest"].assets.srcDir(layout.buildDirectory.dir("generated/migrationUpgradeTestAssets"))
tasks.configureEach { if (name == "preDebugAndroidTestBuild") dependsOn(migrationUpgradeTestAssets) }
