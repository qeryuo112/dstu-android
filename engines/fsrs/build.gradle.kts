plugins {
    id("dstu.android.library")
}

android {
    namespace = "com.deepstudent.engines.fsrs"
    defaultConfig {
        consumerProguardFiles("consumer-rules.pro")
    }
    sourceSets["main"].jniLibs.srcDir(layout.buildDirectory.dir("generated/fsrs-jni"))
    sourceSets["main"].assets.srcDir(layout.buildDirectory.dir("generated/fsrs-notices"))
    sourceSets["main"].assets.srcDir(layout.buildDirectory.dir("generated/fsrs-notices"))
}

val prepareFsrsNative by tasks.registering(Exec::class) {
    workingDir(rootProject.projectDir)
    commandLine("node", "tools/s1/fsrs-build.mjs", layout.buildDirectory.get().asFile.absolutePath)
    outputs.upToDateWhen { false }
    inputs.files(fileTree("src"), rootProject.file("config/stages/s1-fsrs-source.json"))
    inputs.files(rootProject.fileTree("tools/s1") { include("fsrs-*.mjs") })
    inputs.file(rootProject.file("tools/s1/fsrs-sources-check.py"))
    outputs.dir(layout.buildDirectory.dir("generated/fsrs-jni"))
}

tasks.named("preBuild").configure { dependsOn(prepareFsrsNative) }

dependencies {
    api(project(":services:engine:api"))
    testImplementation(libs.junit)
}
