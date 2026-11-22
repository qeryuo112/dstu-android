plugins { id("dstu.kotlin.library") }

dependencies {
    api(project(":features:browser:api"))
    testImplementation(libs.junit)
}

tasks.test { useJUnit() }
