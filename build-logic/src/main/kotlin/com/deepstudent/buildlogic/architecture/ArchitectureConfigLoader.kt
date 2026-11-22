package com.deepstudent.buildlogic.architecture

import com.deepstudent.buildlogic.convention.DstuVersions
import com.google.gson.Gson
import org.gradle.api.GradleException
import java.io.File

internal class LoadedArchitectureConfig(
    val architecture: ArchitectureConfig,
    val complexity: ComplexityConfig,
    val architectureFile: File,
    val complexityFile: File,
    val detektConfigFile: File
)

internal class RoleMatch(
    val id: String,
    val layer: String,
    val domain: String?,
    val capability: String?
) {
    companion object {
        val UNKNOWN = RoleMatch("unknown", "unknown", null, null)
    }
}

internal class ArchitectureConfigLoader(private val gson: Gson = Gson()) {
    fun load(rootDir: File): LoadedArchitectureConfig {
        val architectureFile = File(rootDir, "config/architecture/architecture.json")
        val complexityFile = File(rootDir, "config/architecture/complexity.json")
        val errors = mutableListOf<String>()
        if (!architectureFile.isFile) {
            errors += "missing architecture config: ${architectureFile.absolutePath}"
        }
        if (!complexityFile.isFile) {
            errors += "missing complexity config: ${complexityFile.absolutePath}"
        }
        if (errors.isNotEmpty()) {
            fail(errors)
        }
        val architecture = runCatching {
            gson.fromJson(architectureFile.readText(Charsets.UTF_8), ArchitectureConfig::class.java)
        }.getOrElse {
            throw GradleException(
                "DSTU_ARCH_CONFIG_INVALID file=${architectureFile.absolutePath} " +
                    "errors=[json parse failed: ${it.message}]"
            )
        }
        val complexity = runCatching {
            gson.fromJson(complexityFile.readText(Charsets.UTF_8), ComplexityConfig::class.java)
        }.getOrElse {
            throw GradleException(
                "DSTU_ARCH_CONFIG_INVALID file=${complexityFile.absolutePath} " +
                    "errors=[json parse failed: ${it.message}]"
            )
        }
        if (architecture == null || complexity == null) {
            fail(listOf("config JSON is empty"))
        }
        val detektConfigFile = File(rootDir, complexity.detekt.configFile)
        validateArchitecture(architecture, architectureFile, errors)
        validateComplexity(complexity, complexityFile, detektConfigFile, errors)
        validateExceptions(architecture.exceptions, rootDir, architectureFile, errors)
        validateExceptions(complexity.exceptions, rootDir, complexityFile, errors)
        validateRoleRuleReferences(architecture, errors)
        if (errors.isNotEmpty()) {
            fail(errors, architectureFile)
        }
        return LoadedArchitectureConfig(
            architecture = architecture,
            complexity = complexity,
            architectureFile = architectureFile,
            complexityFile = complexityFile,
            detektConfigFile = detektConfigFile
        )
    }

    private fun validateArchitecture(
        config: ArchitectureConfig,
        file: File,
        errors: MutableList<String>
    ) {
        if (config.formatVersion != 1) {
            errors += "architecture.formatVersion must be 1, actual=${config.formatVersion}"
        }
        validateToolchain(config.toolchain, errors)
        validateRoleRules(config, errors)
        validateEdges(config, errors)
        validateSourceRules(config.sourceRules, errors)
        if (config.additionalSourceRoots.any {
                it.project.isBlank() || it.path.isBlank() || it.sourceSet.isBlank()
            }
        ) {
            errors += "additionalSourceRoots entries require project, path and sourceSet"
        }
        if (!file.isFile) {
            errors += "architecture config does not exist: ${file.absolutePath}"
        }
    }

