plugins {
    id("dstu.android.library")
}

android {
    namespace = "com.deepstudent.services.backup.data"
    defaultConfig {
        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
    }
}

dependencies {
    api(project(":services:backup:api"))
    api(project(":services:backup:backend"))
    api(project(":platform:database"))
    api(project(":platform:storage"))
    testImplementation(libs.junit)
    testImplementation(project(":platform:backupzip"))
    testImplementation(project(":services:sync:data"))
    androidTestImplementation(project(":services:sync:data"))
    testRuntimeOnly("androidx.sqlite:sqlite-bundled-jvm:2.6.2")
    testRuntimeOnly("androidx.sqlite:sqlite-jvm:2.6.2")
    androidTestImplementation(project(":platform:backupzip"))
    androidTestImplementation(libs.androidx.test.runner)
    androidTestImplementation(libs.androidx.test.core)
    androidTestImplementation(libs.androidx.test.junit)
}

// The same identity transaction contract runs on the signed-off JVM and Android SQLite driver.
android.sourceSets["test"].java.srcDir("src/sharedTest/kotlin")
android.sourceSets["androidTest"].java.srcDir("src/sharedTest/kotlin")
configurations.configureEach {
    if (name.contains("UnitTest") && name.endsWith("Classpath")) {
        exclude(group = "androidx.sqlite", module = "sqlite-bundled")
        exclude(group = "androidx.sqlite", module = "sqlite-bundled-android")
        exclude(group = "androidx.sqlite", module = "sqlite")
        exclude(group = "androidx.sqlite", module = "sqlite-android")
    }
}
val backupIdentityTestAssets by tasks.registering(Sync::class) {
    from(rootProject.layout.projectDirectory) {
        include("fixtures/legacy-mobile/legacy-mobile-continuity-continuity-v1.zip")
        include("fixtures/legacy-mobile/legacy-mobile-continuity-continuity-v1.continuity.json")
    }
    into(layout.buildDirectory.dir("generated/backupIdentityTestAssets"))
}
android.sourceSets["androidTest"].assets.srcDir(layout.buildDirectory.dir("generated/backupIdentityTestAssets"))
tasks.configureEach { if (name == "preDebugAndroidTestBuild") dependsOn(backupIdentityTestAssets) }
