plugins {
    id("dstu.kotlin.library")
}

dependencies {
    api(project(":features:terminal:api"))
    implementation(libs.kotlinx.coroutines.core)

    testImplementation(libs.junit)
    testImplementation(libs.kotlinx.coroutines.test)
}

tasks.test {
    useJUnit()
}
