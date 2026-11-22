package com.deepstudent.buildlogic.architecture

internal class ScanModel {
    var formatVersion: Int = 1
    var root: String = ""
    var projects: List<ProjectModel> = emptyList()
    var modules: List<ModuleModel> = emptyList()
    var sourceRules: SourceRulesConfig = SourceRulesConfig()
    var architectureExceptions: List<ExceptionConfig> = emptyList()
    var complexityExceptions: List<ExceptionConfig> = emptyList()
    var thresholds: ThresholdConfig = ThresholdConfig()
    var architectureSourceSets: List<String> = emptyList()
    var complexitySourceSets: List<String> = emptyList()
}

internal class ProjectModel {
    var path: String = ""
    var projectDir: String = ""
    var role: String = "unknown"
    var layer: String = "unknown"
    var domain: String? = null
    var capability: String? = null
    var dependencies: List<DependencyModel> = emptyList()
}

internal class DependencyModel {
    var configuration: String = ""
    var targetType: String = ""
    var path: String? = null
    var group: String? = null
    var name: String? = null
    var version: String? = null
}

internal class ModuleModel {
    var path: String = ""
    var role: String = "unknown"
    var layer: String = "unknown"
    var domain: String? = null
    var capability: String? = null
    var sources: List<SourceRootModel> = emptyList()
}

internal class SourceRootModel {
    var dir: String = ""
    var sourceSet: String = ""
}

internal class GraphViolation {
    var code: String = ""
    var project: String = ""
    var target: String? = null
    var group: String? = null
    var configurations: List<String> = emptyList()
    var message: String = ""
}

internal class GraphReport {
    var formatVersion: Int = 1
    var root: String = ""
    var generatedAt: String = ""
    var durationMillis: Long = 0
    var projects: Int = 0
    var edges: List<GraphEdgeReport> = emptyList()
    var externalDependencies: List<GraphExternalReport> = emptyList()
    var cycles: List<List<String>> = emptyList()
    var violations: List<GraphViolation> = emptyList()
}

internal class GraphEdgeReport {
    var from: String = ""
    var to: String = ""
    var configurations: List<String> = emptyList()
    var allowed: Boolean = false
}

internal class GraphExternalReport {
    var project: String = ""
    var group: String = ""
    var name: String = ""
    var version: String? = null
    var configuration: String = ""
    var forbidden: Boolean = false
}
