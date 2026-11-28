plugins {
    id("dstu.android.library")
}

android {
    namespace = "com.deepstudent.services.providers.data"
    compileSdk = 36

    defaultConfig {
        minSdk = 26
        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
    }
}

dependencies {
    api(project(":services:providers:api"))
    api(project(":core:common"))
    implementation(project(":platform:network"))
    implementation(libs.kotlinx.coroutines.core)

    testImplementation(libs.junit)
    testImplementation(libs.kotlinx.coroutines.test)
    testImplementation(libs.okhttp.mockwebserver)
}

tasks.withType<Test>().configureEach {
    useJUnit()
}
