package com.deepstudent.buildlogic.architecture

import com.google.gson.JsonParser
import com.google.gson.JsonObject
import org.gradle.api.GradleException
import org.gradle.api.file.ConfigurableFileCollection
import org.gradle.api.file.RegularFileProperty
import org.gradle.api.provider.Property
import org.gradle.api.tasks.Input
import org.gradle.api.tasks.InputFile
import org.gradle.api.tasks.InputFiles
import org.gradle.api.tasks.OutputFile
import org.gradle.api.tasks.PathSensitive
import org.gradle.api.tasks.PathSensitivity
import org.gradle.api.tasks.JavaExec
import org.gradle.process.CommandLineArgumentProvider
import org.gradle.work.DisableCachingByDefault

/**
 * Runs the isolated PSI analyzer and converts its JSON report into a Gradle
 * failure that names the exact violation codes.
 */
@DisableCachingByDefault(because = "Verification reports contain local paths and execution timestamps")
abstract class DstuAnalyzerTask : JavaExec() {
    @get:Input
    abstract val modelJson: Property<String>

    @get:InputFile
    @get:PathSensitive(PathSensitivity.RELATIVE)
    abstract val manifestFile: RegularFileProperty

    @get:OutputFile
    abstract val reportFile: RegularFileProperty

    @get:Input
    abstract val mode: Property<String>

    @get:Input
    abstract val analyzerVersion: Property<String>

    @get:InputFiles
    @get:PathSensitive(PathSensitivity.RELATIVE)
    abstract val sourceFiles: ConfigurableFileCollection

    init {
        argumentProviders.add(CommandLineArgumentProvider {
            listOf(
                "--mode", mode.get(),
                "--manifest", manifestFile.get().asFile.absolutePath,
                "--report", reportFile.get().asFile.absolutePath
            )
        })
    }

    override fun exec() {
        val report = reportFile.get().asFile
        if (report.exists()) {
            report.delete()
        }
        report.parentFile?.mkdirs()
        isIgnoreExitValue = true
        super.exec()

        val exit = executionResult.get().exitValue
        val violations = readViolations(report)
        logger.lifecycle(
            "[dstu.architecture] task=${name} mode=${mode.get()} analyzer=${analyzerVersion.get()} " +
                "exit=${exit} " +
                "violations=${violations.size} report=${report.absolutePath}"
        )
        violations.forEach { violation ->
            logger.error(
                "[dstu.architecture] ARCH_VIOLATION code=${violation.first} " +
                    "message=${violation.second}"
            )
        }
        val fatal = readFatal(report)
        if (fatal != null) {
            throw GradleException("DSTU_ARCH_ANALYZER_FAILED mode=${mode.get()} fatal=$fatal")
        }
        if (exit != 0 || violations.isNotEmpty()) {
            val codes = violations.map { it.first }.distinct().sorted().joinToString(",")
            throw GradleException(
                "DSTU_ARCH_ANALYZER_VIOLATIONS mode=${mode.get()} count=${violations.size} " +
                    "codes=[$codes] report=${report.absolutePath}"
            )
        }
    }

    private fun readViolations(report: java.io.File): List<Pair<String, String>> {
        if (!report.isFile) {
            return emptyList()
        }
        return runCatching {
            val root = JsonParser.parseString(report.readText(Charsets.UTF_8)).asJsonObject
            val array = root.getAsJsonArray("violations") ?: return emptyList()
            array.map { element -> violationSummary(element.asJsonObject) }
        }.getOrElse { emptyList() }
    }

    private fun violationSummary(violation: JsonObject): Pair<String, String> {
        val code = violation.string("code", "UNKNOWN")
        val summary = "project=${violation.string("project", "-")} " +
            "file=${violation.string("file", "-")} line=${violation.int("line", 0)} " +
            "symbol=${violation.string("symbol", "-")} " +
            "metric=${violation.string("metric", "-")} " +
            "actual=${violation.int("actual", 0)} threshold=${violation.int("threshold", 0)}"
        return code to summary
    }

    private fun JsonObject.string(name: String, fallback: String): String =
        get(name)?.takeUnless { it.isJsonNull }?.asString ?: fallback

    private fun JsonObject.int(name: String, fallback: Int): Int =
        get(name)?.takeUnless { it.isJsonNull }?.asInt ?: fallback

    private fun readFatal(report: java.io.File): String? {
        if (!report.isFile) {
            return "report missing: ${report.absolutePath}"
        }
        return runCatching {
            val root = JsonParser.parseString(report.readText(Charsets.UTF_8)).asJsonObject
            val fatal = root.get("fatal")
            if (fatal == null || fatal.isJsonNull) null else fatal.asString
        }.getOrDefault("report unreadable: ${report.absolutePath}")
    }
}
