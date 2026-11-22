package com.deepstudent.buildlogic.architecture

import com.google.gson.Gson
import com.google.gson.GsonBuilder
import org.gradle.api.DefaultTask
import org.gradle.api.GradleException
import org.gradle.api.file.RegularFileProperty
import org.gradle.api.provider.Property
import org.gradle.api.tasks.Input
import org.gradle.api.tasks.InputFile
import org.gradle.api.tasks.OutputFile
import org.gradle.api.tasks.PathSensitive
import org.gradle.api.tasks.PathSensitivity
import org.gradle.api.tasks.TaskAction
import java.time.Instant

private class EdgeDecision(
    val allowed: Boolean,
    val code: String? = null,
    val message: String? = null
)

/**
 * Verifies the real Gradle project/configuration graph:
 * declared project dependency edges, forbidden external groups, and cycles.
 */
@org.gradle.work.DisableCachingByDefault(because = "Graph evidence contains local paths and execution timestamps")
abstract class VerifyArchitectureGraphTask : DefaultTask() {
    @get:Input
    abstract val modelJson: Property<String>

    @get:InputFile
    @get:PathSensitive(PathSensitivity.RELATIVE)
    abstract val configFile: RegularFileProperty

    @get:OutputFile
    abstract val reportFile: RegularFileProperty

    @TaskAction
    fun verify() {
        val started = System.nanoTime()
        val gson = GsonBuilder().setPrettyPrinting().create()
        val model = gson.fromJson(modelJson.get(), ScanModel::class.java)
        val config = Gson().fromJson(
            configFile.get().asFile.readText(Charsets.UTF_8),
            ArchitectureConfig::class.java
        )
        val projectByPath = model.projects.associateBy { it.path }
        val scan = DependencyGraphScanner().scan(model, config, projectByPath)
        val (edgeReports, edgeViolations) = reviewEdges(config, projectByPath, scan.edges)
        val cycles = CycleDetector().find(scan.edges.keys)
        val violations = (
            unknownProjectViolations(model) +
                scan.violations +
                edgeViolations +
                cycleViolations(cycles)
            ).sortedWith(
            compareBy<GraphViolation> { it.code }
                .thenBy { it.project }
                .thenBy { it.target.orEmpty() }
        )
        val report = GraphReport().apply {
            root = model.root
            generatedAt = Instant.now().toString()
            durationMillis = (System.nanoTime() - started) / 1_000_000L
            projects = model.projects.size
            this.edges = edgeReports
            externalDependencies = scan.external.sortedWith(
                compareBy<GraphExternalReport> { it.project }
                    .thenBy { it.group }
                    .thenBy { it.name }
            )
            this.cycles = cycles
            this.violations = violations
        }
        writeReport(gson, report)
        logReport(report, scan.external.size, cycles.size, edgeReports.size)
        if (violations.isNotEmpty()) {
            failGraph(violations)
        }
    }

    private fun unknownProjectViolations(model: ScanModel): List<GraphViolation> =
        model.projects
            .filter { it.role == RoleMatch.UNKNOWN.id }
            .map {
                graphViolation(
                    "ARCH_UNKNOWN_PROJECT",
                    it.path,
                    message = "project is not covered by config/architecture roleRules; " +
                        "register an exact role before adding the module"
                )
            }

    private fun reviewEdges(
        config: ArchitectureConfig,
        projectByPath: Map<String, ProjectModel>,
        edges: Map<Pair<String, String>, Set<String>>
    ): Pair<List<GraphEdgeReport>, List<GraphViolation>> {
        val reports = mutableListOf<GraphEdgeReport>()
        val violations = mutableListOf<GraphViolation>()
        edges.entries.sortedBy { it.key.first + "->" + it.key.second }.forEach { (edge, configurations) ->
            val source = projectByPath[edge.first]
            val target = projectByPath[edge.second]
            val decision = decideEdge(config, source, target)
            reports += GraphEdgeReport().apply {
                from = edge.first
                to = edge.second
                this.configurations = configurations.sorted()
                allowed = decision.allowed
            }
            if (!decision.allowed && source != null && target != null && decision.code != null) {
                violations += graphViolation(
                    decision.code,
                    source.path,
                    target.path,
                    configurations.sorted(),
                    decision.message.orEmpty()
                )
            }
        }
        return reports to violations
    }

