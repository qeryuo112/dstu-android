plugins {
    id("dstu.android.library")
}

android {
    namespace = "com.deepstudent.features.environments.data"
    defaultConfig {
        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
    }
}

dependencies {
    api(project(":features:environments:api"))
    implementation(project(":features:environments:backend"))
    implementation(project(":engines:proot"))
    implementation(project(":core:common"))
    implementation(libs.kotlinx.coroutines.core)
    implementation("org.apache.commons:commons-compress:1.27.1")
    implementation("org.tukaani:xz:1.10")

    testImplementation(libs.junit)
    testImplementation(libs.kotlinx.coroutines.test)
    androidTestImplementation(libs.androidx.test.runner)
    androidTestImplementation(libs.androidx.test.core)
    androidTestImplementation(libs.androidx.test.junit)
}
