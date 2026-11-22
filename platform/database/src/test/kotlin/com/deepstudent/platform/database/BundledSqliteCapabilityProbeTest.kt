package com.deepstudent.platform.database

import com.deepstudent.platform.database.internal.ProbeContext
import com.deepstudent.platform.database.internal.ProbeWorkspace
import com.deepstudent.platform.database.internal.SqliteConnectionFactory
import com.deepstudent.platform.database.internal.captureDatabaseError
import com.deepstudent.platform.database.internal.unexpectedError
import org.junit.AfterClass
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.BeforeClass
import org.junit.Test
import java.io.File
import java.nio.file.Files

class BundledSqliteCapabilityProbeTest {

    @Test
    fun probeMatrixIsCompleteAndPassing() {
        assertEquals(ProbeId.values().size, report.results.size)
        assertEquals(ProbeId.values().toSet(), report.results.map { it.id }.toSet())
        assertTrue(failureSummary(), report.allPassed)
    }

    @Test
    fun bundledDriverIdentityIsNotAndroidSystemSqlite() {
        assertEquals("androidx.sqlite:sqlite-bundled:2.6.2", report.driver.coordinate)
        assertEquals(
            "androidx.sqlite.driver.bundled.BundledSQLiteDriver",
            report.driver.implementationClass,
        )
        assertFalse(report.driver.hasConnectionPool)
    }

    @Test
    fun versionSourceAndCompileOptionsComeFromBundledLibrary() {
        assertEquals("3.50.1", report.sqlite.version)
        assertEquals(
            "2025-06-06 14:52:32 " +
                "b77dc5e0f596d2140d9ac682b2893ff65d3a4140aa86067a3efebe29dc914c95",
            report.sqlite.sourceId,
        )
        assertEquals(2, report.sqlite.threadingMode)
        assertTrue(report.sqlite.compileOptions.contains("ENABLE_FTS5"))
        assertTrue(report.sqlite.compileOptions.contains("THREADSAFE=2"))
        assertFalse(report.sqlite.json1CompileOption)
    }

    @Test
    fun fts5TrigramHandlesChineseAndEnglish() {
        val details = passed(ProbeId.FTS5_TRIGRAM).details
        assertEquals("1", details["chineseMatch"])
        assertEquals("1", details["chineseFullMatch"])
        assertEquals("2", details["mixedMatch"])
        assertEquals("2", details["englishMatch"])
        assertEquals("3", details["asciiMatch"])
        assertEquals("1", details["twoCharacterCjkMatch"])
        assertTrue(report.capabilities.fts5Trigram)
    }

    @Test
    fun contentlessDeleteIsAvailableAboveSqlite343() {
        val details = passed(ProbeId.FTS5_CONTENTLESS_DELETE).details
        assertEquals("3.50.1", details["sqliteVersion"])
        assertEquals("3.43.0", details["minimumVersion"])
        assertEquals("1", details["beforeDeleteMatch"])
        assertEquals("", details["afterDeleteMatch"])
        assertEquals("1", details["afterReinsertMatch"])
        assertTrue(report.capabilities.fts5ContentlessDelete)
    }

    @Test
    fun json1AndJsonbAreRecorded() {
        val json1 = passed(ProbeId.JSON1).details
        assertEquals("中文", json1["jsonExtract"])
        assertEquals("object", json1["jsonType"])
        assertEquals("3", json1["jsonArrayLength"])
        assertEquals("false", json1["json1CompileOption"])

        val jsonb = passed(ProbeId.JSONB).details
        assertEquals("object", jsonb["jsonbType"])
        assertEquals("2", jsonb["jsonbExtract"])
        assertEquals("4C17611331", jsonb["jsonbHex"])
        assertTrue(report.capabilities.json1)
        assertTrue(report.capabilities.jsonb)
    }

