import org.jetbrains.kotlin.gradle.dsl.JvmTarget

plugins {
    `java-gradle-plugin`
    kotlin("jvm") version "2.2.21"
    // Keep Gradle Action receiver syntax without kotlin-dsl's embedded Kotlin.
    kotlin("plugin.sam.with.receiver") version "2.2.21"
    checkstyle
}

group = "com.deepstudent.buildlogic"
version = "1.0.0"

java {
    toolchain {
        languageVersion.set(JavaLanguageVersion.of(17))
    }
}

kotlin {
    jvmToolchain(17)
    compilerOptions {
        jvmTarget.set(JvmTarget.JVM_17)
    }
}

samWithReceiver {
    annotation("org.gradle.api.HasImplicitReceiver")
}

dependencies {
    // java-gradle-plugin does not add the Gradle API to this Kotlin compile classpath.
    compileOnly(gradleApi())
    implementation(project(":architecture-analyzer"))
    implementation("com.google.code.gson:gson:2.11.0")

    // Typed compile-time dependencies. They are also runtime dependencies
    // because the convention plugins must load AGP/KGP in TestKit fixtures and
    // in the included build. Keep these pins aligned with DstuVersions.
    implementation("com.android.tools.build:gradle:8.13.0")
    implementation("org.jetbrains.kotlin:kotlin-gradle-plugin:2.2.21")

    checkstyle("com.puppycrawl.tools:checkstyle:10.20.1")
    checkstyle(project(":java-complexity-checks"))

    testImplementation(gradleTestKit())
    testImplementation("org.junit.jupiter:junit-jupiter:5.11.4")
    testImplementation(project(":java-complexity-checks"))
    testImplementation("com.puppycrawl.tools:checkstyle:10.20.1")
    testRuntimeOnly("org.junit.platform:junit-platform-launcher")
}

gradlePlugin {
    plugins {
        create("dstuAndroidApplication") {
            id = "dstu.android.application"
            implementationClass = "com.deepstudent.buildlogic.convention.DstuAndroidApplicationPlugin"
        }
        create("dstuAndroidLibrary") {
            id = "dstu.android.library"
            implementationClass = "com.deepstudent.buildlogic.convention.DstuAndroidLibraryPlugin"
        }
        create("dstuKotlinLibrary") {
            id = "dstu.kotlin.library"
            implementationClass = "com.deepstudent.buildlogic.convention.DstuKotlinLibraryPlugin"
        }
        create("dstuArchitecture") {
            id = "dstu.architecture"
            implementationClass = "com.deepstudent.buildlogic.architecture.DstuArchitecturePlugin"
        }
        create("dstuCoverage") {
            id = "dstu.coverage"
            implementationClass = "com.deepstudent.buildlogic.quality.DstuCoveragePlugin"
        }
    }
}

tasks.test {
    useJUnitPlatform()
    maxParallelForks = 1
    testLogging {
        events("passed", "skipped", "failed")
        exceptionFormat = org.gradle.api.tasks.testing.logging.TestExceptionFormat.FULL
    }
}

tasks.validatePlugins {
    enableStricterValidation.set(true)
}

val checkstyleJavaComplexity = tasks.register<org.gradle.api.plugins.quality.Checkstyle>(
    "checkstyleJavaComplexity",
) {
    group = "verification"
    description = "Checks build-logic Java tools with fixed Checkstyle AST limits."
    source(fileTree("architecture-analyzer/src/main/java"))
    source(fileTree("java-complexity-checks/src/main/java"))
    configFile = file("config/checkstyle/java-complexity.xml")
    checkstyleClasspath = configurations.getByName("checkstyle")
    classpath = files()
    reports {
        xml.required.set(true)
        xml.outputLocation.set(
            layout.buildDirectory.file("reports/checkstyle/java-complexity.xml"),
        )
        html.required.set(true)
        html.outputLocation.set(
            layout.buildDirectory.file("reports/checkstyle/java-complexity.html"),
        )
    }
}

tasks.matching { it.name == "check" }.configureEach {
    dependsOn(checkstyleJavaComplexity)
}
