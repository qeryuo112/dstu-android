package com.deepstudent.buildlogic.architecture

import com.deepstudent.buildlogic.convention.DstuVersions
import com.dstu.architecture.analyzer.AnalyzerMain
import com.google.gson.GsonBuilder
import org.gradle.api.GradleException
import org.gradle.api.Plugin
import org.gradle.api.Project
import org.gradle.api.Task
import org.gradle.api.artifacts.Configuration
import org.gradle.api.file.DirectoryProperty
import org.gradle.api.file.FileCollection
import org.gradle.api.file.RegularFileProperty
import org.gradle.api.provider.Property
import org.gradle.api.tasks.TaskProvider
import java.io.File

abstract class DstuArchitectureExtension {
    abstract val architectureConfig: RegularFileProperty
    abstract val complexityConfig: RegularFileProperty
    abstract val reportDirectory: DirectoryProperty
}

private class AnalyzerRegistration(
    val project: Project,
    val extension: DstuArchitectureExtension,
    val analyzerRuntime: Configuration,
    val analyzerClasspath: FileCollection,
    val scanModelJson: Property<String>,
    val prepare: TaskProvider<PrepareArchitectureScanTask>
)

private class AnalyzerTaskSpec(
    val name: String,
    val mode: String,
    val reportName: String,
    val description: String
)

/**
 * Root-only architecture gate.
 *
 * <p>The task pair reads the real Gradle project/configuration graph and runs
 * isolated Kotlin PSI structural analysis. The configuration file is the single
 * incrementally updatable registry for module roles, allowed edges, forbidden
 * groups, source rules and exact generated/vendor exceptions.</p>
 */
class DstuArchitecturePlugin : Plugin<Project> {
    override fun apply(project: Project) {
        requireRoot(project)
        DstuVersions.requireFixedGradle()
        val extension = createExtension(project)
        val analyzerRuntime = createAnalyzerRuntime(project)
        val detektCli = createDetektCli(project)
        val analyzerClasspath = analyzerClasspath(project)
        val scanModelJson = project.objects.property(String::class.java).convention("{}")
        val detekt = registerVerificationTasks(
            project,
            extension,
            analyzerRuntime,
            detektCli,
            analyzerClasspath,
            scanModelJson
        )
        project.gradle.projectsEvaluated {
            configureLoadedModel(project, detektCli, detekt, scanModelJson)
        }
        project.logger.lifecycle(
            "[dstu.architecture] plugin=applied project=${project.path} gradle=${DstuVersions.GRADLE} " +
                "kotlin=${DstuVersions.KOTLIN} detekt=${DstuVersions.DETEKT}"
        )
    }

    private fun requireRoot(project: Project) {
        if (project != project.rootProject) {
            throw GradleException(
                "DSTU_ARCHITECTURE_ROOT_ONLY project=${project.path} " +
                    "reason=apply dstu.architecture on the root build"
            )
        }
    }

    private fun createExtension(project: Project): DstuArchitectureExtension {
        val extension = project.extensions.create(
            "dstuArchitecture",
            DstuArchitectureExtension::class.java
        )
        extension.architectureConfig.convention(
            project.layout.projectDirectory.file("config/architecture/architecture.json")
        )
        extension.complexityConfig.convention(
            project.layout.projectDirectory.file("config/architecture/complexity.json")
        )
        extension.reportDirectory.convention(
            project.layout.buildDirectory.dir("reports/dstu-architecture")
        )
        return extension
    }

    private fun createAnalyzerRuntime(project: Project): Configuration =
        project.configurations.create("dstuArchitectureAnalyzerRuntime") {
            isCanBeConsumed = false
            isCanBeResolved = true
            description = "Isolated Kotlin PSI runtime for dstu architecture analysis"
            defaultDependencies {
                add(
                    project.dependencies.create(
                        "org.jetbrains.kotlin:kotlin-compiler-embeddable:${DstuVersions.KOTLIN}"
                    )
                )
                add(project.dependencies.create("com.google.code.gson:gson:${DstuVersions.GSON}"))
            }
        }

    private fun createDetektCli(project: Project): Configuration =
        project.configurations.create("dstuDetektCli") {
            isCanBeConsumed = false
            isCanBeResolved = true
            description = "Fixed detekt 1.23.8 CLI for dstu complexity verification"
        }