    @Test
    fun transactionAndSavepointCommitOrRollbackExactly() {
        val transaction = passed(ProbeId.TRANSACTION).details
        assertEquals("1", transaction["committedCount"])
        assertEquals("committed", transaction["committedValues"])

        val savepoint = passed(ProbeId.SAVEPOINT).details
        assertEquals("outer", savepoint["rollbackValues"])
        assertEquals("outer,inner", savepoint["releaseValues"])
        assertEquals("1", savepoint["rollbackRows"])
        assertEquals("2", savepoint["releaseRows"])
        assertTrue(report.capabilities.savepoint)
    }

    @Test
    fun foreignKeysAreEnforcedAndReported() {
        val details = passed(ProbeId.FOREIGN_KEYS).details
        assertEquals("0", details["defaultForeignKeys"])
        assertEquals("1", details["enabledForeignKeys"])
        assertEquals("1", details["validChildRows"])
        assertEquals("CONSTRAINT", details["violationKind"])
        assertEquals("787", details["violationCode"])
        assertTrue(report.capabilities.foreignKeys)
    }

    @Test
    fun integerBoundariesRoundTripExactly() {
        val details = passed(ProbeId.INTEGER_BOUNDARIES).details
        assertEquals(Long.MIN_VALUE.toString(), details["min"])
        assertEquals((Long.MIN_VALUE + 1).toString(), details["minPlusOne"])
        assertEquals("-1", details["minusOne"])
        assertEquals("0", details["zero"])
        assertEquals("1", details["one"])
        assertEquals("9007199254740993", details["largeExact"])
        assertEquals((Long.MAX_VALUE - 1).toString(), details["maxMinusOne"])
        assertEquals(Long.MAX_VALUE.toString(), details["max"])
        assertEquals("8", details["count"])
    }

    @Test
    fun blobWithNulAndNullRoundTrip() {
        val blob = passed(ProbeId.BLOB_NUL).details
        assertEquals("6", blob["blobLength"])
        assertEquals("000102007FFF", blob["blobHex"])
        assertEquals("true", blob["embeddedNulPreserved"])

        val nulls = passed(ProbeId.NULL_VALUE).details
        assertEquals("true", nulls["allColumnsNull"])
        assertEquals("null", nulls["typeof"])
        assertEquals("true", nulls["integerNull"])
        assertEquals("true", nulls["blobNull"])
        assertEquals("true", nulls["textNull"])
    }

    @Test
    fun walTwoConnectionSnapshotAndBusyBudget() {
        val snapshot = passed(ProbeId.WAL_SNAPSHOT).details
        assertEquals("wal", snapshot["journalMode"])
        assertEquals("1", snapshot["snapshotBefore"])
        assertEquals("1", snapshot["snapshotAfterWriter"])
        assertEquals("2", snapshot["writerCount"])
        assertEquals("2", snapshot["afterCommit"])

        val busyResult = passed(ProbeId.WAL_BUSY_TIMEOUT)
        val busy = busyResult.details
        assertEquals("wal", busy["journalMode"])
        assertEquals("250", busy["busyTimeoutMillis"])
        assertTrue(busy.getValue("busyMillis").toLong() >= 100L)
        assertTrue(busy.getValue("busyMillis").toLong() <= 5_000L)
        assertTrue(busy["busyCode"] in setOf("5", "6"))
        assertEquals("BUSY", busy["busyKind"])
        assertTrue(
            "probe duration ${busyResult.durationMillis}ms must cover " +
                "busy wait ${busy.getValue("busyMillis")}ms",
            busyResult.durationMillis >= 100L,
        )
        assertTrue(
            "probe duration ${busyResult.durationMillis}ms must not exclude busy wait",
            busyResult.durationMillis >= busy.getValue("busyMillis").toLong(),
        )
        assertTrue(report.capabilities.wal)
    }

    @Test
    fun reopenReadsPersistedChineseText() {
        val details = passed(ProbeId.REOPEN).details
        assertEquals("1", details["rowCount"])
        assertEquals("中文-reopen", details["value"])
        assertEquals("wal", details["journalMode"])
        assertEquals("0", details["defaultForeignKeys"])
    }

