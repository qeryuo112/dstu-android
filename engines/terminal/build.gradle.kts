plugins {
    id("dstu.android.library")
}

android {
    namespace = "com.deepstudent.engines.terminal"
    defaultConfig {
        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
    }
    sourceSets["main"].java.srcDir("src/operit/kotlin")
    sourceSets["main"].jniLibs.srcDir(layout.buildDirectory.dir("generated/terminal-jni"))
    sourceSets["main"].assets.srcDir(layout.buildDirectory.dir("generated/operit-notices"))
    sourceSets["test"].java.srcDir(rootProject.file("testing/fixtures/s1-terminal/android-stubs"))
}

val prepareTerminalSources by tasks.registering(Exec::class) {
    workingDir(rootProject.projectDir)
    commandLine(
        "node",
        "tools/s1/terminal-prepare.mjs",
        file("src/operit/kotlin").absolutePath,
        layout.buildDirectory.dir("generated/operit-notices").get().asFile.absolutePath,
    )
    inputs.dir(rootProject.file("third_party/operit/terminal"))
    inputs.files(rootProject.file("config/stages/s1-terminal-source.json"))
    inputs.files(rootProject.file("tools/s1/terminal-prepare.mjs"))
    inputs.dir("src/operit/kotlin")
    outputs.upToDateWhen { false }
    outputs.dir(layout.buildDirectory.dir("generated/operit-notices"))
}

val prepareTerminalNative by tasks.registering(Exec::class) {
    workingDir(rootProject.projectDir)
    commandLine("node", "tools/s1/terminal-build.mjs", layout.buildDirectory.get().asFile.absolutePath)
    outputs.upToDateWhen { false }
    inputs.files(fileTree("src/main/cpp"), rootProject.file("config/stages/s1-terminal-source.json"))
    inputs.files(rootProject.fileTree("third_party/operit/terminal"))
    inputs.files(rootProject.file("tools/s1/terminal-build.mjs"), rootProject.file("tools/s1/terminal-prepare.mjs"))
    outputs.dir(layout.buildDirectory.dir("generated/terminal-jni"))
}

tasks.named("preBuild").configure { dependsOn(prepareTerminalSources, prepareTerminalNative) }
tasks.configureEach {
    if (name.startsWith("compile") && name.contains("Kotlin")) {
        dependsOn(prepareTerminalSources)
    }
}

dependencies {
    implementation(libs.icu4j)

    testImplementation(libs.junit)
    androidTestImplementation(libs.androidx.test.runner)
    androidTestImplementation(libs.androidx.test.core)
    androidTestImplementation(libs.androidx.test.junit)
}