    private fun validateToolchain(toolchain: ToolchainConfig, errors: MutableList<String>) {
        val checks = listOf(
            (toolchain.gradle == DstuVersions.GRADLE) to
                "toolchain.gradle must be ${DstuVersions.GRADLE}, actual=${toolchain.gradle}",
            (toolchain.agp == DstuVersions.AGP) to
                "toolchain.agp must be ${DstuVersions.AGP}, actual=${toolchain.agp}",
            (toolchain.kotlin == DstuVersions.KOTLIN) to
                "toolchain.kotlin must be ${DstuVersions.KOTLIN}, actual=${toolchain.kotlin}",
            (toolchain.jdk == DstuVersions.JDK) to
                "toolchain.jdk must be ${DstuVersions.JDK}, actual=${toolchain.jdk}",
            (toolchain.detekt == DstuVersions.DETEKT) to
                "toolchain.detekt must be ${DstuVersions.DETEKT}, actual=${toolchain.detekt}",
            (toolchain.compileSdk == DstuVersions.COMPILE_SDK) to
                "toolchain.compileSdk must be ${DstuVersions.COMPILE_SDK}, actual=${toolchain.compileSdk}",
            (toolchain.minSdk == DstuVersions.MIN_SDK) to
                "toolchain.minSdk must be ${DstuVersions.MIN_SDK}, actual=${toolchain.minSdk}",
            (toolchain.targetSdk == DstuVersions.TARGET_SDK) to
                "toolchain.targetSdk must be ${DstuVersions.TARGET_SDK}, actual=${toolchain.targetSdk}"
        )
        checks.filterNot { it.first }.forEach { errors += it.second }
    }

    private fun validateRoleRules(config: ArchitectureConfig, errors: MutableList<String>) {
        if (config.productionConfigurations.isEmpty()) {
            errors += "productionConfigurations must not be empty"
        }
        if (config.roleRules.isEmpty()) {
            errors += "roleRules must not be empty"
        }
        val roleIds = config.roleRules.map { it.id }
        if (roleIds.any { it.isBlank() }) {
            errors += "roleRules[].id must not be blank"
        }
        if (roleIds.toSet().size != roleIds.size) {
            errors += "roleRules[].id must be unique"
        }
        config.roleRules.forEach { rule -> validateRoleRule(rule, errors) }
    }

    private fun validateRoleRule(rule: RoleRuleConfig, errors: MutableList<String>) {
        if (rule.pattern.isBlank()) {
            errors += "role ${rule.id} has blank pattern"
        } else {
            runCatching { Regex(rule.pattern) }.onFailure {
                errors += "role ${rule.id} has invalid regex: ${it.message}"
            }
        }
        if (rule.layer.isBlank()) {
            errors += "role ${rule.id} has blank layer"
        }
    }

    private fun validateEdges(config: ArchitectureConfig, errors: MutableList<String>) {
        if (config.edgeRules.isEmpty()) {
            errors += "edgeRules must not be empty"
        }
        config.edgeRules.forEachIndexed { index, rule ->
            if (rule.from.isEmpty()) {
                errors += "edgeRules[$index].from must not be empty"
            }
            // An empty `to` list is an explicit deny rule (for example
            // third_party must not depend on any host project).
        }
        config.crossDomainApiAllowlist.forEachIndexed { index, rule ->
            if (rule.from.isBlank() || rule.to.isBlank() || rule.reason.isBlank()) {
                errors += "crossDomainApiAllowlist[$index] requires from, to and reason"
            }
        }
        if (config.forbiddenExternalDependencies.any { it.roles.isEmpty() || it.groups.isEmpty() }) {
            errors += "forbiddenExternalDependencies entries require roles and groups"
        }
    }

    private fun validateSourceRules(source: SourceRulesConfig, errors: MutableList<String>) {
        if (source.domainPureRoles.isEmpty()) {
            errors += "sourceRules.domainPureRoles must not be empty"
        }
        if (source.domainForbiddenImportPrefixes.isEmpty()) {
            errors += "sourceRules.domainForbiddenImportPrefixes must not be empty"
        }
        if (source.globalLookupSymbols.isEmpty()) {
            errors += "sourceRules.globalLookupSymbols must not be empty"
        }
        if (source.architectureSourceSets.isEmpty()) {
            errors += "sourceRules.architectureSourceSets must not be empty"
        }
        if (source.complexitySourceSets.isEmpty()) {
            errors += "sourceRules.complexitySourceSets must not be empty"
        }
        if (source.coreForbiddenImportPrefixes.isEmpty()) {
            errors += "sourceRules.coreForbiddenImportPrefixes must not be empty"
        }
        if (source.domainPackageSegments.isEmpty()) {
            errors += "sourceRules.domainPackageSegments must not be empty"
        }
        if (source.applicationPackageSegments.isEmpty()) {
            errors += "sourceRules.applicationPackageSegments must not be empty"
        }
    }