    @Test
    fun corruptAndIllegalSqlAreDiagnosable() {
        val illegal = passed(ProbeId.ILLEGAL_SQL_DIAGNOSTICS).details
        assertEquals("1", illegal["missingTableCode"])
        assertEquals("SQL", illegal["missingTableKind"])
        assertEquals("SQL_NO_SUCH_TABLE", illegal["missingTableDiagnostic"])
        assertEquals("SQL", illegal["syntaxKind"])
        assertEquals("SQL_SYNTAX_ERROR", illegal["syntaxDiagnostic"])

        val corrupt = passed(ProbeId.CORRUPT_DIAGNOSTICS).details
        assertEquals("CORRUPT", corrupt["kind"])
        assertTrue(corrupt.getValue("code") in setOf("11", "26"))
        assertTrue(corrupt.getValue("diagnostic") in setOf("CORRUPT_FILE", "NOT_A_DATABASE"))
    }

    @Test
    fun statementAndConnectionCloseResources() {
        val details = passed(ProbeId.RESOURCE_CLOSE).details
        assertEquals("true", details["statementCloseTwice"])
        assertEquals("21", details["statementAfterCloseCode"])
        assertEquals("true", details["connectionCloseTwice"])
        assertEquals("21", details["connectionAfterCloseCode"])
        assertEquals("true", details["reopenAfterClose"])
    }

    @Test
    fun errorsAreDeliveredThroughTheNarrowSink() {
        assertTrue(errors.any { it.sqliteCode == 1 && it.kind == DatabaseErrorKind.SQL })
        assertTrue(errors.any { it.kind == DatabaseErrorKind.CORRUPT })
        assertTrue(errors.any { it.kind == DatabaseErrorKind.CONSTRAINT && it.sqliteCode == 787 })
        assertTrue(errors.any { it.kind == DatabaseErrorKind.BUSY })
        assertTrue(errors.none { it.causeClass?.contains("androidx.sqlite") == true })
        assertTrue(errors.all { it.owner == report.owner })
        assertTrue(errors.all { it.correlationId == report.correlationId })
        assertTrue(errors.none { it.message.isBlank() })
    }

    @Test
    fun reportCarriesOwnerCorrelationIdAndDurations() {
        assertEquals("platform/database", report.owner)
        assertTrue(report.correlationId.isNotBlank())
        assertTrue(report.results.all { it.durationMillis >= 0L })
        assertEquals(report.results.size, logEvents.size)
        assertTrue(logEvents.all { it.owner == report.owner })
        assertTrue(logEvents.all { it.correlationId == report.correlationId })
        assertTrue(logEvents.all { it.durationMillis >= 0L })
    }

    @Test
    fun captureDurationIncludesProbeWork() {
        val root = Files.createTempDirectory("s1-04-duration").toFile()
        try {
            ProbeWorkspace.create(root).use { workspace ->
                val events = mutableListOf<ProbeLogEvent>()
                val context = ProbeContext(
                    factory = SqliteConnectionFactory(),
                    workspace = workspace,
                    owner = "platform/database",
                    correlationId = "duration-correlation",
                    errorSink = DatabaseErrorSink.NONE,
                    log = { events += it },
                )
                val result = context.capture(ProbeId.DRIVER_IDENTITY) {
                    Thread.sleep(40L)
                    mapOf("injectedDelay" to "40ms")
                }
                assertEquals(ProbeStatus.PASSED, result.status)
                assertTrue(
                    "capture duration must include probe work: ${result.durationMillis}ms",
                    result.durationMillis >= 30L,
                )
                assertEquals(result.durationMillis, events.single().durationMillis)
            }
        } finally {
            root.deleteRecursively()
        }
    }

