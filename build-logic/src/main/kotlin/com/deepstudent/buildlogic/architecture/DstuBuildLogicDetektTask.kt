package com.deepstudent.buildlogic.architecture

import org.gradle.api.GradleException
import org.gradle.api.file.DirectoryProperty
import org.gradle.api.file.RegularFileProperty
import org.gradle.api.provider.Property
import org.gradle.api.tasks.Input
import org.gradle.api.tasks.InputDirectory
import org.gradle.api.tasks.InputFile
import org.gradle.api.tasks.JavaExec
import org.gradle.api.tasks.Optional
import org.gradle.api.tasks.OutputFile
import org.gradle.api.tasks.PathSensitive
import org.gradle.api.tasks.PathSensitivity
import org.gradle.process.CommandLineArgumentProvider
import org.gradle.work.DisableCachingByDefault

/**
 * Applies the same fixed detekt version and complexity configuration to
 * build-logic's own Kotlin sources. Java analyzer sources remain organized in
 * small classes and are reviewed separately because detekt is Kotlin-only.
 */
@DisableCachingByDefault(because = "Local verification report; source inputs still invalidate up-to-date state")
abstract class DstuBuildLogicDetektTask : JavaExec() {
    @get:InputDirectory
    @get:Optional
    @get:PathSensitive(PathSensitivity.RELATIVE)
    abstract val sourceDirectory: DirectoryProperty

    @get:InputFile
    @get:PathSensitive(PathSensitivity.RELATIVE)
    abstract val detektConfigFile: RegularFileProperty

    @get:Input
    abstract val detektVersion: Property<String>

    @get:OutputFile
    abstract val xmlReport: RegularFileProperty

    @get:OutputFile
    abstract val textReport: RegularFileProperty

    init {
        argumentProviders.add(CommandLineArgumentProvider {
            listOf(
                "--input", sourceDirectory.get().asFile.absolutePath,
                "--config", detektConfigFile.get().asFile.absolutePath,
                "--report", "xml:${xmlReport.get().asFile.absolutePath}",
                "--report", "txt:${textReport.get().asFile.absolutePath}",
                "--jvm-target", "17"
            )
        })
    }

    override fun exec() {
        val cliJar = classpath.files.firstOrNull { it.name.startsWith("detekt-cli-") }
            ?: throw GradleException("DSTU_BUILD_LOGIC_DETEKT_CLI_MISSING")
        if (!cliJar.name.contains(detektVersion.get())) {
            throw GradleException(
                "DSTU_BUILD_LOGIC_DETEKT_VERSION_MISMATCH expected=${detektVersion.get()} " +
                    "actual=${cliJar.name}"
            )
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
            "[dstu.architecture] task=detektBuildLogic version=${detektVersion.get()} exit=$exit " +
                "source=${sourceDirectory.get().asFile.absolutePath} xml=${xml.absolutePath}"
        )
        if (exit != 0) {
            throw GradleException(
                "DSTU_BUILD_LOGIC_DETEKT_FAILED version=${detektVersion.get()} exit=$exit " +
                    "xml=${xml.absolutePath} text=${text.absolutePath}"
            )
        }
    }
}
