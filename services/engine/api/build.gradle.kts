plugins {
    id("dstu.kotlin.library")
}

dependencies {
    api(project(":core:common"))
    api(libs.kotlinx.coroutines.core)
    implementation(libs.kotlinx.serialization.json)

    testImplementation(libs.junit)
}

tasks.test {
    useJUnit()
}
