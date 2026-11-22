package com.deepstudent.platform.database

import com.deepstudent.platform.database.internal.MigrationCommitKind
import java.io.File
import java.net.URLClassLoader
import java.util.concurrent.TimeUnit
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

class MigrationCommitDeathTest {
    @get:Rule val temporary = TemporaryFolder()

    @Test fun realJvmProcessRestartsAfterEveryUsageMigrationAndGovernanceCommit() {
        val repository = File(checkNotNull(System.getProperty("user.dir"))).let {
            if (it.resolve("config/schema/final-lock.json").isFile) it else it.resolve("../..")
        }.canonicalFile
        val root = temporary.newFolder()
        val expected = MigrationCheckpointEvidence(root.resolve("database.db"), SharedDatabase.LLM_USAGE,
            MigrationSource { repository.resolve(it).readBytes() }, null)
        val driver = ChildDriver(repository, root, checkNotNull(javaClass.classLoader))
        expected.history.indices.forEach { driver.run(HALTED_AT_COMMIT, "advance", it.toString()) }
        driver.run(0, "verify", MigrationCommitKind.MIGRATION.name)
        driver.run(HALTED_AT_COMMIT, "governance", "-")
        driver.run(0, "verify", MigrationCommitKind.GOVERNANCE.name)
    }
}

private class ChildDriver(private val repository: File, private val root: File, loader: ClassLoader) {
    private val java = File(System.getProperty("java.home"), "bin/java").absolutePath
    // Gradle isolates test dependencies in a URLClassLoader instead of putting them on java.class.path.
    private val classpath = generateSequence(loader) { it.parent }.filterIsInstance<URLClassLoader>()
        .flatMap { it.urLs.asSequence() }.filter { it.protocol == "file" }.map { File(it.toURI()).path }
        .distinct().joinToString(File.pathSeparator) + File.pathSeparator + System.getProperty("java.class.path")

    fun run(expectedStatus: Int, operation: String, step: String) {
        val output = root.resolve("child-output")
        val process = ProcessBuilder(java, "-cp", classpath, MigrationCommitDeathJvmProcess::class.java.name,
            repository.path, root.path, operation, step).redirectErrorStream(true).redirectOutput(output).start()
        try {
            assertTrue("child timed out: $operation $step", process.waitFor(45, TimeUnit.SECONDS))
            assertEquals("child $operation $step: ${output.readText().takeLast(4000)}", expectedStatus, process.exitValue())
        } finally {
            if (process.isAlive) process.destroyForcibly().waitFor(10, TimeUnit.SECONDS)
        }
    }
}

private const val HALTED_AT_COMMIT = 94

/** Runs outside the Gradle worker; halt deliberately bypasses connection and JVM shutdown hooks. */
object MigrationCommitDeathJvmProcess {
    @JvmStatic fun main(args: Array<String>) {
        val (repository, root, operation, step) = args
        val contract = MigrationCommitDeathContract(File(root), SharedDatabase.LLM_USAGE,
            MigrationSource { File(repository, it).readBytes() }, null, currentPid())
        when (operation) {
            "advance" -> contract.advance(step.toInt(), ::hardHalt)
            "governance" -> contract.migrateLegacyHead(::hardHalt)
            "verify" -> contract.verifyHead(MigrationCommitKind.valueOf(step))
            else -> error("unknown checkpoint operation")
        }
    }

    // This source compiles against Android stubs but is only executed on the host JDK.
    private fun currentPid(): Long = Class.forName("java.lang.ProcessHandle").let { handle ->
        handle.getMethod("pid").invoke(handle.getMethod("current").invoke(null)) as Long
    }

    private fun hardHalt(): Nothing {
        Runtime.getRuntime().halt(HALTED_AT_COMMIT)
        error("process survived JVM halt")
    }
}
