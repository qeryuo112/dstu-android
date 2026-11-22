package com.deepstudent.services.sync.data.changelog

import com.deepstudent.core.common.json.JsonValue
import com.deepstudent.platform.database.SqlReadOperations
import com.deepstudent.platform.database.SqlRow
import com.deepstudent.platform.database.SqlValue
import java.util.Base64
import java.util.Locale

/** Maps the current row, including opaque fields, with the frozen desktop SQLite conventions. */
internal class ChangeRowImages(private val sql: SqlReadOperations, private val preserveVaultClocks: Boolean) {
    private val metadata = mutableMapOf<String, TableMetadata>()
    private val hasVault = sql.query("SELECT 1 FROM sqlite_master WHERE type='table' AND name='__sync_unknown_fields'") { true }
        .isNotEmpty()

    fun encode(databaseName: String, entry: ChangeLogEntry, keys: List<String>): ByteArray {
        val operation = when (entry.operation.uppercase(Locale.ROOT)) {
            "INSERT" -> "Insert"
            "DELETE" -> "Delete"
            else -> "Update" // Same fallback as frozen changeOperationFromStr.
        }
        val meta = metadata.getOrPut(entry.table) { tableMetadata(sql, entry.table, keys) }
        require(meta.keys == keys) { "registry key changed during snapshot" }
        // Desktop parses the log field even on DELETE or a missing current row; malformed JSON must surface.
        val deltas = entry.fieldDeltas?.let(JsonValue::parse)
        val data = if (operation == "Delete") null else read(entry.table, entry.recordId, meta)
        val withDeltas = if (data == null || deltas == null || deltas == JsonValue.Null) data else mergeDeltas(data, entry.table, deltas)
        return JsonValue.Object.Builder().put("table_name", entry.table).put("record_id", entry.recordId)
            .put("operation", operation).put("data", withDeltas ?: JsonValue.Null).put("changed_at", entry.changedAt)
            .put("change_log_id", entry.id).put("database_name", databaseName).build().literal().toByteArray(Charsets.UTF_8)
    }

    private fun read(table: String, recordId: String, meta: TableMetadata): JsonValue.Object? {
        val keyValues = parseRecordKey(table, recordId, meta.keys)
        val predicate = meta.keys.joinToString(" AND ") { "${quoteIdentifier(it)}=?" }
        var rows = select(meta, table, predicate, keyValues)
        require(rows.size <= 1) { "registered logical key is not unique" }
        if (rows.isEmpty() && table == "questions" && "exam_id" in meta.columns) {
            rows = select(meta, table, "\"exam_id\"=?", listOf(recordId))
        }
        val row = rows.singleOrNull() ?: return null
        return JsonValue.Object.from(unknown(table, recordId) + row.asMap())
    }

    private fun select(meta: TableMetadata, table: String, where: String, keys: List<String>): List<JsonValue.Object> {
        val names = meta.columns.joinToString(",") { quoteIdentifier(it) }
        val types = meta.columns.joinToString(",") { "typeof(${quoteIdentifier(it)})" }
        return sql.query("SELECT $names,$types FROM ${quoteIdentifier(table)} WHERE $where LIMIT 2", keys.map(SqlValue::Text)) { row ->
            JsonValue.Object.from(meta.columns.mapIndexed { i, column -> column to value(row, i, i + meta.columns.size) }.toMap())
        }
    }

    private fun unknown(table: String, id: String): Map<String, JsonValue> {
        if (!hasVault) return emptyMap()
        val raw = sql.query("SELECT fields_json FROM __sync_unknown_fields WHERE table_name=? AND record_id=?",
            listOf(SqlValue.Text(table), SqlValue.Text(id))) { it.text(0) }.singleOrNull() ?: return emptyMap()
        val stored = (JsonValue.parse(raw) as? JsonValue.Object)?.asMap()?.filterKeys { !isLocalDerived(table, it) }
            ?: return emptyMap()
        return vaultValues(table, stored, preserveVaultClocks)
    }
}

private fun vaultValues(table: String, stored: Map<String, JsonValue>, preserveClocks: Boolean): Map<String, JsonValue> {
    val values = stored.mapValues { (_, value) -> if (isEnvelope(value)) (value as JsonValue.Object)["value"]!! else value }.toMutableMap()
    val prior = values["__sync_field_deltas"] as? JsonValue.Object
    val clocks = prior?.asMap().orEmpty().filterKeys { !isLocalDerived(table, it) }.toMutableMap()
    if (preserveClocks) {
        for ((key, value) in stored.filterValues(::isEnvelope)) {
            clocks[key] = JsonValue.Object.from((value as JsonValue.Object).asMap().filterKeys { it != "value" })
        }
    }
    if (prior != null || clocks.isNotEmpty()) values["__sync_field_deltas"] = JsonValue.Object.from(clocks)
    return values
}

private fun isEnvelope(value: JsonValue): Boolean = value is JsonValue.Object && value.containsKey("value") &&
    value["hlc"] is JsonValue.Text && value["dev"] is JsonValue.Text

private fun mergeDeltas(data: JsonValue.Object, table: String, raw: JsonValue): JsonValue.Object {
    val incoming = if (raw is JsonValue.Object) JsonValue.Object.from(raw.asMap().filterKeys { !isLocalDerived(table, it) }) else raw
    val previous = data["__sync_field_deltas"]
    val merged = if (previous is JsonValue.Object && incoming is JsonValue.Object) {
        JsonValue.Object.from(previous.asMap() + incoming.asMap())
    } else incoming
    return JsonValue.Object.from(data.asMap() + ("__sync_field_deltas" to merged))
}

private fun value(row: SqlRow, index: Int, type: Int): JsonValue = when (row.text(type)) {
    "null" -> JsonValue.Null
    "integer" -> JsonValue.integer(row.long(index))
    "real" -> row.double(index).let { if (it.isFinite()) JsonValue.Number.raw(it.toString()) else JsonValue.Null }
    "text" -> JsonValue.text(row.text(index))
    "blob" -> JsonValue.obj("\$dsblob" to JsonValue.text(Base64.getEncoder().encodeToString(row.blob(index))))
    else -> error("unsupported SQLite storage type")
}
