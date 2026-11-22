package com.deepstudent.buildlogic.architecture

internal class ArchitectureConfig {
    var formatVersion: Int = 0
    var toolchain: ToolchainConfig = ToolchainConfig()
    var productionConfigurations: List<String> = emptyList()
    var roleRules: List<RoleRuleConfig> = emptyList()
    var allowUnknownProjects: Boolean = false
    var edgeRules: List<EdgeRuleConfig> = emptyList()
    var crossDomainApiAllowlist: List<CrossDomainApiAllowRule> = emptyList()
    var forbiddenExternalDependencies: List<ForbiddenExternalConfig> = emptyList()
    var sourceRules: SourceRulesConfig = SourceRulesConfig()
    var additionalSourceRoots: List<AdditionalSourceRootConfig> = emptyList()
    var exceptions: List<ExceptionConfig> = emptyList()
}

internal class ToolchainConfig {
    var gradle: String = ""
    var agp: String = ""
    var kotlin: String = ""
    var jdk: Int = 0
    var detekt: String = ""
    var compileSdk: Int = 0
    var minSdk: Int = 0
    var targetSdk: Int = 0
}

internal class RoleRuleConfig {
    var id: String = ""
    var pattern: String = ""
    var layer: String = ""
    var description: String = ""
}

internal class EdgeRuleConfig {
    var from: List<String> = emptyList()
    var to: List<String> = emptyList()
    var sameDomainFor: List<String> = emptyList()
    var sameCapabilityFor: List<String> = emptyList()
    var description: String = ""
}

internal class ForbiddenExternalConfig {
    var roles: List<String> = emptyList()
    var groups: List<String> = emptyList()
    var description: String = ""
}

internal class CrossDomainApiAllowRule {
    var from: String = ""
    var to: String = ""
    var reason: String = ""
}

internal class SourceRulesConfig {
    var domainPureRoles: List<String> = emptyList()
    var domainForbiddenImportPrefixes: List<String> = emptyList()
    var uiStrictRoles: List<String> = emptyList()
    var uiForbiddenImportPrefixes: List<String> = emptyList()
    var crossDomainInternalExemptRoles: List<String> = emptyList()
    var globalLookupSymbols: List<String> = emptyList()
    var globalLookupAllowedRoles: List<String> = emptyList()
    var globalLookupAllowedFiles: List<String> = emptyList()
    var coreNoPlatformRoles: List<String> = emptyList()
    var coreForbiddenImportPrefixes: List<String> = emptyList()
    var domainPackageSegments: List<String> = emptyList()
    var applicationPackageSegments: List<String> = emptyList()
    var architectureSourceSets: List<String> = emptyList()
    var complexitySourceSets: List<String> = emptyList()
}

internal class AdditionalSourceRootConfig {
    var project: String = ""
    var path: String = ""
    var sourceSet: String = "main"
}

internal class ExceptionConfig {
    var path: String = ""
    var kind: String = ""
    var reason: String = ""
    var owner: String = ""
    var reviewUnit: String = ""
    var test: String = ""
}

internal class ComplexityConfig {
    var formatVersion: Int = 0
    var detekt: DetektConfig = DetektConfig()
    var thresholds: ThresholdConfig = ThresholdConfig()
    var exceptions: List<ExceptionConfig> = emptyList()
}

internal class DetektConfig {
    var version: String = ""
    var configFile: String = ""
    var maxIssues: Int = -1
}

internal class ThresholdConfig {
    var functionEffectiveLines: Int = 0
    var classEffectiveLines: Int = 0
    var fileEffectiveLines: Int = 0
    var cyclomaticComplexity: Int = 0
    var nestedBlockDepth: Int = 0
    var constructorParameters: Int = 0
}
