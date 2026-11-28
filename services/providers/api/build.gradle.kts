plugins {
    id("dstu.kotlin.library")
}

dependencies {
    api(project(":core:common"))
    api(libs.kotlinx.coroutines.core)

    testImplementation(libs.junit)
    testImplementation(libs.kotlinx.coroutines.test)
}

tasks.test {
    useJUnit()
}
