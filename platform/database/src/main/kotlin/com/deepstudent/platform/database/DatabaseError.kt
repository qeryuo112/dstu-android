package com.deepstudent.platform.database

/**
 * Stable, driver-independent error categories exposed by this module.
 *
 * No SQLite implementation type crosses this boundary. Callers receive a
 * [DatabaseError] through a report or [DatabaseErrorSink].
 */
public enum class DatabaseErrorKind {
    OPEN,
    BUSY,
    CONSTRAINT,
    CORRUPT,
    MISUSE,
    SQL,
    ASSERTION,
    UNSUPPORTED,
    UNKNOWN,
}

/**
 * Fixed, code-based diagnostics.
 *
 * The public message is deliberately a constant. Raw driver messages can
 * contain paths, SQL text, bound values or credentials and must not cross the
 * error port or enter a log.
 */
public enum class DatabaseDiagnostic(
    public val fixedMessage: String,
) {
    OPEN_FAILED("database open failed"),
    BUSY_LOCK("database is busy"),
    CONSTRAINT_FAILED("database constraint failed"),
    FOREIGN_KEY_FAILED("foreign key constraint failed"),
    CORRUPT_FILE("database file is corrupt"),
    NOT_A_DATABASE("file is not a database"),
    MISUSE_CLOSED("database resource is closed or misused"),
    SQL_NO_SUCH_TABLE("SQL referenced a missing table"),
    SQL_NO_SUCH_COLUMN("SQL referenced a missing column"),
    SQL_NO_SUCH_FUNCTION("SQL referenced a missing function"),
    SQL_SYNTAX_ERROR("SQL syntax error"),
    SQL_REJECTED("SQL statement was rejected"),
    PROBE_ASSERTION("probe assertion failed"),
    UNSUPPORTED("capability is unsupported"),
    UNKNOWN_FAILURE("database operation failed"),
}

/**
 * A sanitized database failure.
 *
 * [sqliteCode] is the numeric code parsed from the driver message when
 * available. It is diagnostic evidence, not a type that callers must switch
 * on. [message] is fixed by [diagnostic] and never contains the raw driver
 * message. [causeClass] names an unexpected exception class for diagnosis.
 */
public data class DatabaseError(
    val kind: DatabaseErrorKind,
    val diagnostic: DatabaseDiagnostic,
    val operation: String,
    val sqliteCode: Int? = null,
    val owner: String = "platform/database",
    val correlationId: String? = null,
    val causeClass: String? = null,
) {
    public val message: String
        get() = diagnostic.fixedMessage
}

/** Narrow error port for owners that need to record failures outside a report. */
public fun interface DatabaseErrorSink {
    public fun onError(error: DatabaseError)

    public companion object {
        public val NONE: DatabaseErrorSink = DatabaseErrorSink { }
    }
}
