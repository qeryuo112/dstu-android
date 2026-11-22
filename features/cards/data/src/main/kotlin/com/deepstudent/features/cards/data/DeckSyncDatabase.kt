package com.deepstudent.features.cards.data

import com.deepstudent.core.common.json.JsonValue
import com.deepstudent.platform.database.SqlOperations
import com.deepstudent.platform.database.SqlValue

internal fun SqlOperations.deckSetup() {
    execute("CREATE TABLE IF NOT EXISTS __sync_unknown_fields (table_name TEXT NOT NULL,record_id TEXT NOT NULL,fields_json TEXT NOT NULL,updated_at TEXT NOT NULL DEFAULT (datetime('now')),PRIMARY KEY(table_name,record_id))")
    execute("CREATE TABLE IF NOT EXISTS __sync_id_aliases (table_name TEXT NOT NULL,remote_id TEXT NOT NULL,canonical_id TEXT NOT NULL,created_at TEXT NOT NULL DEFAULT (strftime('%Y-%m-%dT%H:%M:%fZ','now')),PRIMARY KEY(table_name,remote_id))")
    execute("CREATE TABLE IF NOT EXISTS __sync_delete_versions (table_name TEXT NOT NULL,record_id TEXT NOT NULL,changed_at TEXT NOT NULL,source_device_id TEXT NOT NULL,source_seq INTEGER NOT NULL DEFAULT 0,PRIMARY KEY(table_name,record_id))")
}

internal fun SqlOperations.deckRow(id: String): JsonValue.Object? = query(
    "SELECT id,name,description,config_json,created_at,updated_at,deleted_at,device_id,local_version FROM anki_decks WHERE id=?",
    listOf(text(id)),
) { row ->
    val fields = deckColumns.mapIndexed { index, key ->
        val value = when {
            row.isNull(index) -> JsonValue.Null
            key == "local_version" -> JsonValue.integer(row.long(index))
            else -> JsonValue.text(row.text(index))
        }
        key to value
    }.toMap()
    JsonValue.Object.from(deckUnknownValues(id) + fields)
}.singleOrNull()

internal fun SqlOperations.deckWrite(id: String, row: JsonValue.Object, local: JsonValue.Object?) {
    val fields = row.asMap().filterKeys { it in deckColumns && it != "id" }
    if (local == null) {
        require(listOf("name", "created_at", "updated_at").all { it in fields })
        val names = listOf("id") + fields.keys
        val sql = "INSERT INTO anki_decks (${names.joinToString(",") { "\"$it\"" }}) VALUES (${names.joinToString(",") { "?" }})"
        execute(sql, listOf(text(id)) + fields.values.map(::deckSqlValue))
    } else {
        val changed = fields.filter { (key, value) -> local[key] != value }
        if (changed.isEmpty()) return
        val assignments = changed.keys.joinToString(",") { "\"$it\"=?" }
        execute("UPDATE anki_decks SET $assignments WHERE id=?", changed.values.map(::deckSqlValue) + text(id))
    }
}

private fun deckSqlValue(value: JsonValue): SqlValue = when (value) {
    JsonValue.Null -> SqlValue.Null
    is JsonValue.Text -> text(value.value)
    is JsonValue.Number -> SqlValue.Integer(value.asLongOrNull() ?: error("invalid"))
    else -> error("invalid")
}
