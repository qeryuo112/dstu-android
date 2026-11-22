plugins {
    id("dstu.kotlin.library")
}

dependencies {
    api(project(":services:sync:api"))
    api(project(":core:common"))
    implementation(libs.kotlinx.coroutines.core)
    implementation(libs.kotlinx.serialization.json)
    testImplementation(libs.junit)
    testImplementation(libs.truth)
    testImplementation(libs.kotlinx.coroutines.core)
}

tasks.test { useJUnit() }
