plugins { id("dstu.kotlin.library") }

dependencies {
    api(libs.kotlinx.coroutines.core)
    testImplementation(libs.junit)
}

tasks.test { useJUnit() }
