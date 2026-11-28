plugins {
    id("dstu.android.application")
    alias(libs.plugins.kotlin.compose)
}

apply(from = rootProject.file("gradle/platform-evidence.gradle.kts"))

val releaseIdentity = providers.exec {
    commandLine("node", rootProject.file("tools/s1/version.mjs"), providers.gradleProperty("dstu.version").get())
}.standardOutput.asText.get().trim().split(":")
val benchmarkApp = providers.gradleProperty("dstu.benchmarkApp").orNull == "true"
val remediationApp = providers.gradleProperty("dstu.remediationApp").orNull == "true"
check(!benchmarkApp || !remediationApp) { "Select only one isolated app variant" }

// Only a protected CI job supplies these values. Never use the debug key as a release fallback.
val releaseSigningEnvironment = listOf(
    "DSTU_RELEASE_KEYSTORE",
    "DSTU_RELEASE_STORE_PASSWORD",
    "DSTU_RELEASE_KEY_ALIAS",
    "DSTU_RELEASE_KEY_PASSWORD",
).associateWith { providers.environmentVariable(it).orNull }
val hasReleaseSigning = releaseSigningEnvironment.values.any { it != null }
check(!hasReleaseSigning || releaseSigningEnvironment.values.all { !it.isNullOrBlank() }) {
    "Incomplete release signing environment; refusing unsigned/debug fallback"
}

android {
    if (hasReleaseSigning) {
        val ciSigning = signingConfigs.create("ciRelease") {
            storeFile = file(checkNotNull(releaseSigningEnvironment["DSTU_RELEASE_KEYSTORE"]))
            check(storeFile?.isFile == true) { "Release keystore is missing" }
            storePassword = releaseSigningEnvironment["DSTU_RELEASE_STORE_PASSWORD"]
            keyAlias = releaseSigningEnvironment["DSTU_RELEASE_KEY_ALIAS"]
            keyPassword = releaseSigningEnvironment["DSTU_RELEASE_KEY_PASSWORD"]
        }
        buildTypes.named("release") { signingConfig = ciSigning }
    }
    if (remediationApp) {
        // Device acceptance must never replace the installed app or share its files/Keystore UID.
        buildTypes.named("debug") { applicationIdSuffix = ".s2device" }
    }
    namespace = "com.deepstudent.app"
    defaultConfig {
        applicationId = "com.deepstudent.app"
        versionName = releaseIdentity[0]
        versionCode = releaseIdentity[1].toInt()
    }
    if (benchmarkApp) {
        val sourceSha256 = providers.exec {
            commandLine("node", rootProject.file("tools/s1/benchmark-provenance.mjs"), "--sha256")
        }.standardOutput.asText.get().trim()
        check(Regex("[a-f0-9]{64}").matches(sourceSha256))
        buildTypes.create("benchmark") {
            initWith(buildTypes.getByName("release"))
            applicationIdSuffix = ".benchmark"
            signingConfig = signingConfigs.getByName("debug")
            isDebuggable = false
            matchingFallbacks += "release"
            manifestPlaceholders["benchmarkSourceSha256"] = sourceSha256
        }
        testBuildType = "benchmark"
    }
    buildFeatures {
        compose = true
        buildConfig = true
    }
    sourceSets["androidTest"].assets.srcDir(project(":engines:pdf").layout.buildDirectory.dir("generated/pdf-fixtures"))
    sourceSets["androidTest"].assets.srcDir(rootProject.file("fixtures/s1-18"))
    sourceSets["androidTest"].assets.srcDir(rootProject.file("services/backup/backend/src/test/resources"))
    sourceSets["androidTest"].assets.srcDir(rootProject.file("fixtures/legacy-mobile"))
}