    private fun analyzerClasspath(project: Project): FileCollection {
        val location = AnalyzerMain::class.java.protectionDomain.codeSource.location
        return project.files(File(location.toURI()))
    }

    private fun registerVerificationTasks(
        project: Project,
        extension: DstuArchitectureExtension,
        analyzerRuntime: Configuration,
        detektCli: Configuration,
        analyzerClasspath: FileCollection,
        scanModelJson: Property<String>
    ): TaskProvider<DstuDetektTask> {
        val prepare = registerPrepare(project, scanModelJson)
        val graph = registerGraph(project, extension, scanModelJson)
        val registration = AnalyzerRegistration(
            project,
            extension,
            analyzerRuntime,
            analyzerClasspath,
            scanModelJson,
            prepare
        )
        val sources = registerAnalyzer(
            registration,
            AnalyzerTaskSpec(
                "verifyArchitectureSources",
                "architecture",
                "architecture-sources.json",
                "Verifies Kotlin PSI imports, package boundaries and global graph lookups."
            )
        )
        val detekt = registerDetekt(project, extension, detektCli, scanModelJson)
        val buildLogicDetekt = registerBuildLogicDetekt(project, extension, detektCli)
        val complexityPsi = registerAnalyzer(
            registration,
            AnalyzerTaskSpec(
                "verifyComplexityPsi",
                "complexity",
                "complexity-psi.json",
                "Verifies PSI effective lines, cyclomatic complexity, nesting and constructor parameters."
            )
        )
        val verifyArchitecture = registerLifecycle(
            project,
            "verifyArchitecture",
            "Runs the real Gradle graph and Kotlin PSI architecture gates.",
            graph,
            sources
        )
        val verifyComplexity = registerLifecycle(
            project,
            "verifyComplexity",
            "Runs fixed detekt and independent PSI complexity gates.",
            detekt,
            buildLogicDetekt,
            complexityPsi
        )
        registerLifecycle(
            project,
            "verifyDstu",
            "Runs all S1.01 architecture and complexity gates.",
            verifyArchitecture,
            verifyComplexity
        )
        return detekt
    }

    private fun registerPrepare(
        project: Project,
        scanModelJson: Property<String>
    ): TaskProvider<PrepareArchitectureScanTask> =
        project.tasks.register(
            "prepareDstuArchitectureScan",
            PrepareArchitectureScanTask::class.java
        ) {
            group = "verification"
            description = "Writes the architecture/complexity scan manifest."
            modelJson.set(scanModelJson)
            manifestFile.set(
                project.layout.buildDirectory.file("intermediates/dstu-architecture/scan-manifest.json")
            )
        }

    private fun registerGraph(
        project: Project,
        extension: DstuArchitectureExtension,
        scanModelJson: Property<String>
    ): TaskProvider<VerifyArchitectureGraphTask> =
        project.tasks.register(
            "verifyArchitectureGraph",
            VerifyArchitectureGraphTask::class.java
        ) {
            group = "verification"
            description = "Verifies real Gradle project edges, forbidden groups and cycles."
            modelJson.set(scanModelJson)
            configFile.set(extension.architectureConfig)
            reportFile.set(extension.reportDirectory.file("architecture-graph.json"))
        }

    private fun registerAnalyzer(
        registration: AnalyzerRegistration,
        spec: AnalyzerTaskSpec
    ): TaskProvider<DstuAnalyzerTask> =
        registration.project.tasks.register(spec.name, DstuAnalyzerTask::class.java) {
            group = "verification"
            description = spec.description
            dependsOn(registration.prepare)
            modelJson.set(registration.scanModelJson)
            manifestFile.set(registration.prepare.flatMap { it.manifestFile })
            reportFile.set(registration.extension.reportDirectory.file(spec.reportName))
            mode.set(spec.mode)
            sourceFiles.from(
                registration.project.provider {
                    scanSourceDirectories(registration.scanModelJson.get(), spec.mode)
                }
            )
            analyzerVersion.set("psi-${DstuVersions.KOTLIN}")
            mainClass.set("com.dstu.architecture.analyzer.AnalyzerMain")
            classpath = registration.analyzerClasspath + registration.analyzerRuntime
            jvmArgs("-Xmx1g", "-Dfile.encoding=UTF-8")
        }

