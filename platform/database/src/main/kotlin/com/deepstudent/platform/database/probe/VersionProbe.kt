package com.deepstudent.platform.database.probe

import com.deepstudent.platform.database.ProbeId
import com.deepstudent.platform.database.ProbeResult
import com.deepstudent.platform.database.internal.ProbeContext
import com.deepstudent.platform.database.internal.ProbeFamily
import com.deepstudent.platform.database.internal.expectTrue

internal class VersionProbe : ProbeFamily {
    override fun run(context: ProbeContext): List<ProbeResult> = listOf(
        context.capture(ProbeId.VERSION) {
            context.withSession("version.db") { session ->
                val version = session.queryText("select sqlite_version()")
                val sourceId = session.queryText("select sqlite_source_id()")
                expectTrue(version.isNotBlank(), "sqlite_version() must not be blank")
                expectTrue(sourceId.isNotBlank(), "sqlite_source_id() must not be blank")
                mapOf(
                    "version" to version,
                    "sourceId" to sourceId,
                    "isBundledArtifact" to "true",
                )
            }
        },
        context.capture(ProbeId.COMPILE_OPTIONS) {
            context.withSession("compile-options.db") { session ->
                val options = session.queryTexts("pragma compile_options").toSortedSet()
                val threadingMode = options
                    .firstOrNull { it.startsWith("THREADSAFE=") }
                    ?.substringAfter('=')
                    ?.toIntOrNull()
                val json1CompileOption =
                    session.queryLong("select sqlite_compileoption_used('ENABLE_JSON1')") == 1L
                expectTrue(options.isNotEmpty(), "pragma compile_options returned no rows")
                expectTrue(
                    options.contains("ENABLE_FTS5"),
                    "bundled SQLite must be compiled with ENABLE_FTS5",
                )
                mapOf(
                    "compileOptions" to options.joinToString("\n"),
                    "compileOptionsCount" to options.size.toString(),
                    "threadingMode" to threadingMode?.toString().orEmpty(),
                    "json1CompileOption" to json1CompileOption.toString(),
                )
            }
        },
    )
}
