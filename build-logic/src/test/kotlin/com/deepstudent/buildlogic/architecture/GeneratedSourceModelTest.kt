package com.deepstudent.buildlogic.architecture

import org.gradle.testfixtures.ProjectBuilder
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.nio.file.Files
import java.nio.file.Path

class GeneratedSourceModelTest {
    @TempDir
    lateinit var temporary: Path

    @Test
    fun `declared generated roots survive a clean configuration`() {
        val root = ProjectBuilder.builder().withProjectDir(temporary.toFile()).build()
        val moduleDirectory = Files.createDirectories(temporary.resolve("design"))
        Files.writeString(moduleDirectory.resolve("build.gradle.kts"), "")
        val module = ProjectBuilder.builder().withName("design")
            .withProjectDir(moduleDirectory.toFile()).withParent(root).build()
        val generated = moduleDirectory.resolve("build/generated/tokens")
        assertFalse(Files.exists(generated))
        val config = ArchitectureConfig().apply {
            roleRules = listOf(RoleRuleConfig().apply {
                id = "core-design"
                pattern = "^:design$"
                layer = "core"
            })
            additionalSourceRoots = listOf(AdditionalSourceRootConfig().apply {
                project = module.path
                path = "build/generated/tokens"
                sourceSet = "main"
            })
        }
        val loaded = LoadedArchitectureConfig(
            config, ComplexityConfig(), temporary.resolve("architecture.json").toFile(),
            temporary.resolve("complexity.json").toFile(), temporary.resolve("detekt.yml").toFile(),
        )
        val model = ArchitectureModelBuilder().build(root, loaded)
        assertTrue(model.modules.single().sources.any { it.dir == generated.toString() && it.sourceSet == "main" })
    }
}
