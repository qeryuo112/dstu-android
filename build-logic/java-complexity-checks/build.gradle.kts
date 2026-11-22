plugins {
    `java-library`
}

java {
    toolchain {
        languageVersion.set(JavaLanguageVersion.of(17))
    }
}

dependencies {
    compileOnly("com.puppycrawl.tools:checkstyle:10.20.1")
}

tasks.jar {
    archiveBaseName.set("dstu-java-complexity-checks")
}