dependencies {
    implementation(project(":core:design"))
    implementation(project(":engines:pdf"))
    implementation(project(":engines:zstd"))
    implementation(project(":engines:fsrs"))
    implementation(project(":engines:quickjs"))
    implementation(project(":services:script:api"))
    implementation(project(":services:script:backend"))
    implementation(project(":services:script:data"))
    implementation(project(":engines:proot"))
    implementation(project(":engines:terminal"))
    implementation(project(":features:environments:api"))
    implementation(project(":features:environments:data"))
    implementation(project(":features:environments:ui"))
    implementation(project(":services:execution:api"))
    implementation(project(":platform:execution"))
    implementation(project(":features:execution:ui"))
    implementation(project(":features:sync:ui"))
    implementation(project(":features:cards:api"))
    implementation(project(":features:cards:data"))
    implementation(project(":services:sync:api"))
    implementation(project(":services:sync:data"))
    implementation(project(":services:sync:backend"))
    implementation(project(":features:terminal:api"))
    implementation(project(":features:terminal:backend"))
    implementation(project(":features:terminal:data"))
    implementation(project(":features:terminal:ui"))
    implementation(project(":services:engine:api"))
    implementation(project(":features:chat:api"))
    implementation(project(":features:chat:backend"))
    implementation(project(":features:chat:data"))
    implementation(project(":features:chat:ui"))
    implementation(project(":services:providers:api"))
    implementation(project(":services:providers:backend"))
    implementation(project(":services:providers:data"))
    implementation(project(":features:documents:api"))
    implementation(project(":features:documents:data"))
    implementation(project(":features:documents:ui"))
    implementation(project(":features:workspace:api"))
    implementation(project(":features:workspace:backend"))
    implementation(project(":features:workspace:ui"))
    implementation(project(":features:workspace:data"))
    implementation(project(":platform:security"))
    implementation(project(":platform:database"))
    implementation(project(":platform:storage"))
    implementation(project(":platform:backupzip"))
    implementation(project(":services:backup:backend"))
    implementation(project(":services:backup:data"))
    implementation(project(":platform:network"))
    implementation(project(":platform:background"))
    implementation(project(":features:notes:api"))
    implementation(project(":features:notes:backend"))
    implementation(project(":features:notes:data"))
    implementation(project(":features:notes:ui"))
    implementation(project(":features:editor:api"))
    implementation(project(":features:editor:backend"))
    implementation(project(":features:editor:ui"))
    implementation(project(":features:content:api"))
    implementation(project(":features:content:backend"))
    implementation(project(":features:content:data"))
    implementation(project(":features:content:ui"))
    implementation(project(":features:browser:api"))
    implementation(project(":features:browser:backend"))
    implementation(project(":features:browser:data"))
    implementation(project(":features:browser:ui"))
    implementation(project(":services:resources:api"))
    implementation(project(":services:resources:backend"))
    implementation(project(":services:resources:data"))
    implementation(project(":core:common"))
    implementation(project(":services:runtime:api"))
    implementation(project(":services:runtime:backend"))
    implementation(project(":services:runtime:data"))
    implementation(platform(libs.compose.bom))
    implementation(libs.androidx.activity.compose)
    implementation(libs.androidx.lifecycle.viewmodel.compose)
    implementation(libs.androidx.core.ktx)
    implementation(libs.androidx.datastore.preferences)
    implementation(libs.compose.ui)
    implementation(libs.compose.material3)
    implementation(libs.compose.material.icons.extended)
    implementation(libs.kotlinx.coroutines.android)
    testImplementation(libs.junit)
    testImplementation(libs.kotlinx.coroutines.test)
    testRuntimeOnly("androidx.sqlite:sqlite-bundled-jvm:2.6.2")
    testRuntimeOnly("androidx.sqlite:sqlite-jvm:2.6.2")
    androidTestImplementation(platform(libs.compose.bom))
    androidTestImplementation(libs.compose.ui.test.junit4)
    androidTestImplementation(libs.androidx.test.runner)
    androidTestImplementation(libs.androidx.test.core)
    androidTestImplementation(libs.androidx.test.junit)
    androidTestImplementation(libs.okhttp.mockwebserver)
    androidTestImplementation(project(":features:review:api"))
    androidTestImplementation(project(":features:review:backend"))
    androidTestImplementation(project(":features:settings:api"))
    debugImplementation(libs.compose.ui.test.manifest)
}

tasks.withType<Test>().configureEach {
    dependsOn(":engines:zstd:prepareZstdHost")
    systemProperty("java.library.path", project(":engines:zstd").layout.buildDirectory.dir("host").get().asFile.absolutePath)
}

configurations.configureEach {
    if (name.contains("UnitTest") && name.endsWith("Classpath")) {
        exclude(group = "androidx.sqlite", module = "sqlite-bundled")
        exclude(group = "androidx.sqlite", module = "sqlite-bundled-android")
        exclude(group = "androidx.sqlite", module = "sqlite")
        exclude(group = "androidx.sqlite", module = "sqlite-android")
    }
}

android.sourceSets["test"].java.srcDir("src/sharedTest/kotlin")
android.sourceSets["androidTest"].java.srcDir("src/sharedTest/kotlin")
android.sourceSets["androidTest"].java.srcDir(project(":services:sync:data").file("src/markerTest/kotlin"))
