package com.deepstudent.buildlogic.architecture

import org.gradle.api.DefaultTask
import org.gradle.api.file.RegularFileProperty
import org.gradle.api.provider.Property
import org.gradle.api.tasks.Input
import org.gradle.api.tasks.OutputFile
import org.gradle.api.tasks.TaskAction

/**
 * Materializes the configuration-time scan model into the manifest consumed by
 * the isolated Kotlin-PSI analyzer JVM.
 */
@org.gradle.work.DisableCachingByDefault(because = "Manifest contains absolute project source paths")
abstract class PrepareArchitectureScanTask : DefaultTask() {
    @get:Input
    abstract val modelJson: Property<String>

    @get:OutputFile
    abstract val manifestFile: RegularFileProperty

    @TaskAction
    fun prepare() {
        val file = manifestFile.get().asFile
        file.parentFile?.mkdirs()
        file.writeText(modelJson.get(), Charsets.UTF_8)
        logger.lifecycle(
            "[dstu.architecture] task=prepareDstuArchitectureScan manifest=${file.absolutePath} " +
                "bytes=${file.length()}"
        )
    }
}
