plugins { id("dstu.android.library") }
android { namespace = "com.deepstudent.features.chat.data" }
dependencies {
    implementation(project(":features:chat:api"))
    implementation(project(":platform:network"))
    implementation(project(":core:common"))
    testImplementation(libs.junit)
    testImplementation(libs.kotlinx.coroutines.test)
    testImplementation(libs.okhttp.mockwebserver)
}
