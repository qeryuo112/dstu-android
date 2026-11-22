plugins {
    id("dstu.android.library")
    alias(libs.plugins.kotlin.compose)
}

android {
    namespace = "com.deepstudent.features.browser.ui"
    defaultConfig { testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner" }
    buildFeatures { compose = true }
}

dependencies {
    api(project(":features:browser:api"))
    implementation(project(":core:design"))
    api(platform(libs.compose.bom))
    api(libs.compose.ui)
    implementation(libs.compose.material3)
    implementation(libs.compose.material.icons.extended)
    implementation(libs.androidx.activity.compose)
    androidTestImplementation(platform(libs.compose.bom))
    androidTestImplementation(libs.androidx.activity.compose)
    androidTestImplementation(libs.compose.ui.test.junit4)
    androidTestImplementation(libs.androidx.test.runner)
    androidTestImplementation(libs.androidx.test.core)
    androidTestImplementation(libs.androidx.test.junit)
}
