plugins {
    id("dstu.android.library")
}

android {
    namespace = "com.deepstudent.features.terminal.data"
    defaultConfig {
        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
    }
}

dependencies {
    api(project(":features:terminal:api"))
    implementation(project(":features:terminal:backend"))
    implementation(project(":engines:terminal"))
    implementation(libs.kotlinx.coroutines.core)

    androidTestImplementation(libs.androidx.test.runner)
    androidTestImplementation(libs.androidx.test.core)
    androidTestImplementation(libs.androidx.test.junit)
}
