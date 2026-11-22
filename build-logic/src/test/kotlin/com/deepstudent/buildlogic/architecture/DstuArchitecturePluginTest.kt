package com.deepstudent.buildlogic.architecture

import org.gradle.testkit.runner.BuildResult
import org.gradle.testkit.runner.GradleRunner
import org.gradle.testkit.runner.TaskOutcome
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNotEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.nio.file.Files
import java.nio.file.Path
import java.util.stream.Stream
import com.google.gson.JsonParser

class DstuArchitecturePluginTest {
    @TempDir
    lateinit var tempDir: Path

    @Test
    fun `ui direct data dependency fails architecture`() {
        val result = runFixtureAndFail("ui-imports-data", "verifyArchitecture")
        assertTrue(result.output.contains("ARCH_PROJECT_EDGE_FORBIDDEN") ||
            result.output.contains("ARCH_UI_IMPLEMENTATION_IMPORT"))
    }

    @Test
    fun `domain android import fails architecture`() {
        val result = runFixtureAndFail("domain-imports-android", "verifyArchitecture")
        assertTrue(result.output.contains("ARCH_DOMAIN_ANDROID_IMPORT") ||
            result.output.contains("ARCH_FORBIDDEN_EXTERNAL_DEPENDENCY"))
    }

    @Test
    fun `cross domain internal import fails architecture`() {
        val result = runFixtureAndFail("cross-domain-internal", "verifyArchitecture")
        assertTrue(result.output.contains("ARCH_CROSS_DOMAIN_INTERNAL_IMPORT"))
    }

    @Test
    fun `project cycle fails architecture`() {
        val result = runFixtureAndFail("project-cycle", "verifyArchitecture")
        assertTrue(result.output.contains("ARCH_DEPENDENCY_CYCLE"))
    }

    @Test
    fun `global graph lookup fails architecture`() {
        val result = runFixtureAndFail("global-graph-lookup", "verifyArchitecture")
        assertTrue(result.output.contains("ARCH_GLOBAL_GRAPH_LOOKUP"))
    }

    @Test
    fun `long function fails psi complexity`() {
        val result = runFixtureAndFail("long-function", "verifyComplexityPsi")
        assertTrue(result.output.contains("COMPLEXITY_FUNCTION_LINES"))
    }

    @Test
    fun `detekt 1_23_8 fails long function`() {
        val result = runFixtureAndFail("long-function", "detektDstu")
        assertTrue(result.output.contains("LongMethod") ||
            result.output.contains("DSTU_COMPLEXITY_DETEKT_FAILED"))
    }

    @Test
    fun `clean fixture passes architecture and complexity`() {
        val architecture = runFixture("clean", "verifyArchitecture")
        assertEquals(TaskOutcome.SUCCESS, architecture.task(":verifyArchitecture")?.outcome)
        val complexity = runFixture("clean", "verifyComplexity")
        assertEquals(TaskOutcome.SUCCESS, complexity.task(":verifyComplexity")?.outcome)
    }

    @Test
    fun `source edit invalidates architecture and fails`() {
        val project = prepareFixture("clean")
        runner(project, "verifyArchitecture").build()
        val source = project.resolve(
            "features/notes/ui/src/main/kotlin/com/deepstudent/features/notes/ui/NoteScreen.kt"
        )
        Files.writeString(
            source,
            """
            package com.deepstudent.features.notes.ui

            import com.deepstudent.features.notes.backend.application.NoteUseCase

            class NoteScreen(private val useCase: NoteUseCase)
            """.trimIndent() + "\n"
        )
        val result = runner(project, "verifyArchitecture").buildAndFail()
        assertTrue(result.output.contains("ARCH_UI_IMPLEMENTATION_IMPORT"))
        assertNotEquals(
            TaskOutcome.UP_TO_DATE,
            result.task(":verifyArchitectureSources")?.outcome
        )
    }

    @Test
    fun `source edit invalidates complexity and fails`() {
        val project = prepareFixture("clean")
        runner(project, "verifyComplexityPsi").build()
        val source = project.resolve(
            "features/notes/backend/src/main/kotlin/com/deepstudent/features/notes/backend/domain/Note.kt"
        )
        val increments = (1..65).joinToString("\n") { "        value += $it" }
        Files.writeString(
            source,
            """
            package com.deepstudent.features.notes.backend.domain

            class Note {
                fun long(input: Int): Int {
                    var value = input
            $increments
                    return value
                }
            }
            """.trimIndent() + "\n"
        )
        val result = runner(project, "verifyComplexityPsi").buildAndFail()
        assertTrue(result.output.contains("COMPLEXITY_FUNCTION_LINES"))
        assertNotEquals(
            TaskOutcome.UP_TO_DATE,
            result.task(":verifyComplexityPsi")?.outcome
        )
    }

    @Test
    fun `kotlin library convention compiles`() {
        val result = runner(prepareFixture("kotlin-library"), "compileKotlin").build()
        assertEquals(TaskOutcome.SUCCESS, result.task(":lib:compileKotlin")?.outcome)
    }

    @Test
    fun `android library convention assembles`() {
        val result = runner(prepareFixture("android-library"), "assembleDebug").build()
        assertEquals(TaskOutcome.SUCCESS, result.task(":lib:assembleDebug")?.outcome)
    }

    @Test
    fun `pure URI value parsing is allowed but opening a network connection fails`() {
        val project = prepareFixture("clean")
        val source = project.resolve("core/common/src/main/kotlin/com/deepstudent/core/common/Endpoint.kt")
        Files.writeString(
            source,
            "package com.deepstudent.core.common\nimport java.net.URI\nclass Endpoint(val uri: URI)\n"
        )
        runner(project, "verifyArchitecture").build()
        Files.writeString(
            source,
            "package com.deepstudent.core.common\nimport java.net.Socket\nclass Endpoint(val socket: Socket)\n"
        )
        val result = runner(project, "verifyArchitecture").buildAndFail()
        assertTrue(result.output.contains("ARCH_DOMAIN_ANDROID_IMPORT"))
    }

    private fun runFixture(name: String, task: String): BuildResult =
        runner(prepareFixture(name), task).build()

    private fun runner(project: Path, task: String): GradleRunner =
        GradleRunner.create()
            .withProjectDir(project.toFile())
            .withPluginClasspath()
            .withArguments(task, "--stacktrace", "--console=plain")
            .forwardOutput()

    private fun runFixtureAndFail(name: String, task: String): BuildResult =
        runner(prepareFixture(name), task).buildAndFail()

    private fun prepareFixture(name: String): Path {
        val source = Path.of("src", "test", "fixtures", name)
        val target = Files.createTempDirectory(tempDir, "$name-")
        copyTree(source, target)
        copyTree(Path.of("..", "config", "architecture"), target.resolve("config/architecture"))
        copyExceptionSources(target)
        return target
    }

    private fun copyExceptionSources(target: Path) {
        for (name in listOf("architecture.json", "complexity.json")) {
            val config = JsonParser.parseString(Files.readString(target.resolve("config/architecture/$name"))).asJsonObject
            for (entry in config.getAsJsonArray("exceptions")) {
                val relative = entry.asJsonObject.get("path").asString
                val destination = target.resolve(relative)
                Files.createDirectories(destination.parent)
                Files.copy(Path.of("..").resolve(relative), destination)
            }
        }
    }

    private fun copyTree(source: Path, target: Path) {
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
    }
}
