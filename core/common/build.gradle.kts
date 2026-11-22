plugins {
    id("dstu.kotlin.library")
}

dependencies {
    api(libs.kotlinx.serialization.json)

    testImplementation(libs.junit)
    testImplementation(libs.kotlinx.coroutines.core)
}

tasks.test {
    useJUnit()
}
