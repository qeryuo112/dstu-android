pluginManagement {
    repositories {
        gradlePluginPortal()
        mavenCentral()
        google()
    }
}

dependencyResolutionManagement {
    repositories {
        mavenCentral()
        google()
    }
}

rootProject.name = "clean"
include(
    ":app",
    ":core:common",
    ":features:notes:api",
    ":features:notes:backend",
    ":features:notes:ui"
)
