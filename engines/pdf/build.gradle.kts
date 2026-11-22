plugins {
    id("dstu.android.library")
}

android {
    namespace = "com.deepstudent.engines.pdf"
    defaultConfig {
        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
        consumerProguardFiles("consumer-rules.pro")
    }
    sourceSets["main"].jniLibs.srcDir(layout.buildDirectory.dir("generated/pdf-jni"))
    sourceSets["main"].assets.srcDir(layout.buildDirectory.dir("generated/pdf-notices"))
    sourceSets["androidTest"].assets.srcDir(layout.buildDirectory.dir("generated/pdf-fixtures"))
}

val preparePdfNative by tasks.registering(Exec::class) {
    workingDir(rootProject.projectDir)
    commandLine("node", "tools/s1/pdf-build.mjs", layout.buildDirectory.get().asFile.absolutePath)
    // External source checkout and its transitive DEPS are verified by the command on every build.
    outputs.upToDateWhen { false }
    inputs.files(fileTree("src/main/cpp"), rootProject.file("config/stages/s1-pdf-source.json"))
    inputs.files(rootProject.fileTree("tools/s1") { include("pdf-*.mjs") })
    outputs.dir(layout.buildDirectory.dir("generated/pdf-jni"))
    outputs.dir(layout.buildDirectory.dir("generated/pdf-fixtures"))
    outputs.dir(layout.buildDirectory.dir("generated/pdf-notices"))
}

tasks.named("preBuild").configure { dependsOn(preparePdfNative) }

dependencies {
    api(project(":services:engine:api"))

    androidTestImplementation(libs.androidx.test.runner)
    androidTestImplementation(libs.androidx.test.core)
    androidTestImplementation(libs.androidx.test.junit)
}
