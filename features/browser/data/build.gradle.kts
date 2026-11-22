plugins { id("dstu.android.library") }

android {
    namespace = "com.deepstudent.features.browser.data"
    defaultConfig { testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner" }
}

dependencies {
    api(project(":features:browser:api"))
    implementation(libs.kotlinx.coroutines.android)
    androidTestImplementation(project(":features:browser:backend"))
    androidTestImplementation(libs.androidx.test.runner)
    androidTestImplementation(libs.androidx.test.core)
    androidTestImplementation(libs.androidx.test.junit)
}
