plugins {
    id("dstu.kotlin.library")
}

dependencies {
    api(project(":services:backup:api"))
    implementation(libs.kotlinx.serialization.json)
    testImplementation(libs.junit)
    testImplementation(project(":platform:backupzip"))
}

tasks.test { useJUnit() }
