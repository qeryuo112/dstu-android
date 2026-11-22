package com.deepstudent.platform.database.internal

import com.deepstudent.platform.database.MigrationDiagnostic

/** SQLite complete.c transition table, including CREATE TRIGGER ... BEGIN ... END. */
private val TRANSITIONS = arrayOf(
    intArrayOf(1, 0, 2, 3, 4, 2, 2, 2),
    intArrayOf(1, 1, 2, 3, 4, 2, 2, 2),
    intArrayOf(1, 2, 2, 2, 2, 2, 2, 2),
    intArrayOf(1, 3, 3, 2, 4, 2, 2, 2),
    intArrayOf(1, 4, 2, 2, 2, 4, 5, 2),
    intArrayOf(6, 5, 5, 5, 5, 5, 5, 5),
    intArrayOf(6, 6, 5, 5, 5, 5, 5, 7),
    intArrayOf(1, 7, 5, 5, 5, 5, 5, 5),
)

private data class SqlToken(val type: Int, val end: Int)
private const val SEMI = 0
private const val SPACE = 1
private const val OTHER = 2

internal fun splitStatements(sql: String): List<String> {
    if ('\u0000' in sql) fail(MigrationDiagnostic.STATEMENT_INVALID)
    return SqlStatementScanner(sql).split()
}

private class SqlStatementScanner(private val sql: String) {
    val statements = mutableListOf<String>()
    private var state = 0
    private var start = 0
    private var cursor = 0
    private var hasContent = false

    fun split(): List<String> {
        while (cursor < sql.length) accept(nextToken(sql, cursor))
        if (hasContent) appendTail()
        return statements
    }

    private fun accept(token: SqlToken) {
        if (token.type != SPACE && token.type != SEMI) hasContent = true
        state = TRANSITIONS[state][token.type]
        cursor = token.end
        if (token.type == SEMI && state == 1) {
            if (hasContent) statements.add(sql.substring(start, cursor))
            start = cursor
            hasContent = false
        }
    }

    private fun appendTail() {
        if (TRANSITIONS[state][SEMI] != 1) fail(MigrationDiagnostic.STATEMENT_INVALID)
        statements.add(sql.substring(start))
    }
}

private fun nextToken(sql: String, start: Int): SqlToken {
    val ch = sql[start]
    return when {
        ch == ';' -> SqlToken(SEMI, start + 1)
        // SQLite treats a UTF-8 BOM at the start of a token as whitespace.
        ch.isWhitespace() || ch == '\uFEFF' -> SqlToken(SPACE, start + 1)
        sql.startsWith("--", start) -> SqlToken(SPACE, lineCommentEnd(sql, start))
        sql.startsWith("/*", start) -> SqlToken(SPACE, blockCommentEnd(sql, start))
        isQuote(ch) -> SqlToken(OTHER, quotedEnd(sql, start))
        isIdentifier(ch) -> identifierToken(sql, start)
        else -> SqlToken(OTHER, start + 1)
    }
}

private fun isQuote(ch: Char): Boolean = ch == '\'' || ch == '"' || ch == '`' || ch == '['

private fun lineCommentEnd(sql: String, start: Int): Int {
    val end = sql.indexOf('\n', start + 2)
    return if (end < 0) sql.length else end + 1
}

private fun blockCommentEnd(sql: String, start: Int): Int {
    val end = sql.indexOf("*/", start + 2)
    if (end < 0) fail(MigrationDiagnostic.STATEMENT_INVALID)
    return end + 2
}

private fun quotedEnd(sql: String, start: Int): Int {
    val close = if (sql[start] == '[') ']' else sql[start]
    var cursor = start + 1
    while (cursor < sql.length) {
        if (sql[cursor] == close) {
            if (close != ']' && sql.getOrNull(cursor + 1) == close) {
                cursor += 2
                continue
            }
            return cursor + 1
        }
        cursor++
    }
    fail(MigrationDiagnostic.STATEMENT_INVALID)
}

private fun isIdentifier(ch: Char): Boolean = ch.isLetterOrDigit() || ch == '_' || ch == '$' || ch.code >= 128

private fun identifierToken(sql: String, start: Int): SqlToken {
    var end = start + 1
    while (end < sql.length && isIdentifier(sql[end])) end++
    val type = when (sql.substring(start, end).uppercase()) {
        "EXPLAIN" -> 3
        "CREATE" -> 4
        "TEMP", "TEMPORARY" -> 5
        "TRIGGER" -> 6
        "END" -> 7
        else -> OTHER
    }
    return SqlToken(type, end)
}

internal fun isDropTable(statement: String): Boolean {
    return Regex("^DROP\\s+TABLE\\b", RegexOption.IGNORE_CASE).containsMatchIn(withoutLeadingTrivia(statement))
}

internal fun validatePublicStatement(sql: String) {
    val parts = splitStatements(sql)
    if (parts.size != 1) fail(MigrationDiagnostic.STATEMENT_INVALID)
    val statement = withoutExplainPrefix(parts.single())
    val keyword = statement.takeWhile(::isIdentifier).uppercase()
    if (keyword in setOf("BEGIN", "END", "COMMIT", "ROLLBACK", "SAVEPOINT", "RELEASE", "ATTACH", "DETACH")) {
        fail(MigrationDiagnostic.STATEMENT_INVALID)
    }
    if (keyword == "PRAGMA" && !READ_ONLY_PRAGMA.matches(statement.trim())) {
        fail(MigrationDiagnostic.STATEMENT_INVALID)
    }
}

/** EXPLAIN changes step-time behavior only; prepare-time PRAGMA effects still run. */
private fun withoutExplainPrefix(sql: String): String {
    var statement = withoutLeadingTrivia(sql)
    if (statement.takeWhile(::isIdentifier).uppercase() != "EXPLAIN") return statement
    statement = withoutLeadingTrivia(statement.substring("EXPLAIN".length))
    if (statement.takeWhile(::isIdentifier).uppercase() == "QUERY") {
        val afterQuery = withoutLeadingTrivia(statement.substring("QUERY".length))
        if (afterQuery.takeWhile(::isIdentifier).uppercase() == "PLAN") {
            statement = withoutLeadingTrivia(afterQuery.substring("PLAN".length))
        }
    }
    return statement
}

private fun withoutLeadingTrivia(statement: String): String {
    var cursor = 0
    while (cursor < statement.length) {
        val token = nextToken(statement, cursor)
        if (token.type != SPACE) break
        cursor = token.end
    }
    return statement.substring(cursor)
}

private val READ_ONLY_PRAGMA = Regex(
    "PRAGMA\\s+(integrity_check|foreign_key_check|journal_mode|foreign_keys|busy_timeout|synchronous)\\s*;?\\s*",
    RegexOption.IGNORE_CASE,
)
