package com.deepstudent.buildlogic.architecture

internal class DependencyScanResult(
    val edges: Map<Pair<String, String>, Set<String>>,
    val external: List<GraphExternalReport>,
    val violations: List<GraphViolation>
)

/**
 * Reads declared project edges and external module dependencies from the
 * configuration-time model captured from real Gradle configurations.
 */
internal class DependencyGraphScanner {
    fun scan(
        model: ScanModel,
        config: ArchitectureConfig,
        projectByPath: Map<String, ProjectModel>
    ): DependencyScanResult {
        val edges = linkedMapOf<Pair<String, String>, MutableSet<String>>()
        val external = mutableListOf<GraphExternalReport>()
        val violations = mutableListOf<GraphViolation>()
        model.projects.forEach { project ->
            project.dependencies.forEach { dependency ->
                when (dependency.targetType) {
                    "project" -> scanProjectDependency(project, dependency, projectByPath, edges, violations)
                    "external" -> scanExternalDependency(project, dependency, config, external, violations)
                }
            }
        }
        return DependencyScanResult(edges, external, violations)
    }

    private fun scanProjectDependency(
        project: ProjectModel,
        dependency: DependencyModel,
        projectByPath: Map<String, ProjectModel>,
        edges: MutableMap<Pair<String, String>, MutableSet<String>>,
        violations: MutableList<GraphViolation>
    ) {
        val target = dependency.path ?: return
        if (target == project.path) {
            violations += graphViolation(
                "ARCH_SELF_DEPENDENCY",
                project.path,
                target,
                listOf(dependency.configuration),
                "project depends on itself"
            )
        }
        if (target !in projectByPath) {
            violations += graphViolation(
                "ARCH_UNKNOWN_PROJECT",
                project.path,
                target,
                listOf(dependency.configuration),
                "project dependency target is not part of this Gradle build"
            )
            return
        }
        edges.getOrPut(project.path to target) { linkedSetOf() }.add(dependency.configuration)
    }

    private fun scanExternalDependency(
        project: ProjectModel,
        dependency: DependencyModel,
        config: ArchitectureConfig,
        external: MutableList<GraphExternalReport>,
        violations: MutableList<GraphViolation>
    ) {
        val group = dependency.group.orEmpty()
        val name = dependency.name.orEmpty()
        val forbidden = externalForbidden(config, project.role, group)
        external += GraphExternalReport().apply {
            this.project = project.path
            this.group = group
            this.name = name
            version = dependency.version
            configuration = dependency.configuration
            this.forbidden = forbidden
        }
        if (forbidden) {
            violations += graphViolation(
                "ARCH_FORBIDDEN_EXTERNAL_DEPENDENCY",
                project.path,
                "$group:$name",
                listOf(dependency.configuration),
                "role ${project.role} must not link group $group"
            )
        }
    }

    private fun externalForbidden(
        config: ArchitectureConfig,
        sourceRole: String,
        group: String
    ): Boolean {
        if (group.isBlank()) {
            return false
        }
        return config.forbiddenExternalDependencies.any { rule ->
            matchesAny(rule.roles, sourceRole) && rule.groups.any { prefix ->
                group == prefix || group.startsWith("$prefix.")
            }
        }
    }
}

internal fun graphViolation(
    code: String,
    project: String,
    target: String? = null,
    configurations: List<String> = emptyList(),
    message: String
): GraphViolation = GraphViolation().apply {
    this.code = code
    this.project = project
    this.target = target
    this.configurations = configurations
    this.message = message
}
