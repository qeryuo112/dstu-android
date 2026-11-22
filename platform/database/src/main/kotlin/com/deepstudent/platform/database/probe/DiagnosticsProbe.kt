package com.deepstudent.platform.database.probe

import com.deepstudent.platform.database.DatabaseError
import com.deepstudent.platform.database.DatabaseDiagnostic
import com.deepstudent.platform.database.DatabaseErrorKind
import com.deepstudent.platform.database.ProbeId
import com.deepstudent.platform.database.ProbeResult
import com.deepstudent.platform.database.internal.DatabaseFailure
import com.deepstudent.platform.database.internal.ProbeContext
import com.deepstudent.platform.database.internal.ProbeFamily
import com.deepstudent.platform.database.internal.captureDatabaseError
import com.deepstudent.platform.database.internal.expectEquals
import com.deepstudent.platform.database.internal.expectTrue

internal class DiagnosticsProbe : ProbeFamily {
    override fun run(context: ProbeContext): List<ProbeResult> = listOf(
        context.capture(ProbeId.ILLEGAL_SQL_DIAGNOSTICS) { illegalSql(context) },
        context.capture(ProbeId.CORRUPT_DIAGNOSTICS) { corrupt(context) },
    )

    private fun illegalSql(context: ProbeContext): Map<String, String> =
        context.withSession("diagnostics.db") { session ->
            val missing = captureDatabaseError {
                session.queryLong("select count(*) from table_that_does_not_exist")
            }
            val syntax = captureDatabaseError { session.exec("selec 1") }
            expectTrue(missing != null, "missing table must produce a database error")
            expectTrue(syntax != null, "invalid SQL must produce a database error")
            val missingError = missing ?: error("unreachable")
            val syntaxError = syntax ?: error("unreachable")
            context.report(missingError)
            context.report(syntaxError)
            expectEquals(missingError.kind, DatabaseErrorKind.SQL, "missing table kind")
            expectEquals(missingError.sqliteCode, 1, "missing table code")
            expectEquals(
                missingError.diagnostic,
                DatabaseDiagnostic.SQL_NO_SUCH_TABLE,
                "missing table diagnostic",
            )
            expectEquals(
                syntaxError.diagnostic,
                DatabaseDiagnostic.SQL_SYNTAX_ERROR,
                "syntax diagnostic",
            )
            mapOf(
                "missingTableCode" to missingError.sqliteCode.toString(),
                "missingTableKind" to missingError.kind.name,
                "missingTableDiagnostic" to missingError.diagnostic.name,
                "syntaxCode" to syntaxError.sqliteCode.toString(),
                "syntaxKind" to syntaxError.kind.name,
                "syntaxDiagnostic" to syntaxError.diagnostic.name,
            )
        }

    private fun corrupt(context: ProbeContext): Map<String, String> {
        val file = context.workspace.file("corrupt.db")
        file.writeBytes("not-a-sqlite-database".toByteArray())
        val error = openAndQuery(context, file)
        expectTrue(error != null, "corrupt database must produce a database error")
        val actual = error ?: error("unreachable")
        context.report(actual)
        expectTrue(
            actual.kind == DatabaseErrorKind.CORRUPT,
            "corrupt database kind must be CORRUPT, actual ${actual.kind}",
        )
        expectTrue(
            actual.sqliteCode in CORRUPT_CODES,
            "corrupt database code must be CORRUPT/NOTADB, actual ${actual.sqliteCode}",
        )
        expectTrue(
            actual.diagnostic == DatabaseDiagnostic.CORRUPT_FILE ||
                actual.diagnostic == DatabaseDiagnostic.NOT_A_DATABASE,
            "corrupt database diagnostic must be fixed, actual ${actual.diagnostic}",
        )
        return mapOf(
            "code" to actual.sqliteCode.toString(),
            "kind" to actual.kind.name,
            "diagnostic" to actual.diagnostic.name,
            "fileBytes" to file.length().toString(),
        )
    }

    private fun openAndQuery(context: ProbeContext, file: java.io.File): DatabaseError? =
        try {
            context.factory.open(file).use { session ->
                captureDatabaseError { session.queryLong("select count(*) from sqlite_master") }
            }
        } catch (failure: DatabaseFailure) {
            failure.error
        }

    private companion object {
        val CORRUPT_CODES = setOf(11, 26)
    }
}
