plugins {
    id("dstu.android.library")
}

android {
    namespace = "com.deepstudent.engines.quickjs"
    defaultConfig {
        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
        consumerProguardFiles("consumer-rules.pro")
    }
    sourceSets["main"].jniLibs.srcDir(layout.buildDirectory.dir("generated/script-jni"))
    sourceSets["main"].assets.srcDir(layout.buildDirectory.dir("generated/script-notices"))
    sourceSets["androidTest"].assets.srcDir(rootProject.file("fixtures/script"))
}

val prepareQuickJsNative by tasks.registering(Exec::class) {
    workingDir(rootProject.projectDir)
    commandLine("node", "tools/s1/script-build.mjs")
    outputs.upToDateWhen { false }
    inputs.files(
        rootProject.fileTree("engines/quickjs/src/main/cpp"),
        rootProject.file("config/stages/s1-script-source.json"),
        rootProject.file("config/sources/quickjs.commit.json"),
    )
    inputs.files(rootProject.fileTree("tools/s1") { include("script-*.mjs") })
    outputs.dir(layout.buildDirectory.dir("generated/script-jni"))
    outputs.dir(layout.buildDirectory.dir("generated/script-notices"))
}

tasks.named("preBuild").configure { dependsOn(prepareQuickJsNative) }

dependencies {
    api(project(":services:script:api"))

    testImplementation(libs.junit)
    androidTestImplementation(libs.androidx.test.runner)
    androidTestImplementation(libs.androidx.test.core)
    androidTestImplementation(libs.androidx.test.junit)
}
