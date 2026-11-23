plugins {
    id("dstu.android.library")
}

android {
    namespace = "com.deepstudent.services.sync.data"
    compileSdk = 36
    defaultConfig {
        minSdk = 26
        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
    }
}

dependencies {
    api(project(":services:sync:backend"))
    implementation(project(":platform:security"))
    implementation(project(":platform:database"))
    implementation(libs.okhttp)
    implementation(libs.kotlinx.coroutines.core)
    implementation(libs.kotlinx.serialization.json)
    testImplementation(libs.junit)
    testImplementation(libs.okhttp.mockwebserver)
    testRuntimeOnly("androidx.sqlite:sqlite-bundled-jvm:2.6.2")
    testRuntimeOnly("androidx.sqlite:sqlite-jvm:2.6.2")
    androidTestImplementation(libs.okhttp.mockwebserver)
    androidTestImplementation(libs.androidx.test.runner)
    androidTestImplementation(libs.androidx.test.core)
    androidTestImplementation(libs.androidx.test.junit)
}

configurations.configureEach {
    if (name.contains("UnitTest") && name.endsWith("Classpath")) {
        exclude(group = "androidx.sqlite", module = "sqlite-bundled")
        exclude(group = "androidx.sqlite", module = "sqlite-bundled-android")
        exclude(group = "androidx.sqlite", module = "sqlite")
        exclude(group = "androidx.sqlite", module = "sqlite-android")
    }
}

// Ship the immutable S0 contract bytes themselves; do not regenerate or maintain a second registry.
val frozenSyncContracts by tasks.registering(Sync::class) {
    from(rootProject.layout.projectDirectory) {
        include("config/sync-registry-contract.json")
        include("config/baseline/schema.json")
        include("config/contracts/templates-assets.json")
        include("config/contracts/media-time.json")
        include("config/contracts/plugin-data.json")
    }
    into(layout.buildDirectory.dir("generated/frozenSyncContracts"))
}
android.sourceSets["main"].assets.srcDir(layout.buildDirectory.dir("generated/frozenSyncContracts"))
tasks.named("preBuild").configure { dependsOn(frozenSyncContracts) }
android.sourceSets["test"].java.srcDir("src/sharedTest/kotlin")
android.sourceSets["androidTest"].java.srcDir("src/sharedTest/kotlin")
// The exit probe is never present in the platform/product runtime artifacts.
val exitProbeSources = rootProject.layout.projectDirectory.dir("testing/android-process-exit/src")
android.sourceSets["androidTest"].java.srcDir(exitProbeSources.dir("androidTest/java"))
android.sourceSets["androidTest"].java.srcDir(exitProbeSources.dir("sharedTest/java"))

// The app instrumentation runs this same WebDAV/password contract on Android.
android.sourceSets["test"].java.srcDir("src/markerTest/kotlin")
