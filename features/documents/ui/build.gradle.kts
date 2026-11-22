plugins {
    id("dstu.android.library")
    alias(libs.plugins.kotlin.compose)
}
android { namespace = "com.deepstudent.features.documents.ui"; buildFeatures { compose = true } }
dependencies {
    implementation(project(":features:documents:api"))
    implementation(project(":services:engine:api"))
    implementation(project(":core:design"))
    implementation(platform(libs.compose.bom))
    implementation(libs.compose.ui)
    implementation(libs.compose.material3)
    implementation(libs.compose.material.icons.extended)
    implementation(libs.kotlinx.coroutines.android)
}
