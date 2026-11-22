plugins {
    id("dstu.android.library")
}

android {
    namespace = "com.deepstudent.engines.zstd"
    defaultConfig {
        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
        consumerProguardFiles("consumer-rules.pro")
    }
    sourceSets["main"].jniLibs.srcDir(layout.buildDirectory.dir("generated/zstd-jni"))
    sourceSets["main"].assets.srcDir(layout.buildDirectory.dir("generated/zstd-notices"))
    sourceSets["androidTest"].assets.srcDir(rootProject.file("config/baseline"))
}

val prepareZstdNative by tasks.registering(Exec::class) {
    workingDir(rootProject.projectDir)
    commandLine("node", "tools/s1/zstd-build.mjs")
    outputs.upToDateWhen { false }
    inputs.files(rootProject.fileTree("engines/zstd/src/main/cpp"),
        rootProject.file("tools/s1/zstd-build.mjs"), rootProject.file("config/stages/s1-zstd-source.json"))
    outputs.dir(layout.buildDirectory.dir("generated/zstd-jni"))
    outputs.dir(layout.buildDirectory.dir("generated/zstd-notices"))
}
tasks.named("preBuild").configure { dependsOn(prepareZstdNative) }

val prepareZstdHost by tasks.registering(Exec::class) {
    workingDir(rootProject.projectDir)
    commandLine("node", "tools/s1/zstd-build.mjs", "host")
    outputs.upToDateWhen { false }
}
tasks.withType<Test>().configureEach {
    dependsOn(prepareZstdHost)
    systemProperty("java.library.path", layout.buildDirectory.dir("host").get().asFile.absolutePath)
    systemProperty("dstu.zstd.crosscheck", layout.buildDirectory.file("host/crosscheck.zst").get().asFile.absolutePath)
}

dependencies {
    testImplementation(libs.junit)
    androidTestImplementation(libs.androidx.test.runner)
    androidTestImplementation(libs.androidx.test.core)
    androidTestImplementation(libs.androidx.test.junit)
}
