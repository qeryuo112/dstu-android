plugins {
    id("dstu.kotlin.library")
}

dependencies {
    implementation(project(":features:media:api"))

    testImplementation(libs.junit)
}

tasks.test {
    useJUnit()
}
