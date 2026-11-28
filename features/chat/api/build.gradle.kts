plugins { id("dstu.kotlin.library") }
dependencies {
    api(project(":core:common"))
    api(project(":services:runtime:api"))
    api(libs.kotlinx.coroutines.core)
    testImplementation(libs.junit)
}
