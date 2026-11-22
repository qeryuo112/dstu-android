plugins {
    id("dstu.android.library")
}

android {
    namespace = "com.deepstudent.features.cards.data"
}

dependencies {
    implementation(project(":features:cards:api"))
    implementation(project(":platform:database"))

    testImplementation(libs.junit)
    testRuntimeOnly("androidx.sqlite:sqlite-bundled-jvm:2.6.2")
    testRuntimeOnly("androidx.sqlite:sqlite-jvm:2.6.2")
}

configurations.configureEach {
    if (name.contains("UnitTest") && name.endsWith("Classpath")) {
        exclude(group = "androidx.sqlite", module = "sqlite-bundled")
        exclude(group = "androidx.sqlite", module = "sqlite-bundled-android")
        exclude(group = "androidx.sqlite", module = "sqlite")
        exclude(group = "androidx.sqlite", module = "sqlite-android")
    }
}
