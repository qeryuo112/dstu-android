plugins { id("dstu.kotlin.library") }

dependencies {
    api(project(":features:content:api"))
    implementation(project(":core:common"))
    testImplementation(libs.junit)
}

tasks.test { useJUnit() }
