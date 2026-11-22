package com.deepstudent.platform.security

import java.io.File
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class SecurityBoundaryTest {
    @Test
    fun securityMainHasNoLoggingOrHigherLayerImports() {
        val main = File(repositoryRoot(), "platform/security/src/main/kotlin")
        val sources = main.walkTopDown().filter { it.isFile && it.extension == "kt" }.toList()
        assertTrue(sources.isNotEmpty())
        for (source in sources) {
            val text = source.readText()
            assertFalse("$source must not log secrets", text.contains("android.util.Log"))
            assertFalse("$source must not print", text.contains("println("))
            assertFalse("$source must not use stdout", text.contains("System.out"))
            assertFalse("$source must not print stack traces", text.contains("printStackTrace"))
            assertFalse("$source must not import app", text.contains("import com.deepstudent.app"))
            assertFalse("$source must not import features", text.contains("import com.deepstudent.features"))
        }
    }

    private fun repositoryRoot(): File {
        var current = File(System.getProperty("user.dir")).absoluteFile
        while (true) {
            if (File(current, "config/baseline/crypto-golden.json").isFile) return current
            current = current.parentFile ?: error("repository root not found")
        }
    }
}
