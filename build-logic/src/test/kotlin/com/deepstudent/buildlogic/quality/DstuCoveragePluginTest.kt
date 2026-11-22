package com.deepstudent.buildlogic.quality

import org.gradle.testkit.runner.GradleRunner
import org.gradle.testkit.runner.TaskOutcome
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.nio.file.Files
import java.nio.file.Path
import java.util.stream.Stream

class DstuCoveragePluginTest {
    @TempDir
    lateinit var tempDir: Path

    @Test
    fun `coverage report is generated from real test execution`() {
        val result = runner(prepareFixture(), "jacocoTestReport").build()
        assertEquals(TaskOutcome.SUCCESS, result.task(":jacocoTestReport")?.outcome)
        assertTrue(Files.exists(fixtureProject().resolve("build/reports/jacoco/test/jacocoTestReport.xml")))
        assertTrue(Files.exists(fixtureProject().resolve("build/reports/jacoco/test/html/index.html")))
    }

    @Test
    fun `coverage gate fails below the configured minimum`() {
        val result = runner(prepareFixture(), "jacocoTestCoverageVerification").buildAndFail()
        assertEquals(
            TaskOutcome.FAILED,
            result.task(":jacocoTestCoverageVerification")?.outcome,
        )
        assertTrue(result.output.contains("Rule violated"))
    }

    @Test
    fun `empty test suite cannot silently skip the coverage gate`() {
        val project = prepareFixture()
        project.resolve("src/test").toFile().deleteRecursively()
        val result = runner(project, "jacocoTestCoverageVerification").buildAndFail()
        assertTrue(result.output.contains("DSTU_COVERAGE_NO_TEST_DATA"))
    }

    @Test
    fun `zero threshold cannot disable the coverage gate`() {
        val project = prepareFixture()
        val buildFile = project.resolve("build.gradle.kts")
        Files.writeString(buildFile, Files.readString(buildFile).replace("1.0", "0.0"))
        val result = runner(project, "jacocoTestCoverageVerification").buildAndFail()
        assertTrue(result.output.contains("DSTU_COVERAGE_MINIMUM_INVALID"))
    }

    private fun fixtureProject(): Path = tempDir.resolve("coverage-failure")

    private fun prepareFixture(): Path {
        val source = Path.of("src", "test", "fixtures", "coverage-failure")
        val target = fixtureProject()
        Files.createDirectories(target)
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
