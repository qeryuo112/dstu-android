plugins {
    id("dstu.kotlin.library")
}

dependencies {
    api(project(":core:common"))
    api(libs.kotlinx.coroutines.core)

    testImplementation(libs.junit)
}

tasks.test {
    useJUnit()
}
