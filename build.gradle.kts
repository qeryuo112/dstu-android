plugins {
    id("dstu.architecture")
    alias(libs.plugins.android.application) apply false
    alias(libs.plugins.android.library) apply false
    alias(libs.plugins.kotlin.android) apply false
    alias(libs.plugins.kotlin.jvm) apply false
    alias(libs.plugins.kotlin.compose) apply false
    alias(libs.plugins.kotlin.serialization) apply false
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
