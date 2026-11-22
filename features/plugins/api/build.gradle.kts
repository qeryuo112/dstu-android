plugins {
    id("dstu.kotlin.library")
}

dependencies {
    api(project(":core:common"))
    implementation(libs.kotlinx.serialization.json)

    testImplementation(libs.junit)
}

tasks.test {
    useJUnit()
}
