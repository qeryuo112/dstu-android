package com.deepstudent.buildlogic.convention

import org.gradle.testkit.runner.GradleRunner
import org.gradle.testkit.runner.TaskOutcome
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.nio.file.Files
import java.nio.file.Path
import java.util.stream.Stream

class AndroidConventionTest {
    @TempDir
    lateinit var tempDir: Path

    @Test
    fun `android convention exposes typed AGP and KGP values`() {
        val result = runner(prepareFixture(), "printDstuConvention").build()
        assertEquals(TaskOutcome.SUCCESS, result.task(":lib:printDstuConvention")?.outcome)
        assertTrue(result.output.contains("DstuCompileSdk=36"))
        assertTrue(result.output.contains("DstuMinSdk=26"))
        assertTrue(result.output.contains("DstuTargetSdk=36"))
        assertTrue(result.output.contains("DstuAbi=arm64-v8a"))
        assertTrue(result.output.contains("DstuLegacyPackaging=true"))
        assertTrue(result.output.contains("DstuKotlinJvmTarget=17"))
    }

    private fun prepareFixture(): Path {
        val source = Path.of("src", "test", "fixtures", "android-library")
        val target = Files.createTempDirectory(tempDir, "android-library-")
        Files.walk(source).use { paths: Stream<Path> ->
            paths.forEach { path ->
                val destination = target.resolve(source.relativize(path).toString())
                if (Files.isDirectory(path)) {
                    Files.createDirectories(destination)
                } else {
                    Files.createDirectories(destination.parent)
                    Files.copy(path, destination)
                }
            }
        }
        return target
    }

    private fun runner(project: Path, task: String): GradleRunner = GradleRunner.create()
        .withProjectDir(project.toFile())
        .withPluginClasspath()
        .withArguments(task, "--stacktrace", "--console=plain")
        .forwardOutput()
}
