package com.deepstudent.buildlogic.architecture

import org.gradle.api.Project
import org.gradle.api.artifacts.ModuleDependency
import org.gradle.api.artifacts.ProjectDependency
import java.io.File

internal class ArchitectureModelBuilder {
    fun build(project: Project, loaded: LoadedArchitectureConfig): ScanModel {
        val config = loaded.architecture
        val root = project.rootProject.projectDir
        val sourceSets = (config.sourceRules.architectureSourceSets +
            config.sourceRules.complexitySourceSets).distinct()
        val projects = project.rootProject.allprojects
            .filter { it != project.rootProject && isModuleProject(it) }
            .sortedBy { it.path }

        val projectModels = projects.map { child ->
            val role = classify(child.path, config)
            ProjectModel().apply {
                path = child.path
                projectDir = child.projectDir.absolutePath
                this.role = role.id
                layer = role.layer
                domain = role.domain
                capability = role.capability
                dependencies = dependenciesOf(child, config)
            }
        }
        val moduleModels = projects.map { child ->
            val role = classify(child.path, config)
            ModuleModel().apply {
                path = child.path
                this.role = role.id
                layer = role.layer
                domain = role.domain
                capability = role.capability
                sources = sourceRoots(child, sourceSets, config)
            }
        }
        return ScanModel().apply {
            this.root = root.absolutePath
            this.projects = projectModels
            this.modules = moduleModels
            sourceRules = config.sourceRules
            architectureExceptions = config.exceptions
            complexityExceptions = loaded.complexity.exceptions
            thresholds = loaded.complexity.thresholds
            architectureSourceSets = config.sourceRules.architectureSourceSets
            complexitySourceSets = config.sourceRules.complexitySourceSets
        }
    }

    fun classify(path: String, config: ArchitectureConfig): RoleMatch {
        for (rule in config.roleRules) {
            val match = runCatching { Regex(rule.pattern).matchEntire(path) }.getOrNull() ?: continue
            val domain = if (rule.pattern.contains("?<domain>")) {
                match.groups["domain"]?.value
            } else {
                null
            }
            val capability = if (rule.pattern.contains("?<capability>")) {
                match.groups["capability"]?.value
            } else {
                null
            }
            return RoleMatch(rule.id, rule.layer, domain, capability)
        }
        return RoleMatch.UNKNOWN
    }

    private fun isModuleProject(project: Project): Boolean {
        if (project.buildFile.isFile ||
            project.projectDir.resolve("build.gradle.kts").isFile ||
            project.projectDir.resolve("build.gradle").isFile
        ) {
            return true
        }
        if (listOf("src/main/kotlin", "src/main/java", "src/main").any {
                File(project.projectDir, it).exists()
            }
        ) {
            return true
        }
        return project.configurations.any { it.dependencies.isNotEmpty() }
    }

    private fun dependenciesOf(project: Project, config: ArchitectureConfig): List<DependencyModel> {
        return config.productionConfigurations.flatMap { configurationName ->
            val configuration = project.configurations.findByName(configurationName)
            configuration?.dependencies?.mapNotNull { dependency ->
                dependencyModel(configurationName, dependency)
            } ?: emptyList()
        }
    }

    private fun dependencyModel(
        configurationName: String,
        dependency: org.gradle.api.artifacts.Dependency
    ): DependencyModel? = when (dependency) {
        is ProjectDependency -> DependencyModel().apply {
            configuration = configurationName
            targetType = "project"
            path = dependency.path
        }
        is ModuleDependency -> externalDependency(configurationName, dependency)
        else -> null
    }

    private fun externalDependency(
        configurationName: String,
        dependency: ModuleDependency
    ): DependencyModel? {
        if (dependency.group.isNullOrBlank() || dependency.name.isBlank()) {
            return null
        }
        return DependencyModel().apply {
            configuration = configurationName
            targetType = "external"
            group = dependency.group
            name = dependency.name
            version = dependency.version
        }
    }

    private fun sourceRoots(
        project: Project,
        sourceSets: List<String>,
        config: ArchitectureConfig
    ): List<SourceRootModel> {
        val roots = linkedMapOf<String, SourceRootModel>()
        sourceSets.forEach { sourceSet -> addDefaultRoots(project, sourceSet, roots) }
        config.additionalSourceRoots
            .filter { it.project == project.path }
            .forEach { additional -> addAdditionalRoot(project, additional, roots) }
        return roots.values.toList()
    }

    private fun addDefaultRoots(
        project: Project,
        sourceSet: String,
        roots: MutableMap<String, SourceRootModel>
    ) {
        listOf("kotlin", "java").forEach { relative ->
            val directory = File(project.projectDir, "src/$sourceSet/$relative")
            if (directory.isDirectory) {
                roots[directory.absolutePath] = sourceRoot(directory, sourceSet)
            }
        }
    }

    private fun addAdditionalRoot(
        project: Project,
        additional: AdditionalSourceRootConfig,
        roots: MutableMap<String, SourceRootModel>
    ) {
        val directory = if (File(additional.path).isAbsolute) {
            File(additional.path)
        } else {
            File(project.projectDir, additional.path)
        }
        roots[directory.absolutePath] = sourceRoot(directory, additional.sourceSet)
    }

    private fun sourceRoot(directory: File, sourceSet: String): SourceRootModel =
        SourceRootModel().apply {
            dir = directory.absolutePath
            this.sourceSet = sourceSet
        }
}
