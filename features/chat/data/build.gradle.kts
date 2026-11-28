plugins { id("dstu.android.library") }
android {
    namespace = "com.deepstudent.features.chat.data"
    defaultConfig { testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner" }
}
dependencies {
    implementation(project(":features:chat:api"))
    api(project(":platform:database"))
    implementation(project(":platform:network"))
    implementation(libs.kotlinx.coroutines.core)
    implementation(project(":core:common"))
    testImplementation(libs.junit)
    testImplementation(libs.kotlinx.coroutines.test)
    testImplementation(libs.okhttp.mockwebserver)
    testRuntimeOnly("androidx.sqlite:sqlite-bundled-jvm:2.6.2")
    testRuntimeOnly("androidx.sqlite:sqlite-jvm:2.6.2")
    androidTestImplementation(libs.androidx.test.runner)
    androidTestImplementation(libs.androidx.test.core)
    androidTestImplementation(libs.androidx.test.junit)
    androidTestImplementation(libs.kotlinx.coroutines.test)
    androidTestImplementation(project(":features:chat:backend"))
    androidTestImplementation(libs.okhttp.mockwebserver)
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
