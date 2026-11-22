plugins {
    id("dstu.kotlin.library")
}

dependencies {
    api(project(":services:backup:api"))
    implementation("org.apache.commons:commons-compress:1.27.1")
    testImplementation(libs.junit)
}

tasks.test { useJUnit() }
