package com.deepstudent.features.notes.data

import com.deepstudent.core.common.json.JsonValue
import com.deepstudent.platform.database.SqlOperations
import com.deepstudent.platform.database.SqlReadOperations
import com.deepstudent.platform.database.SqlValue
import java.util.Base64

internal fun SqlOperations.syncSetup() {
    execute("CREATE TABLE IF NOT EXISTS __sync_unknown_fields (table_name TEXT NOT NULL,record_id TEXT NOT NULL,fields_json TEXT NOT NULL,updated_at TEXT NOT NULL DEFAULT (datetime('now')),PRIMARY KEY(table_name,record_id))")
    execute("CREATE TABLE IF NOT EXISTS __android_sync_cursor_v2 (instance TEXT NOT NULL,device TEXT NOT NULL,seq INTEGER NOT NULL,PRIMARY KEY(instance,device))")
    execute("CREATE TABLE IF NOT EXISTS __android_sync_versions_v2 (instance TEXT NOT NULL,table_name TEXT NOT NULL,record_id TEXT NOT NULL,changed_at TEXT NOT NULL,device TEXT NOT NULL,seq INTEGER NOT NULL,deleted INTEGER NOT NULL,PRIMARY KEY(instance,table_name,record_id))")
}

internal fun SqlOperations.syncColumns(table: String): List<String> =
    query("SELECT name FROM pragma_table_info(?) ORDER BY cid", listOf(text(table))) { it.text(0) }

internal fun syncQuote(value: String): String = "\"${value.replace("\"", "\"\"")}\""

internal fun syncSqlValue(value: JsonValue): SqlValue = when (value) {
    JsonValue.Null -> SqlValue.Null
    is JsonValue.Bool -> SqlValue.Integer(if (value.value) 1 else 0)
    is JsonValue.Number -> syncNumberValue(value)
    is JsonValue.Text -> SqlValue.Text(value.value)
    is JsonValue.Object -> syncObjectValue(value)
    is JsonValue.Array -> SqlValue.Text(value.literal())
}

private fun syncNumberValue(value: JsonValue.Number): SqlValue =
    if (value.isIntegerLiteral()) SqlValue.Integer(value.asLongOrNull() ?: error("integer overflow"))
    else SqlValue.Real(value.literal.toDouble())

private fun syncObjectValue(value: JsonValue.Object): SqlValue {
    val blob = value["\$dsblob"] as? JsonValue.Text
    return if (value.size == 1 && blob != null) SqlValue.Blob(Base64.getDecoder().decode(blob.value))
    else SqlValue.Text(value.literal())
}

internal fun SqlReadOperations.syncUnknown(table: String, id: String): Map<String, JsonValue> {
    val json = query("SELECT fields_json FROM __sync_unknown_fields WHERE table_name=? AND record_id=?",
        listOf(text(table), text(id))) { it.text(0) }.singleOrNull() ?: return emptyMap()
    val stored = JsonValue.parseObject(json).asMap().filterKeys { !NoteSyncRows.derived(table, it) }
    val values = stored.mapValues { (_, value) -> if (syncEnvelope(value)) (value as JsonValue.Object)["value"]!! else value }.toMutableMap()
    val deltas = (values["__sync_field_deltas"] as? JsonValue.Object)?.asMap().orEmpty()
        .filterKeys { !NoteSyncRows.derived(table, it) }.toMutableMap()
    if (values["__sync_field_deltas"] is JsonValue.Object) values["__sync_field_deltas"] = JsonValue.Object.from(deltas)
    for ((key, value) in stored) {
        if (syncEnvelope(value)) deltas[key] = JsonValue.Object.from((value as JsonValue.Object).asMap().filterKeys { it != "value" })
    }
    if (deltas.isNotEmpty()) values["__sync_field_deltas"] = JsonValue.Object.from(deltas)
    return values
}

private fun syncEnvelope(value: JsonValue?): Boolean = value is JsonValue.Object &&
    value["hlc"] is JsonValue.Text && value["dev"] is JsonValue.Text && value.containsKey("value")

private fun syncMergeUnknown(value: JsonValue, version: JsonValue.Object?, previous: JsonValue?): JsonValue? {
    val hlc = (version?.get("hlc") as? JsonValue.Text)?.value
    val dev = (version?.get("dev") as? JsonValue.Text)?.value
    if (hlc == null || dev == null) return if (syncEnvelope(previous)) null else value
    if (syncEnvelope(previous)) {
        val prior = previous as JsonValue.Object
        if (syncCompare(hlc, dev, "", (prior["hlc"] as JsonValue.Text).value, (prior["dev"] as JsonValue.Text).value, "") <= 0) return null
    }
    return JsonValue.Object.from(version!!.asMap() + ("value" to value))
}

internal fun SqlOperations.syncVault(table: String, id: String, fields: Map<String, JsonValue>) {
    if (fields.isEmpty()) return
    val old = query("SELECT fields_json FROM __sync_unknown_fields WHERE table_name=? AND record_id=?",
        listOf(text(table), text(id))) { it.text(0) }.singleOrNull()?.let { JsonValue.parseObject(it).asMap() }.orEmpty()
    val merged = old.toMutableMap()
    val deltas = (fields["__sync_field_deltas"] as? JsonValue.Object)?.asMap().orEmpty()
    for ((key, value) in fields.filterKeys { it != "__sync_field_deltas" }) {
        val mergedValue = syncMergeUnknown(value, deltas[key] as? JsonValue.Object, old[key])
        if (mergedValue != null) merged[key] = mergedValue
    }
    if (deltas.isNotEmpty()) {
        val prior = (old["__sync_field_deltas"] as? JsonValue.Object)?.asMap().orEmpty()
        merged["__sync_field_deltas"] = JsonValue.Object.from(prior + deltas.filterKeys { it !in fields })
    }
    execute("INSERT INTO __sync_unknown_fields(table_name,record_id,fields_json) VALUES(?,?,?) ON CONFLICT(table_name,record_id) DO UPDATE SET fields_json=excluded.fields_json,updated_at=datetime('now')",
        listOf(text(table), text(id), text(JsonValue.Object.from(merged).literal())))
}
