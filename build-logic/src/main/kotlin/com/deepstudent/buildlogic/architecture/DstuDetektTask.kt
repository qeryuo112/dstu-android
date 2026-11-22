package com.deepstudent.buildlogic.architecture

import com.google.gson.Gson
import org.gradle.api.GradleException
import org.gradle.api.file.ConfigurableFileCollection
import org.gradle.api.file.RegularFileProperty
import org.gradle.api.provider.Property
import org.gradle.api.tasks.Input
import org.gradle.api.tasks.InputFile
import org.gradle.api.tasks.InputFiles
import org.gradle.api.tasks.JavaExec
import org.gradle.api.tasks.OutputFile
import org.gradle.api.tasks.PathSensitive
import org.gradle.api.tasks.PathSensitivity
import org.gradle.process.CommandLineArgumentProvider
import org.gradle.work.DisableCachingByDefault
import java.io.File

/**
 * Runs detekt 1.23.8 from a dedicated CLI configuration. The fixed version is
 * asserted both by the configuration build and by the resolved CLI artifact.
 */
@DisableCachingByDefault(because = "Local verification report; source inputs still invalidate up-to-date state")
abstract class DstuDetektTask : JavaExec() {
    @get:Input
    abstract val modelJson: Property<String>

    @get:InputFile
    @get:PathSensitive(PathSensitivity.RELATIVE)
    abstract val detektConfigFile: RegularFileProperty

    @get:Input
    abstract val detektVersion: Property<String>

    @get:OutputFile
    abstract val xmlReport: RegularFileProperty

    @get:OutputFile
    abstract val textReport: RegularFileProperty

    @get:InputFiles
    @get:PathSensitive(PathSensitivity.RELATIVE)
    abstract val sourceFiles: ConfigurableFileCollection

    init {
        argumentProviders.add(CommandLineArgumentProvider { detektArguments() })
    }

    override fun exec() {
        val cliJar = classpath.files.firstOrNull { it.name.startsWith("detekt-cli-") }
            ?: throw GradleException(
                "DSTU_COMPLEXITY_DETEKT_CLI_MISSING version=${detektVersion.get()}"
            )
        if (!cliJar.name.contains(detektVersion.get())) {
            throw GradleException(
                "DSTU_COMPLEXITY_DETEKT_VERSION_MISMATCH expected=${detektVersion.get()} " +
                    "actual=${cliJar.name}"
            )
        }
        val inputDirectories = inputDirectories()
        if (inputDirectories.isEmpty()) {
            logger.lifecycle(
                "[dstu.architecture] task=detektDstu skipped=no-kotlin-sources " +
                    "version=${detektVersion.get()}"
            )
            return
        }
        val xml = xmlReport.get().asFile
        val text = textReport.get().asFile
        if (xml.exists()) {
            xml.delete()
        }
        if (text.exists()) {
            text.delete()
        }
        xml.parentFile?.mkdirs()
        text.parentFile?.mkdirs()
        isIgnoreExitValue = true
        super.exec()
        val exit = executionResult.get().exitValue
        logger.lifecycle(
            "[dstu.architecture] task=detektDstu version=${detektVersion.get()} exit=$exit " +
                "inputs=${inputDirectories.size} xml=${xml.absolutePath} text=${text.absolutePath}"
        )
        if (exit != 0) {
            throw GradleException(
                "DSTU_COMPLEXITY_DETEKT_FAILED version=${detektVersion.get()} exit=$exit " +
                    "xml=${xml.absolutePath} text=${text.absolutePath}"
            )
        }
    }

    private fun detektArguments(): List<String> {
        val inputDirectories = inputDirectories()
        if (inputDirectories.isEmpty()) {
            return emptyList()
        }
        val model = Gson().fromJson(modelJson.get(), ScanModel::class.java)
        val arguments = mutableListOf(
            "--input", inputDirectories.joinToString(","),
            "--config", detektConfigFile.get().asFile.absolutePath,
            "--report", "xml:${xmlReport.get().asFile.absolutePath}",
            "--report", "txt:${textReport.get().asFile.absolutePath}",
            "--jvm-target", "17"
        )
        val exceptionPaths = model.complexityExceptions.map {
            File(model.root, it.path).absolutePath
        }
        if (exceptionPaths.isNotEmpty()) {
            arguments += listOf("--excludes", exceptionPaths.joinToString(","))
        }
        return arguments
    }

    private fun inputDirectories(): List<String> {
        val model = Gson().fromJson(modelJson.get(), ScanModel::class.java)
        val sourceSets = model.complexitySourceSets.toSet()
        return model.modules
            .flatMap { it.sources }
            .filter { it.sourceSet in sourceSets && File(it.dir).isDirectory }
            .map { it.dir }
            .distinct()
            .sorted()
    }
}
