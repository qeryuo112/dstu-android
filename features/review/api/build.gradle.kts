plugins {
    id("dstu.kotlin.library")
}

dependencies {
    api(project(":services:engine:api"))
}

tasks.test {
    useJUnit()
}
