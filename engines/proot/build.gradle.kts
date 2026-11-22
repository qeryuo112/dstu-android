plugins {
    id("dstu.android.library")
}

android {
    namespace = "com.deepstudent.engines.proot"
    sourceSets["main"].jniLibs.srcDir(layout.buildDirectory.dir("generated/proot-jni"))
    sourceSets["main"].resources.srcDir(layout.buildDirectory.dir("generated/proot-resources"))
    sourceSets["main"].assets.srcDir(layout.buildDirectory.dir("generated/proot-notices"))
}

val preparePRootNative by tasks.registering(Exec::class) {
    workingDir(rootProject.projectDir)
    commandLine("node", "tools/s1/proot-build.mjs", layout.buildDirectory.get().asFile.absolutePath)
    inputs.dir(rootProject.file("third_party/proot"))
    inputs.dir(rootProject.file("third_party/talloc"))
    inputs.dir("src/main/cpp")
    inputs.files(rootProject.file("config/stages/s1-proot-source.json"), rootProject.file("tools/s1/proot-build.mjs"))
    outputs.dir(layout.buildDirectory.dir("generated/proot-jni"))
    outputs.dir(layout.buildDirectory.dir("generated/proot-resources"))
    outputs.dir(layout.buildDirectory.dir("generated/proot-notices"))
}

tasks.named("preBuild").configure { dependsOn(preparePRootNative) }
tasks.configureEach {
    if (name.contains("JavaRes") || name.startsWith("compile") && name.contains("Kotlin")) {
        dependsOn(preparePRootNative)
    }
}

dependencies {
    implementation(project(":core:common"))
    implementation(libs.kotlinx.coroutines.core)
    testImplementation(libs.junit)
    androidTestImplementation(libs.androidx.test.runner)
    androidTestImplementation(libs.androidx.test.core)
    androidTestImplementation(libs.androidx.test.junit)
}
