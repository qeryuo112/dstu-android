package com.deepstudent.platform.database.internal

import androidx.sqlite.SQLiteStatement

/** The frozen canonical text stays byte-for-byte compatible with the desktop fingerprint. */
internal data class CanonicalSchema(
    val text: String,
    val parts: Map<Pair<String, String>, SchemaPart> = emptyMap(),
) {
    val hash: String get() = sha256(text.toByteArray(Charsets.UTF_8))
}

/** Separate fields, rather than parsing potentially hostile SQL or column names as delimiters. */
internal data class SchemaPart(val owner: String?, val fields: List<List<String?>>)

internal fun SqliteSession.schemaScope(): SchemaScope = SchemaScope(
    queryTexts("SELECT name FROM sqlite_master WHERE type='table' AND name NOT LIKE 'sqlite_%' ORDER BY name"),
    queryTexts("SELECT name FROM sqlite_master WHERE type='index' AND name NOT LIKE 'sqlite_autoindex%' ORDER BY name"),
    queryTexts("SELECT name FROM sqlite_master WHERE type='trigger' ORDER BY name"),
)

/** Matches desktop canonical formatting and uses the frozen migration-managed scope only. */
internal class SchemaFingerprint(private val session: SqliteSession) {
    fun compute(scope: SchemaScope): CanonicalSchema {
        val text = StringBuilder()
        val parts = mutableMapOf<Pair<String, String>, SchemaPart>()
        for (table in scope.tables) appendTable(text, parts, table, scope)
        appendMissing(text, "index", "missing_idx", scope.indexes)
        appendMissing(text, "trigger", "missing_trg", scope.triggers)
        return CanonicalSchema(text.toString(), parts)
    }

    private fun appendTable(
        text: StringBuilder, parts: MutableMap<Pair<String, String>, SchemaPart>, table: String, scope: SchemaScope,
    ) {
        text.append("table:").append(table).append('\n')
        if (!exists("table", table)) {
            text.append("missing_table:").append(table).append('\n')
            return
        }
        val escaped = table.replace("'", "''")
        val columns = session.queryAll("PRAGMA table_info('$escaped')") { column ->
            val fields = columnFields(column)
            text.append("col:").append(fields.joinToString(":")) .append('\n')
            fields
        }
        parts["table" to table] = SchemaPart(null, columns)
        appendObjects(text, parts, table, "index", "idx", scope.indexes.toSet())
        appendObjects(text, parts, table, "trigger", "trg", scope.triggers.toSet())
    }

    private fun columnFields(row: SQLiteStatement): List<String?> = listOf(
        row.getLong(0).toString(), row.getText(1), if (row.isNull(2)) "" else row.getText(2),
        row.getLong(3).toString(), if (row.isNull(4)) "" else row.getText(4), row.getLong(5).toString(),
    )

    private fun appendObjects(
        text: StringBuilder, parts: MutableMap<Pair<String, String>, SchemaPart>, table: String,
        type: String, marker: String, allowed: Set<String>,
    ) {
        session.queryAll(
            "SELECT name, IFNULL(sql,'') FROM sqlite_master WHERE type=? AND tbl_name=? ORDER BY name",
            { it.bindText(1, type); it.bindText(2, table) },
        ) { row ->
            val name = row.getText(0)
            if (name in allowed) {
                val sql = row.getText(1)
                text.append(marker).append(':').append(name).append(':').append(sql).append('\n')
                parts[type to name] = SchemaPart(table, listOf(listOf(sql)))
            }
        }
    }

    private fun appendMissing(text: StringBuilder, type: String, marker: String, names: List<String>) {
        for (name in names) if (!exists(type, name)) text.append(marker).append(':').append(name).append('\n')
    }

    private fun exists(type: String, name: String): Boolean = session.queryLong(
        "SELECT EXISTS(SELECT 1 FROM sqlite_master WHERE type=? AND name=?)",
        { it.bindText(1, type); it.bindText(2, name) },
    ) == 1L
}

/** Only return a name from the trusted frozen scope; never parse untrusted sqlite_master SQL. */
internal fun firstSchemaDifference(expected: CanonicalSchema, actual: CanonicalSchema, scope: SchemaScope): Pair<String, String>? {
    val allowed = scope.tables.map { "table" to it } + scope.indexes.map { "index" to it } +
        scope.triggers.map { "trigger" to it }
    return allowed.firstOrNull { expected.parts[it] != actual.parts[it] }
}
