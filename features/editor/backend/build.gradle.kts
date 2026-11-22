plugins {
    id("dstu.kotlin.library")
}

dependencies {
    api(project(":features:editor:api"))
    implementation(libs.jetbrains.markdown)
    implementation(libs.icu4j)
    testImplementation(libs.kotlinx.serialization.json)
    testImplementation(libs.junit)
}

tasks.processTestResources {
    from(rootProject.file("config/content-inputs.json")) { into("frozen") }
    from(rootProject.file("config/baseline/content.json")) { into("frozen") }
    from(rootProject.file("config/baseline/corpus.json")) { into("frozen") }
}

tasks.test {
    useJUnit()
}
