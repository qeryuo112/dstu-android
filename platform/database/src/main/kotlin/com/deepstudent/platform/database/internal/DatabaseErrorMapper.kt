package com.deepstudent.platform.database.internal

import com.deepstudent.platform.database.DatabaseError
import com.deepstudent.platform.database.DatabaseDiagnostic
import com.deepstudent.platform.database.DatabaseErrorKind

private val SQLITE_CODE = Regex("""(?:Error code:|\bcode\s+)\s*(\d+)""", RegexOption.IGNORE_CASE)

@Suppress("TooGenericExceptionCaught")
internal fun <T> guardSql(operation: String, block: () -> T): T {
    return try {
        block()
    } catch (failure: Exception) {
        if (!failure.isSqliteFailure()) throw failure
        throw DatabaseFailure(failure.toDatabaseError(operation))
    }
}

internal fun Exception.toDatabaseError(operation: String): DatabaseError {
    val causes = generateSequence<Throwable>(this) { it.cause }.take(MAX_CAUSE_DEPTH).toList()
    val rawMessage = causes.joinToString(" ") { it.message.orEmpty() }
    val code = SQLITE_CODE.find(rawMessage)?.groupValues?.get(1)?.toIntOrNull()
    val busyByType = causes.any { it.javaClass.name in ANDROID_BUSY_EXCEPTIONS }
    val kind = if (busyByType) DatabaseErrorKind.BUSY else kindFor(operation, code)
    val diagnostic = if (busyByType) DatabaseDiagnostic.BUSY_LOCK else diagnosticFor(operation, code, rawMessage)
    return DatabaseError(kind = kind, diagnostic = diagnostic, operation = operation, sqliteCode = code)
}

/**
 * The AndroidX `SQLiteException` is an actual typealias to
 * `android.database.SQLException` on Android and a JVM class in the desktop
 * artifact. Match by name so the same probe code works on both runtimes.
 */
private fun Exception.isSqliteFailure(): Boolean {
    var current: Throwable? = this
    var depth = 0
    while (current != null && depth < MAX_CAUSE_DEPTH) {
        var type: Class<*>? = current.javaClass
        while (type != null) {
            when (type.name) {
                ANDROIDX_SQLITE_EXCEPTION, ANDROID_SQL_EXCEPTION, ANDROID_SQLITE_EXCEPTION -> return true
            }
            type = type.superclass
        }
        current = current.cause
        depth++
    }
    return false
}

internal fun unexpectedError(operation: String, failure: Exception): DatabaseError =
    DatabaseError(
        kind = DatabaseErrorKind.UNKNOWN,
        diagnostic = DatabaseDiagnostic.UNKNOWN_FAILURE,
        operation = operation,
        causeClass = failure.javaClass.name,
    )

private fun kindFor(operation: String, code: Int?): DatabaseErrorKind = when {
    primaryCode(code) in CORRUPT_CODES -> DatabaseErrorKind.CORRUPT
    primaryCode(code) in BUSY_CODES -> DatabaseErrorKind.BUSY
    operation.startsWith("open") -> DatabaseErrorKind.OPEN
    code == null -> DatabaseErrorKind.UNKNOWN
    code in CONSTRAINT_CODES -> DatabaseErrorKind.CONSTRAINT
    code == MISUSE_CODE -> DatabaseErrorKind.MISUSE
    code == SQL_ERROR_CODE -> DatabaseErrorKind.SQL
    else -> DatabaseErrorKind.UNKNOWN
}

private fun diagnosticFor(operation: String, code: Int?, rawMessage: String): DatabaseDiagnostic {
    codeDiagnostic(code)?.let { return it }
    if (code == SQL_ERROR_CODE) return sqlDiagnostic(rawMessage.lowercase())
    return operationDiagnostic(operation)
}

/** Fixed diagnostics that depend only on a SQLite result code. */
private fun codeDiagnostic(code: Int?): DatabaseDiagnostic? = when {
    primaryCode(code) == 11 -> DatabaseDiagnostic.CORRUPT_FILE
    primaryCode(code) == 26 -> DatabaseDiagnostic.NOT_A_DATABASE
    primaryCode(code) in BUSY_CODES -> DatabaseDiagnostic.BUSY_LOCK
    code == 787 -> DatabaseDiagnostic.FOREIGN_KEY_FAILED
    code in CONSTRAINT_CODES -> DatabaseDiagnostic.CONSTRAINT_FAILED
    code == MISUSE_CODE -> DatabaseDiagnostic.MISUSE_CLOSED
    else -> null
}

/** Fixed diagnostics for errors that carry neither a classified code nor SQL text. */
private fun operationDiagnostic(operation: String): DatabaseDiagnostic =
    if (operation.startsWith("open")) {
        DatabaseDiagnostic.OPEN_FAILED
    } else {
        DatabaseDiagnostic.UNKNOWN_FAILURE
    }

/** Classifies SQLITE_ERROR text into a fixed category; text is never exposed. */
private fun sqlDiagnostic(lowered: String): DatabaseDiagnostic = when {
    "no such table" in lowered -> DatabaseDiagnostic.SQL_NO_SUCH_TABLE
    "no such column" in lowered -> DatabaseDiagnostic.SQL_NO_SUCH_COLUMN
    "no such function" in lowered -> DatabaseDiagnostic.SQL_NO_SUCH_FUNCTION
    "syntax error" in lowered -> DatabaseDiagnostic.SQL_SYNTAX_ERROR
    else -> DatabaseDiagnostic.SQL_REJECTED
}

private fun primaryCode(code: Int?): Int? = code?.and(0xff)

private const val MAX_CAUSE_DEPTH = 4
private const val MISUSE_CODE = 21
private const val SQL_ERROR_CODE = 1
private const val ANDROIDX_SQLITE_EXCEPTION = "androidx.sqlite.SQLiteException"
private const val ANDROID_SQL_EXCEPTION = "android.database.SQLException"
private const val ANDROID_SQLITE_EXCEPTION = "android.database.sqlite.SQLiteException"
private val BUSY_CODES = setOf(5, 6)
private val CORRUPT_CODES = setOf(11, 26)
private val CONSTRAINT_CODES = setOf(19, 275, 531, 787, 1299, 1555, 2067)

private val ANDROID_BUSY_EXCEPTIONS = setOf(
    "android.database.sqlite.SQLiteDatabaseLockedException",
    "android.database.sqlite.SQLiteTableLockedException",
    "android.database.sqlite.SQLiteBusyException",
    "android.database.sqlite.SQLiteLockedException",
)