    private fun decideEdge(
        config: ArchitectureConfig,
        source: ProjectModel?,
        target: ProjectModel?
    ): EdgeDecision {
        if (source == null || target == null ||
            source.role == RoleMatch.UNKNOWN.id || target.role == RoleMatch.UNKNOWN.id
        ) {
            return EdgeDecision(true)
        }
        val ruleAllowed = config.edgeRules.any { rule -> ruleAllows(rule, source, target) }
        if (!ruleAllowed) {
            return EdgeDecision(
                false,
                "ARCH_PROJECT_EDGE_FORBIDDEN",
                "role ${source.role} -> ${target.role} is not allowed by " +
                    "config/architecture edgeRules"
            )
        }
        val crossDomainApi = isCrossDomainFeatureApi(source, target)
        if (crossDomainApi && !crossDomainApiRegistered(config, source, target)) {
            return EdgeDecision(
                false,
                "ARCH_CROSS_DOMAIN_API_NOT_REGISTERED",
                "cross-domain feature API edge is not registered in " +
                    "config/architecture crossDomainApiAllowlist"
            )
        }
        return EdgeDecision(true)
    }

    private fun ruleAllows(
        rule: EdgeRuleConfig,
        source: ProjectModel,
        target: ProjectModel
    ): Boolean {
        if (!matchesAny(rule.from, source.role) || !matchesAny(rule.to, target.role)) {
            return false
        }
        if (rule.sameDomainFor.any { matches(it, target.role) } && source.domain != target.domain) {
            return false
        }
        if (rule.sameCapabilityFor.any { matches(it, target.role) } &&
            source.capability != target.capability
        ) {
            return false
        }
        return true
    }

    private fun isCrossDomainFeatureApi(source: ProjectModel, target: ProjectModel): Boolean =
        source.role.startsWith("feature-") &&
            target.role == "feature-api" &&
            source.domain != target.domain

    private fun crossDomainApiRegistered(
        config: ArchitectureConfig,
        source: ProjectModel,
        target: ProjectModel
    ): Boolean = config.crossDomainApiAllowlist.any { rule ->
        matches(rule.from, source.path) && matches(rule.to, target.path)
    }

    private fun cycleViolations(cycles: List<List<String>>): List<GraphViolation> =
        cycles.map { cycle ->
            graphViolation(
                "ARCH_DEPENDENCY_CYCLE",
                cycle.first(),
                cycle.joinToString(" -> "),
                message = "project dependency cycle detected"
            )
        }

    private fun writeReport(gson: Gson, report: GraphReport) {
        val file = reportFile.get().asFile
        file.parentFile?.mkdirs()
        file.writeText(gson.toJson(report), Charsets.UTF_8)
    }

    private fun logReport(
        report: GraphReport,
        externalCount: Int,
        cycleCount: Int,
        edgeCount: Int
    ) {
        logger.lifecycle(
            "[dstu.architecture] task=verifyArchitectureGraph projects=${report.projects} " +
                "edges=$edgeCount external=$externalCount cycles=$cycleCount " +
                "violations=${report.violations.size} duration_ms=${report.durationMillis} " +
                "report=${reportFile.get().asFile.absolutePath}"
        )
        report.violations.forEach { violation ->
            logger.error(
                "[dstu.architecture] ARCH_VIOLATION code=${violation.code} " +
                    "project=${violation.project} target=${violation.target ?: "-"} " +
                    "configurations=${violation.configurations.joinToString(",")} " +
                    "message=${violation.message}"
            )
        }
    }

    private fun failGraph(violations: List<GraphViolation>) {
        val codes = violations.map { it.code }.distinct().sorted().joinToString(",")
        throw GradleException(
            "DSTU_ARCH_GRAPH_VIOLATIONS count=${violations.size} codes=[$codes] " +
                "report=${reportFile.get().asFile.absolutePath}"
        )
    }
}
