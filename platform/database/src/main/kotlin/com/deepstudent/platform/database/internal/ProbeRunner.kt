package com.deepstudent.platform.database.internal

import com.deepstudent.platform.database.DatabaseCapabilities
import com.deepstudent.platform.database.DatabaseErrorSink
import com.deepstudent.platform.database.DatabaseProbeLog
import com.deepstudent.platform.database.DatabaseProbeReport
import com.deepstudent.platform.database.DriverIdentity
import com.deepstudent.platform.database.ProbeId
import com.deepstudent.platform.database.ProbeLogEvent
import com.deepstudent.platform.database.ProbeResult
import com.deepstudent.platform.database.ProbeStatus
import com.deepstudent.platform.database.SqliteIdentity
import com.deepstudent.platform.database.probe.DiagnosticsProbe
import com.deepstudent.platform.database.probe.DriverProbe
import com.deepstudent.platform.database.probe.ForeignKeyProbe
import com.deepstudent.platform.database.probe.FtsProbe
import com.deepstudent.platform.database.probe.JsonProbe
import com.deepstudent.platform.database.probe.ReopenProbe
import com.deepstudent.platform.database.probe.ResourceProbe
import com.deepstudent.platform.database.probe.TransactionProbe
import com.deepstudent.platform.database.probe.ValueProbe
import com.deepstudent.platform.database.probe.VersionProbe
import com.deepstudent.platform.database.probe.WalProbe
import java.io.File
import java.util.UUID

/** Runs probe families in a fixed order and builds the immutable evidence report. */
internal class ProbeRunner {

    @Suppress("TooGenericExceptionCaught")
    fun run(
        workDirectory: File,
        errorSink: DatabaseErrorSink,
        log: DatabaseProbeLog,
    ): DatabaseProbeReport {
        val factory = SqliteConnectionFactory()
        val correlationId = UUID.randomUUID().toString()
        return try {
            ProbeWorkspace.create(workDirectory).use { workspace ->
                val context = ProbeContext(
                    factory = factory,
                    workspace = workspace,
                    owner = OWNER,
                    correlationId = correlationId,
                    errorSink = errorSink,
                    log = log,
                )
                buildReport(OWNER, correlationId, factory, families().flatMap { it.run(context) })
            }
        } catch (failure: Exception) {
            val error = unexpectedError("probe-run", failure)
                .copy(owner = OWNER, correlationId = correlationId)
            runCatching { errorSink.onError(error) }
            runCatching {
                log.onEvent(
                    ProbeLogEvent(
                        owner = OWNER,
                        correlationId = correlationId,
                        probeId = ProbeId.VERSION,
                        status = ProbeStatus.FAILED.name,
                        durationMillis = 0,
                        errorKind = error.kind.name,
                        errorDiagnostic = error.diagnostic.name,
                    ),
                )
            }
            buildReport(
                OWNER,
                correlationId,
                factory,
                listOf(ProbeResult(ProbeId.VERSION, ProbeStatus.FAILED, 0, error = error)),
            )
        }
    }

    private fun families(): List<ProbeFamily> = listOf(
        DriverProbe(),
        VersionProbe(),
        FtsProbe(),
        JsonProbe(),
        TransactionProbe(),
        ForeignKeyProbe(),
        ValueProbe(),
        WalProbe(),
        ReopenProbe(),
        DiagnosticsProbe(),
        ResourceProbe(),
    )

    private fun buildReport(
        owner: String,
        correlationId: String,
        factory: SqliteConnectionFactory,
        results: List<ProbeResult>,
    ): DatabaseProbeReport {
        val version = results.firstOrNull { it.id == ProbeId.VERSION }?.details.orEmpty()
        val options = results.firstOrNull { it.id == ProbeId.COMPILE_OPTIONS }?.details.orEmpty()
        val compileOptions = options["compileOptions"]
            ?.lineSequence()
            ?.filter { it.isNotBlank() }
            ?.toSet()
            .orEmpty()
        return DatabaseProbeReport(
            owner = owner,
            correlationId = correlationId,
            driver = DriverIdentity(
                coordinate = factory.coordinate,
                implementationClass = factory.implementationClass,
                hasConnectionPool = factory.hasConnectionPool,
            ),
            sqlite = SqliteIdentity(
                version = version["version"].orEmpty(),
                sourceId = version["sourceId"].orEmpty(),
                compileOptions = compileOptions,
                threadingMode = options["threadingMode"]?.toIntOrNull(),
                json1CompileOption = options["json1CompileOption"] == "true",
            ),
            capabilities = capabilities(results, compileOptions),
            results = results.toList(),
        )
    }

    private fun capabilities(
        results: List<ProbeResult>,
        compileOptions: Set<String>,
    ): DatabaseCapabilities = DatabaseCapabilities(
        fts5 = compileOptions.contains("ENABLE_FTS5") || passed(results, ProbeId.FTS5_TRIGRAM),
        fts5Trigram = passed(results, ProbeId.FTS5_TRIGRAM),
        fts5ContentlessDelete = passed(results, ProbeId.FTS5_CONTENTLESS_DELETE),
        json1 = passed(results, ProbeId.JSON1),
        jsonb = passed(results, ProbeId.JSONB),
        wal = passed(results, ProbeId.WAL_SNAPSHOT) && passed(results, ProbeId.WAL_BUSY_TIMEOUT),
        savepoint = passed(results, ProbeId.SAVEPOINT),
        foreignKeys = passed(results, ProbeId.FOREIGN_KEYS),
    )

    private fun passed(results: List<ProbeResult>, id: ProbeId): Boolean =
        results.any { it.id == id && it.status == ProbeStatus.PASSED }

    private companion object {
        const val OWNER = "platform/database"
    }
}
