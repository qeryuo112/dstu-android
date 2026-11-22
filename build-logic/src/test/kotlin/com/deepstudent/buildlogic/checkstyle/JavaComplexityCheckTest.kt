package com.deepstudent.buildlogic.checkstyle

import com.puppycrawl.tools.checkstyle.Checker
import com.puppycrawl.tools.checkstyle.ConfigurationLoader
import com.puppycrawl.tools.checkstyle.PropertiesExpander
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.nio.file.Files
import java.nio.file.Path

class JavaComplexityCheckTest {
    @TempDir
    lateinit var tempDir: Path

    @Test
    fun `giant method fails checkstyle`() {
        val increments = (1..65).joinToString("\n") { "        value += $it;" }
        val source = """
            public class Giant {
                public int run(int input) {
                    int value = input;
            $increments
                    return value;
                }
            }
        """.trimIndent()
        assertTrue(check("Giant.java", source) > 0)
    }

    @Test
    fun `high cyclomatic complexity fails checkstyle`() {
        val branches = (1..12).joinToString("\n") {
            "        if (value == $it) { value++; }"
        }
        val source = """
            public class Complex {
                public int run(int value) {
            $branches
                    return value;
                }
            }
        """.trimIndent()
        assertTrue(check("Complex.java", source) > 0)
    }

    @Test
    fun `class effective lines fail checkstyle`() {
        val fields = (1..305).joinToString("\n") { "    private int field$it;" }
        val source = """
            public class Big {
            $fields
            }
        """.trimIndent()
        assertTrue(check("Big.java", source) > 0)
    }

    @Test
    fun `small java source passes checkstyle`() {
        val source = """
            public class Small {
                public int value() {
                    return 1;
                }
            }
        """.trimIndent()
        assertEquals(0, check("Small.java", source))
    }

    private fun check(fileName: String, source: String): Int {
        val file = tempDir.resolve(fileName)
        Files.writeString(file, source + "\n")
        val configuration = ConfigurationLoader.loadConfiguration(
            Path.of("config/checkstyle/java-complexity.xml").toString(),
            PropertiesExpander(System.getProperties())
        )
        val checker = Checker()
        checker.setModuleClassLoader(Thread.currentThread().contextClassLoader)
        checker.configure(configuration)
        return try {
            checker.process(listOf(file.toFile()))
        } finally {
            checker.destroy()
        }
    }
}
