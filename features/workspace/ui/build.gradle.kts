plugins {
    id("dstu.android.library")
    alias(libs.plugins.kotlin.compose)
}
android {
    namespace = "com.deepstudent.features.workspace.ui"
    buildFeatures { compose = true }
}
dependencies {
    api(project(":features:workspace:api"))
    implementation(project(":core:design"))
    implementation(platform(libs.compose.bom))
    implementation(libs.compose.ui)
    implementation(libs.compose.material3)
    implementation(libs.compose.material.icons.extended)
    implementation(libs.androidx.activity.compose)
    implementation(libs.kotlinx.coroutines.android)
    androidTestImplementation(platform(libs.compose.bom))
    androidTestImplementation(libs.compose.ui.test.junit4)
    androidTestImplementation(libs.androidx.test.runner)
    androidTestImplementation(libs.androidx.test.core)
    androidTestImplementation(libs.androidx.test.junit)
    debugImplementation(libs.compose.ui.test.manifest)
}
