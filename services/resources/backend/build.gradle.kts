plugins {
    id("dstu.kotlin.library")
}

dependencies {
    api(project(":services:resources:api"))
    implementation(libs.kotlinx.coroutines.core)
    testImplementation(libs.junit)
}

tasks.test {
    useJUnit()
}
