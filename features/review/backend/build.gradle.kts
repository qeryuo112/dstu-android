plugins {
    id("dstu.kotlin.library")
}

dependencies {
    api(project(":features:review:api"))
    testImplementation(libs.junit)
    testImplementation(libs.kotlinx.serialization.json)
}

tasks.test {
    useJUnit()
}
