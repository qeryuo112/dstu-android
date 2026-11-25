package com.deepstudent.services.sync.data.apply

import com.deepstudent.core.common.json.JsonValue
import com.deepstudent.platform.database.SqlOperations
import com.deepstudent.platform.database.SqlValue

/** Desktop-compatible lossless storage of fields not known to the current migration. */
internal fun SqlOperations.incomingVault(table: String, id: String, fields: Map<String, JsonValue>) {
    if (fields.isEmpty()) return
    execute(
        "CREATE TABLE IF NOT EXISTS __sync_unknown_fields(table_name TEXT NOT NULL,record_id TEXT NOT NULL," +
            "fields_json TEXT NOT NULL,updated_at TEXT NOT NULL DEFAULT (datetime('now')),PRIMARY KEY(table_name,record_id))",
    )
    val args = listOf(SqlValue.Text(table), SqlValue.Text(id))
    val previous = query("SELECT fields_json FROM __sync_unknown_fields WHERE table_name=? AND record_id=?", args) { it.text(0) }
        .singleOrNull()?.let { JsonValue.parseObject(it).asMap() }.orEmpty()
    val merged = previous.toMutableMap()
    val deltas = (fields["__sync_field_deltas"] as? JsonValue.Object)?.asMap().orEmpty()
    for ((key, value) in fields.filterKeys { it != "__sync_field_deltas" }) {
        val next = incomingMergeUnknown(value, deltas[key] as? JsonValue.Object, previous[key])
        if (next != null) merged[key] = next
    }
    if (deltas.isNotEmpty()) {
        val old = (previous["__sync_field_deltas"] as? JsonValue.Object)?.asMap().orEmpty()
        merged["__sync_field_deltas"] = JsonValue.Object.from(old + deltas.filterKeys { it !in fields })
    }
    execute(
        "INSERT INTO __sync_unknown_fields(table_name,record_id,fields_json) VALUES(?,?,?) " +
            "ON CONFLICT(table_name,record_id) DO UPDATE SET fields_json=excluded.fields_json,updated_at=datetime('now')",
        args + SqlValue.Text(JsonValue.Object.from(merged).literal()),
    )
}

private fun incomingMergeUnknown(value: JsonValue, version: JsonValue.Object?, previous: JsonValue?): JsonValue? {
    val hlc = (version?.get("hlc") as? JsonValue.Text)?.value
    val dev = (version?.get("dev") as? JsonValue.Text)?.value
    val prior = previous as? JsonValue.Object
    val priorHlc = (prior?.get("hlc") as? JsonValue.Text)?.value
    val priorDev = (prior?.get("dev") as? JsonValue.Text)?.value
    val priorVersioned = priorHlc != null && priorDev != null && prior.containsKey("value")
    if (hlc == null || dev == null) return if (priorVersioned) null else value
    if (priorVersioned && incomingCompare(hlc, dev, "", priorHlc!!, priorDev!!, "") <= 0) return null
    return JsonValue.Object.from(version!!.asMap() + ("value" to value))
}
