plugins {
    id("dstu.kotlin.library")
}

dependencies {
    testImplementation(libs.junit)
}

tasks.test {
    useJUnit()
}
