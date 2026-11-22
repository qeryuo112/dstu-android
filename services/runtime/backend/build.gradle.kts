plugins {
    id("dstu.kotlin.library")
}

dependencies {
    api(project(":services:runtime:api"))
    testImplementation(libs.junit)
}

tasks.test {
    useJUnit()
}
