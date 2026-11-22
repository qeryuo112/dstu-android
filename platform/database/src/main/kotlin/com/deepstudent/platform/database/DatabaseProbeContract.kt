package com.deepstudent.platform.database

/** The fixed probe families required by S1.04. */
public enum class ProbeId {
    DRIVER_IDENTITY,
    VERSION,
    COMPILE_OPTIONS,
    FTS5_TRIGRAM,
    FTS5_CONTENTLESS_DELETE,
    JSON1,
    JSONB,
    WAL_SNAPSHOT,
    WAL_BUSY_TIMEOUT,
    SAVEPOINT,
    TRANSACTION,
    FOREIGN_KEYS,
    INTEGER_BOUNDARIES,
    BLOB_NUL,
    NULL_VALUE,
    REOPEN,
    CORRUPT_DIAGNOSTICS,
    ILLEGAL_SQL_DIAGNOSTICS,
    RESOURCE_CLOSE,
}

public enum class ProbeStatus {
    PASSED,
    FAILED,
    UNSUPPORTED,
}

/**
 * One probe result.
 *
 * [details] is the explicit evidence whitelist written by the probe family:
 * fixed fixture values, fixed capability names and measured durations. Raw
 * driver messages and raw SQL values never enter it. A failed probe carries
 * a fixed-diagnostic [error] and may carry only a fixed assertion label.
 */
public data class ProbeResult(
    val id: ProbeId,
    val status: ProbeStatus,
    val durationMillis: Long,
    val details: Map<String, String> = emptyMap(),
    val error: DatabaseError? = null,
)

/** Identity of the driver actually used, not of an Android system fallback. */
public data class DriverIdentity(
    val coordinate: String,
    val implementationClass: String,
    val hasConnectionPool: Boolean,
)

/** Identity and compile-time evidence reported by the opened SQLite library. */
public data class SqliteIdentity(
    val version: String,
    val sourceId: String,
    val compileOptions: Set<String>,
    val threadingMode: Int?,
    val json1CompileOption: Boolean,
)

/** Capabilities proven by the probe run, not inferred from the SDK version. */
public data class DatabaseCapabilities(
    val fts5: Boolean,
    val fts5Trigram: Boolean,
    val fts5ContentlessDelete: Boolean,
    val json1: Boolean,
    val jsonb: Boolean,
    val wal: Boolean,
    val savepoint: Boolean,
    val foreignKeys: Boolean,
)

/**
 * Complete S1.04 evidence. A missing or non-passing probe is visible in
 * [results]; consumers must not infer success from the absence of an error.
 */
public data class DatabaseProbeReport(
    val owner: String,
    val correlationId: String,
    val driver: DriverIdentity,
    val sqlite: SqliteIdentity,
    val capabilities: DatabaseCapabilities,
    val results: List<ProbeResult>,
) {
    public fun result(id: ProbeId): ProbeResult? = results.firstOrNull { it.id == id }

    public val allPassed: Boolean
        get() = results.isNotEmpty() && results.all { it.status == ProbeStatus.PASSED }

    public val passedCount: Int
        get() = results.count { it.status == ProbeStatus.PASSED }
}

/**
 * Privacy-safe structured log event.
 *
 * Only fixed detail keys are carried; detail values, SQL text and raw driver
 * messages are never included.
 */
public data class ProbeLogEvent(
    val owner: String,
    val correlationId: String,
    val probeId: ProbeId?,
    val status: String,
    val durationMillis: Long,
    val detailKeys: Set<String> = emptySet(),
    val errorKind: String? = null,
    val errorDiagnostic: String? = null,
)

/** Narrow structured-log port for S1.04 evidence. */
public fun interface DatabaseProbeLog {
    public fun onEvent(event: ProbeLogEvent)

    public companion object {
        public val NONE: DatabaseProbeLog = DatabaseProbeLog { }
    }
}

/**
 * The only public entry point for S1.04 capability evidence.
 *
 * The implementation owns connections and statements. Callers cannot open a
 * connection or execute SQL through this port.
 */
public interface DatabaseCapabilityProbe {
    public fun probe(
        errorSink: DatabaseErrorSink = DatabaseErrorSink.NONE,
        log: DatabaseProbeLog = DatabaseProbeLog.NONE,
    ): DatabaseProbeReport
}
