plugins {
    `java-library`
}

java {
    toolchain {
        languageVersion.set(JavaLanguageVersion.of(17))
    }
}

dependencies {
    // The analyzer runs in its own JavaExec process. Keeping these compileOnly
    // prevents the Kotlin compiler / Gradle runtime from appearing on the
    // convention-plugin classpath, where they could clash with Gradle's own
    // embedded Kotlin.
    compileOnly("org.jetbrains.kotlin:kotlin-compiler-embeddable:2.2.21")
    compileOnly("com.google.code.gson:gson:2.11.0")
}

tasks.jar {
    archiveBaseName.set("dstu-architecture-analyzer")
}
