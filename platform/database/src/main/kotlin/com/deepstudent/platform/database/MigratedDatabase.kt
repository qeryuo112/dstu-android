package com.deepstudent.platform.database

import android.content.Context
import java.io.File

/** The four migration-managed shared databases; browser storage is not a shared database. */
public enum class SharedDatabase(public val id: String) {
    VFS("vfs"), CHAT_V2("chat_v2"), MISTAKES("mistakes"), LLM_USAGE("llm_usage"),
}

/** Supplies the immutable packaged lock and SQL bytes by their repository-relative path. */
public fun interface MigrationSource {
    public fun read(path: String): ByteArray
}

/** Android assets produced directly from the frozen repository inputs at build time. */
public class BundledMigrationSource(context: Context) : MigrationSource {
    private val assets = context.applicationContext.assets

    override fun read(path: String): ByteArray = assets.open(path).use { it.readBytes() }
}

/** Parameters are positional and bound from index 1; no driver types cross the port. */
public sealed interface SqlValue {
    public data object Null : SqlValue
    public data class Integer(val value: Long) : SqlValue
    public data class Real(val value: Double) : SqlValue
    public data class Text(val value: String) : SqlValue
    public class Blob(value: ByteArray) : SqlValue {
        private val bytes = value.copyOf()
        public fun copyBytes(): ByteArray = bytes.copyOf()
    }
}

/** Valid only during its query mapper invocation; column indexes start at zero. */
public interface SqlRow {
    public fun isNull(index: Int): Boolean
    public fun long(index: Int): Long
    public fun double(index: Int): Double
    public fun text(index: Int): String
    public fun blob(index: Int): ByteArray
}

/** Read-only operations; both the scope and rows are valid only in their callbacks. */
public interface SqlReadOperations {
    public fun <T> query(sql: String, args: List<SqlValue> = emptyList(), map: (SqlRow) -> T): List<T>
}

/** One statement per operation. Implementations own and close the statement. */
public interface SqlOperations : SqlReadOperations {
    public fun execute(sql: String, args: List<SqlValue> = emptyList())
}

/** Synchronous, serialized same-database access; no network or user interaction in a transaction. */
public interface SqlDatabase : SqlOperations, AutoCloseable {
    public val migrationReport: MigrationReport?
    public fun <T> transaction(block: SqlOperations.() -> T): T

    /** A WAL snapshot fixed before the callback; it cannot write or escape its owner thread. */
    public fun <T> readSnapshot(block: SqlReadOperations.() -> T): T
}

public object DatabaseFactory {
    public fun openShared(
        file: File,
        database: SharedDatabase,
        source: MigrationSource,
        log: MigrationLog = MigrationLog.NONE,
    ): SqlDatabase = DatabaseRegistry.openShared(file, database, source, migrationLog = log)

    /** Local databases have no shared history or shared schema fingerprint. */
    public fun openLocal(file: File): SqlDatabase = DatabaseRegistry.openLocal(file)
}

public data class MigrationReport(
    val database: SharedDatabase,
    val fromVersion: Int,
    val toVersion: Int,
    val appliedCount: Int,
    val fingerprint: String,
)

/** Whitelisted structured fields only; never contains SQL, paths, row data, or exception messages. */
public data class MigrationLogEvent(
    val correlationId: String,
    val database: SharedDatabase,
    val phase: String,
    val status: String,
    val durationMillis: Long,
    val version: Int? = null,
    val diagnostic: MigrationDiagnostic? = null,
    val details: MigrationLogDetails = MigrationLogDetails(),
) {
    public val owner: String get() = "platform/database"
    public val fromVersion: Int? get() = details.report?.fromVersion
    public val appliedCount: Int? get() = details.report?.appliedCount
    public val fingerprint: String? get() = details.report?.fingerprint
    public val objectType: String? get() = details.objectType
    public val objectName: String? get() = details.objectName
}

/** Only frozen names and the already-verified report may enter the structured event. */
public data class MigrationLogDetails(
    val report: MigrationReport? = null,
    val objectType: String? = null,
    val objectName: String? = null,
    val status: MigrationStatus? = null,
)

public fun interface MigrationLog {
    public fun onEvent(event: MigrationLogEvent)

    public companion object {
        public val NONE: MigrationLog = MigrationLog { }
    }
}

public enum class MigrationDiagnostic(public val fixedMessage: String) {
    SOURCE_INVALID("migration source is invalid"),
    SQL_BYTES_DRIFT("migration bytes differ from the frozen source"),
    REFINERY_DRIFT("migration refinery checksum differs from the frozen source"),
    HISTORY_DRIFT("database migration history differs from the frozen source"),
    SCHEMA_DRIFT("managed schema differs from the frozen source"),
    MIGRATION_FAILED("migration failed"),
    STATEMENT_INVALID("expected one complete SQL statement"),
    DATABASE_FAILED("database operation failed"),
}

/** Only frozen identifiers are exposed for drift location, never raw SQL or exception messages. */
public class MigrationException(
    public val diagnostic: MigrationDiagnostic,
    public val database: SharedDatabase? = null,
    public val version: Int? = null,
    public val objectType: String? = null,
    public val objectName: String? = null,
    public val causeClass: String? = null,
) : RuntimeException(diagnostic.fixedMessage)
