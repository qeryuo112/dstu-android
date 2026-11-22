plugins { id("dstu.kotlin.library") }
dependencies {
    api(project(":services:runtime:api"))
    api(libs.kotlinx.coroutines.core)
}