    private fun registerDetekt(
        project: Project,
        extension: DstuArchitectureExtension,
        detektCli: Configuration,
        scanModelJson: Property<String>
    ): TaskProvider<DstuDetektTask> =
        project.tasks.register("detektDstu", DstuDetektTask::class.java) {
            group = "verification"
            description = "Runs fixed detekt 1.23.8 complexity rules."
            modelJson.set(scanModelJson)
            sourceFiles.from(project.provider {
                scanSourceDirectories(scanModelJson.get(), "complexity")
            })
            detektVersion.set(DstuVersions.DETEKT)
            xmlReport.set(extension.reportDirectory.file("detekt/detekt.xml"))
            textReport.set(extension.reportDirectory.file("detekt/detekt.txt"))
            mainClass.set("io.gitlab.arturbosch.detekt.cli.Main")
            classpath = detektCli
            jvmArgs("-Xmx1g", "-Dfile.encoding=UTF-8")
        }

    private fun registerBuildLogicDetekt(
        project: Project,
        extension: DstuArchitectureExtension,
        detektCli: Configuration
    ): TaskProvider<DstuBuildLogicDetektTask> =
        project.tasks.register("detektBuildLogic", DstuBuildLogicDetektTask::class.java) {
            group = "verification"
            description = "Runs fixed detekt against build-logic's own Kotlin sources."
            sourceDirectory.set(project.layout.projectDirectory.dir("build-logic/src/main/kotlin"))
            detektConfigFile.set(extension.complexityConfig.map { complexityFile ->
                val config = com.google.gson.Gson().fromJson(
                    complexityFile.asFile.readText(Charsets.UTF_8),
                    ComplexityConfig::class.java
                )
                project.layout.projectDirectory.file(config.detekt.configFile)
            })
            detektVersion.set(DstuVersions.DETEKT)
            xmlReport.set(extension.reportDirectory.file("detekt/build-logic.xml"))
            textReport.set(extension.reportDirectory.file("detekt/build-logic.txt"))
            mainClass.set("io.gitlab.arturbosch.detekt.cli.Main")
            classpath = detektCli
            jvmArgs("-Xmx1g", "-Dfile.encoding=UTF-8")
            onlyIf { sourceDirectory.get().asFile.isDirectory }
        }

    private fun registerLifecycle(
        project: Project,
        name: String,
        description: String,
        vararg dependencies: TaskProvider<*>
    ): TaskProvider<Task> =
        project.tasks.register(name) {
            group = "verification"
            this.description = description
            dependsOn(dependencies)
        }

    private fun configureLoadedModel(
        project: Project,
        detektCli: Configuration,
        detekt: TaskProvider<DstuDetektTask>,
        scanModelJson: Property<String>
    ) {
        val loaded = ArchitectureConfigLoader().load(project.rootProject.projectDir)
        val model = ArchitectureModelBuilder().build(project, loaded)
        scanModelJson.set(GsonBuilder().setPrettyPrinting().create().toJson(model))
        detekt.configure {
            detektConfigFile.set(loaded.detektConfigFile)
            detektVersion.set(loaded.complexity.detekt.version)
            onlyIf { hasKotlinSources(model) }
        }
        detektCli.defaultDependencies {
            add(
                project.dependencies.create(
                    "io.gitlab.arturbosch.detekt:detekt-cli:${loaded.complexity.detekt.version}"
                )
            )
        }
        project.logger.lifecycle(
            "[dstu.architecture] config=loaded modules=${model.modules.size} " +
                "projects=${model.projects.size} " +
                "architectureSourceSets=${model.architectureSourceSets.joinToString(",")} " +
                "complexitySourceSets=${model.complexitySourceSets.joinToString(",")} " +
                "detekt=${loaded.complexity.detekt.version} " +
                "architectureConfig=${loaded.architectureFile.absolutePath} " +
                "complexityConfig=${loaded.complexityFile.absolutePath}"
        )
    }

    private fun hasKotlinSources(model: ScanModel): Boolean {
        val sourceSets = model.complexitySourceSets.toSet()
        return model.modules.asSequence()
            .flatMap { it.sources.asSequence() }
            .filter { it.sourceSet in sourceSets }
            .map { File(it.dir) }
            .filter { it.isDirectory }
            .any { directory ->
                directory.walkTopDown().any { file -> file.isFile && file.extension == "kt" }
            }
    }
}
