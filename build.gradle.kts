import com.deepstudent.buildlogic.quality.DstuCoverageExtension
import java.util.Properties

plugins {
    id("dstu.architecture")
    id("dstu.coverage") apply false
    alias(libs.plugins.spotless)
    alias(libs.plugins.android.application) apply false
    alias(libs.plugins.android.library) apply false
    alias(libs.plugins.kotlin.android) apply false
    alias(libs.plugins.kotlin.jvm) apply false
    alias(libs.plugins.kotlin.compose) apply false
    alias(libs.plugins.kotlin.serialization) apply false
}

val coverageConfig = Properties().apply {
    rootProject.file("config/quality/coverage.properties").inputStream().use(::load)
}
val coverageProjects = coverageConfig.getProperty("scope")
    .split(",")
    .map(String::trim)
    .filter(String::isNotEmpty)
val coverageMinimum = coverageConfig.getProperty("minimum").toBigDecimal()
check(coverageProjects.isNotEmpty() && coverageProjects.distinct().size == coverageProjects.size) {
    "Coverage scope must be nonempty and contain no duplicates"
}
check(coverageProjects.all { findProject(it) != null }) { "Coverage scope contains an unregistered module" }

subprojects {
    if (path in coverageProjects) {
        pluginManager.apply("dstu.coverage")
        extensions.configure(DstuCoverageExtension::class.java) {
            minimum.set(coverageMinimum)
        }
    }
}

// A fixed pre-formatting baseline also checks committed files on main/tag builds.
// Comparing with origin/main would silently skip the just-pushed commit.
val spotlessRatchet = providers.gradleProperty("dstu.spotless.ratchet")
    .orElse(providers.fileContents(layout.projectDirectory.file("config/quality/format-baseline.txt")).asText.map(String::trim))
    .get()
logger.lifecycle("[dstu.quality] spotlessRatchet=$spotlessRatchet")

val verifyModuleRegistry = tasks.register<Exec>("verifyModuleRegistry") {
    group = "verification"
    description = "Checks explicit module includes against actual module build files, including optional benchmarks."
    commandLine("node", "tools/ci/modules.mjs")
}

spotless {
    encoding("UTF-8")
    ratchetFrom(spotlessRatchet)
    kotlin {
        target("**/*.kt")
        targetExclude(
            "**/build/**",
            "**/.gradle/**",
            "**/.kotlin/**",
            "**/node_modules/**",
            "logs/**",
            "third_party/**",
        )
        ktlint(libs.versions.ktlint.get())
            .setEditorConfigPath(rootProject.file(".editorconfig").absolutePath)
    }
    kotlinGradle {
        target("**/*.gradle.kts")
        targetExclude(
            "**/build/**",
            "**/.gradle/**",
            "**/.kotlin/**",
            "**/node_modules/**",
            "logs/**",
            "third_party/**",
        )
        ktlint(libs.versions.ktlint.get())
            .setEditorConfigPath(rootProject.file(".editorconfig").absolutePath)
    }
}

tasks.register("coverageReport") {
    group = "verification"
    description = "Generates JaCoCo reports for the explicit quality coverage scope."
    dependsOn(coverageProjects.map { "$it:jacocoTestReport" })
}

tasks.register("verifyCoverage") {
    group = "verification"
    description = "Enforces the explicit JaCoCo line-coverage gate."
    dependsOn(coverageProjects.map { "$it:jacocoTestCoverageVerification" })
}

tasks.register("qualityGate") {
    group = "verification"
    description = "Runs formatting, build-logic tests, architecture, complexity and coverage gates."
    dependsOn(
        verifyModuleRegistry,
        "spotlessCheck",
        "coverageReport",
        "verifyCoverage",
        "verifyDstu",
        gradle.includedBuild("build-logic").task(":check"),
    )
}

tasks.named("verifyComplexity") {
    dependsOn(gradle.includedBuild("build-logic").task(":checkstyleJavaComplexity"))
}

listOf("prepareDstuArchitectureScan", "verifyArchitectureSources", "verifyComplexityPsi", "detektDstu").forEach { name ->
    tasks.named(name) { dependsOn(":core:design:generateDesignTokens") }
}

listOf("prepareDstuArchitectureScan", "verifyArchitectureSources", "verifyComplexityPsi", "detektDstu").forEach { name ->
    tasks.named(name) { dependsOn(":engines:terminal:prepareTerminalSources") }
}
