plugins { id("dstu.kotlin.library") }
dependencies {
    implementation(project(":features:chat:api"))
    implementation(project(":services:runtime:api"))
    implementation(project(":core:common"))
    implementation(libs.kotlinx.coroutines.core)
    testImplementation(libs.junit)
    testImplementation(libs.kotlinx.coroutines.test)
}
