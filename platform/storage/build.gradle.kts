plugins {
    id("dstu.android.library")
}

android {
    namespace = "com.deepstudent.platform.storage"
    defaultConfig {
        minSdk = 26
        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
    }
    sourceSets["test"].java.srcDir("src/sharedTest/kotlin")
    sourceSets["androidTest"].java.srcDir("src/sharedTest/kotlin")
}

dependencies {
    api(project(":core:common"))
    testImplementation(libs.junit)
    androidTestImplementation(libs.androidx.test.runner)
    androidTestImplementation(libs.androidx.test.core)
    androidTestImplementation(libs.androidx.test.junit)
}
