plugins {
    id("dstu.kotlin.library")
}

dependencies {
    api(project(":services:script:api"))
    implementation(project(":core:common"))
    implementation(libs.kotlinx.coroutines.core)

    testImplementation(libs.junit)
    testImplementation(libs.kotlinx.coroutines.test)
}

tasks.test {
    useJUnit()
}