    private fun validateComplexity(
        config: ComplexityConfig,
        file: File,
        detektConfigFile: File,
        errors: MutableList<String>
    ) {
        val checks = listOf(
            (config.formatVersion == 1) to
                "complexity.formatVersion must be 1, actual=${config.formatVersion}",
            (config.detekt.version == DstuVersions.DETEKT) to
                "detekt.version must be ${DstuVersions.DETEKT}, actual=${config.detekt.version}",
            (config.detekt.configFile.isNotBlank()) to "detekt.configFile must not be blank",
            (config.detekt.maxIssues == 0) to
                "detekt.maxIssues must be 0, actual=${config.detekt.maxIssues}"
        )
        checks.filterNot { it.first }.forEach { errors += it.second }
        if (!detektConfigFile.isFile) {
            errors += "detekt config does not exist: ${detektConfigFile.absolutePath}"
        }
        val thresholds = config.thresholds
        val thresholdChecks = listOf(
            Triple("functionEffectiveLines", thresholds.functionEffectiveLines, 60),
            Triple("classEffectiveLines", thresholds.classEffectiveLines, 300),
            Triple("fileEffectiveLines", thresholds.fileEffectiveLines, 500),
            Triple("cyclomaticComplexity", thresholds.cyclomaticComplexity, 10),
            Triple("nestedBlockDepth", thresholds.nestedBlockDepth, 4),
            Triple("constructorParameters", thresholds.constructorParameters, 8)
        )
        thresholdChecks.filter { it.second != it.third }.forEach {
            errors += "thresholds.${it.first} must be ${it.third}"
        }
        if (!file.isFile) {
            errors += "complexity config does not exist: ${file.absolutePath}"
        }
    }

    private fun validateExceptions(
        exceptions: List<ExceptionConfig>,
        rootDir: File,
        configFile: File,
        errors: MutableList<String>
    ) {
        exceptions.forEachIndexed { index, exception ->
            validateException(index, exception, rootDir, configFile, errors)
        }
    }

    private fun validateException(
        index: Int,
        exception: ExceptionConfig,
        rootDir: File,
        configFile: File,
        errors: MutableList<String>
    ) {
        val prefix = "exceptions[$index] in ${configFile.name}"
        if (exception.kind !in setOf("generated", "vendor")) {
            errors += "$prefix kind must be generated or vendor, actual=${exception.kind}"
        }
        validateExceptionPath(prefix, exception, rootDir, errors)
        val metadata = listOf(
            "reason" to exception.reason,
            "owner" to exception.owner,
            "reviewUnit" to exception.reviewUnit,
            "test" to exception.test
        )
        metadata.filter { it.second.isBlank() }.forEach {
            errors += "$prefix ${it.first} must not be blank"
        }
    }

    private fun validateExceptionPath(
        prefix: String,
        exception: ExceptionConfig,
        rootDir: File,
        errors: MutableList<String>
    ) {
        val invalid = exception.path.isBlank() || exception.path.startsWith("/") ||
            exception.path.contains("..") || exception.path.contains("*")
        if (invalid) {
            errors += "$prefix path must be an exact relative file path, actual=${exception.path}"
        } else if (!File(rootDir, exception.path).isFile) {
            errors += "$prefix path does not exist: ${exception.path}"
        }
    }

    private fun validateRoleRuleReferences(config: ArchitectureConfig, errors: MutableList<String>) {
        val roleIds = config.roleRules.map { it.id }
        val patterns = config.edgeRules.flatMap { it.from + it.to } +
            config.edgeRules.flatMap { it.sameDomainFor + it.sameCapabilityFor } +
            config.forbiddenExternalDependencies.flatMap { it.roles } +
            config.sourceRules.domainPureRoles +
            config.sourceRules.uiStrictRoles +
            config.sourceRules.globalLookupAllowedRoles +
            config.sourceRules.coreNoPlatformRoles
        patterns.distinct().forEach { pattern ->
            if (pattern != "*" && roleIds.none { id -> matches(pattern, id) }) {
                errors += "role pattern references no known role: $pattern"
            }
        }
    }

    private fun fail(errors: List<String>, file: File? = null) {
        val location = file?.let { " file=${it.absolutePath}" }.orEmpty()
        throw GradleException(
            "DSTU_ARCH_CONFIG_INVALID$location errors=[${errors.joinToString("; ")}]"
        )
    }
}

internal fun matches(pattern: String, value: String): Boolean {
    if (pattern == "*") {
        return true
    }
    val regex = pattern.split('*').joinToString(".*") { Regex.escape(it) }
    return Regex("^$regex$").matches(value)
}

internal fun matchesAny(patterns: List<String>, value: String): Boolean =
    patterns.any { matches(it, value) }