    @Test
    fun publicContractsDoNotExposeSqliteTypes() {
        val publicTypes = listOf(
            DatabaseCapabilityProbe::class.java,
            BundledSqliteCapabilityProbe::class.java,
            DatabaseProbeReport::class.java,
            ProbeResult::class.java,
            DatabaseError::class.java,
            ProbeLogEvent::class.java,
        )
        val exposed = publicTypes.flatMap { type ->
            type.declaredMethods.map { method ->
                method.name + ":" + method.returnType.name + ":" +
                    method.parameterTypes.joinToString(",") { it.name }
            }
        }.filter { it.contains("androidx.sqlite") }
        assertTrue("public API exposes SQLite types: $exposed", exposed.isEmpty())
    }

    @Test
    fun secretSqlTokenNeverReachesTheErrorPortOrReport() {
        val secret = "S1-04-SECRET-TOKEN-7f3a"
        val root = Files.createTempDirectory("s1-04-secret-$secret").toFile()
        val sinkErrors = mutableListOf<DatabaseError>()
        val auditLog = mutableListOf<ProbeLogEvent>()
        try {
            ProbeWorkspace.create(root).use { workspace ->
                val context = ProbeContext(
                    factory = SqliteConnectionFactory(),
                    workspace = workspace,
                    owner = "platform/database",
                    correlationId = "secret-correlation",
                    errorSink = { sinkErrors += it },
                    log = { auditLog += it },
                )
                val error = captureDatabaseError {
                    context.withSession("secret.db") { session ->
                        session.queryLong("select count(*) from \"$secret\"")
                    }
                }
                assertNotNull(error)
                assertEquals(DatabaseDiagnostic.SQL_NO_SUCH_TABLE, error?.diagnostic)
                assertFalse(error.toString().contains(secret))
                assertFalse(error?.message.orEmpty().contains(secret))

                val publicError = context.report(error ?: error("unreachable"))
                assertFalse(publicError.toString().contains(secret))
                assertEquals("secret-correlation", sinkErrors.single().correlationId)
                assertFalse(sinkErrors.joinToString().contains(secret))

                val unexpected = unexpectedError("secret-operation", IllegalStateException(secret))
                assertFalse(unexpected.toString().contains(secret))
                assertFalse(unexpected.message.contains(secret))
                context.report(unexpected)
                assertTrue(sinkErrors.all { !it.toString().contains(secret) })
                assertEquals(2, sinkErrors.size)

                context.capture(ProbeId.DRIVER_IDENTITY) {
                    mapOf("fixtureDetail" to secret)
                }
                assertTrue(auditLog.isNotEmpty())
                assertFalse(auditLog.joinToString().contains(secret))
            }
            val pathReport = BundledSqliteCapabilityProbe(root).probe()
            assertFalse(pathReport.toString().contains(secret))
            assertFalse(pathReport.correlationId.contains(secret))
        } finally {
            root.deleteRecursively()
        }
    }

    private fun passed(id: ProbeId): ProbeResult {
        val result = report.result(id)
        assertNotNull("missing probe $id", result)
        assertEquals("probe $id failed: ${result?.error}", ProbeStatus.PASSED, result?.status)
        return result ?: error("missing probe $id")
    }

    private fun failureSummary(): String =
        report.results
            .filter { it.status != ProbeStatus.PASSED }
            .joinToString { "${it.id}=${it.status}:${it.error?.message}" }

    private companion object {
        private lateinit var root: File
        private lateinit var report: DatabaseProbeReport
        private val errors = mutableListOf<DatabaseError>()
        private val logEvents = mutableListOf<ProbeLogEvent>()

        @BeforeClass
        @JvmStatic
        fun runProbeMatrix() {
            root = Files.createTempDirectory("s1-04-jvm-probe").toFile()
            report = BundledSqliteCapabilityProbe(root).probe(
                errorSink = { errors += it },
                log = { logEvents += it },
            )
        }

        @AfterClass
        @JvmStatic
        fun removeProbeMatrix() {
            root.deleteRecursively()
        }
    }
}
