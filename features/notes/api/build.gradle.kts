plugins {
    id("dstu.kotlin.library")
}

dependencies {
    api(project(":core:common"))
    testImplementation(libs.junit)
}

tasks.test {
    useJUnit()
}
