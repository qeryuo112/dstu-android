plugins {
    id("dstu.kotlin.library")
}

dependencies {
    api(project(":features:notes:api"))
    implementation(project(":services:runtime:api"))
    implementation(libs.kotlinx.coroutines.core)
    testImplementation(libs.junit)
}

tasks.test {
    useJUnit()
}
