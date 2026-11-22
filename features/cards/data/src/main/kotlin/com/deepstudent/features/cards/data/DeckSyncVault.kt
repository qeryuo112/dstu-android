package com.deepstudent.features.cards.data

import com.deepstudent.core.common.json.JsonValue
import com.deepstudent.platform.database.SqlOperations

private const val DELTAS = "__sync_field_deltas"

private fun SqlOperations.deckStoredUnknown(id: String): Map<String, JsonValue> {
    val json = query("SELECT fields_json FROM __sync_unknown_fields WHERE table_name='anki_decks' AND record_id=?", listOf(text(id))) { it.text(0) }.singleOrNull()
        ?: return emptyMap()
    return JsonValue.parseObject(json).asMap()
}

internal fun SqlOperations.deckUnknownValues(id: String): Map<String, JsonValue> =
    deckStoredUnknown(id).mapValues { (_, value) -> if (deckEnvelope(value)) (value as JsonValue.Object)["value"]!! else value }

internal fun SqlOperations.deckVault(id: String, incoming: JsonValue.Object) {
    val fields = incoming.asMap().filterKeys { it !in deckColumns && it != DELTAS }
    val rawDeltas = (incoming[DELTAS] as? JsonValue.Object)?.asMap().orEmpty().filterKeys { it !in deckColumns }
    if (fields.isEmpty() && rawDeltas.isEmpty()) return
    val old = deckStoredUnknown(id)
    val merged = old.toMutableMap()
    val previousDeltas = (old[DELTAS] as? JsonValue.Object)?.asMap().orEmpty().toMutableMap()
    deckMergeVaultFields(fields, rawDeltas, old, merged, previousDeltas)
    rawDeltas.filterKeys { it !in fields && old[it] == null }.forEach { (key, delta) -> previousDeltas[key] = delta }
    if (previousDeltas.isEmpty()) merged.remove(DELTAS) else merged[DELTAS] = JsonValue.Object.from(previousDeltas)
    if (old == merged) return
    execute("INSERT INTO __sync_unknown_fields(table_name,record_id,fields_json) VALUES('anki_decks',?,?) ON CONFLICT(table_name,record_id) DO UPDATE SET fields_json=excluded.fields_json,updated_at=datetime('now')", listOf(text(id), text(JsonValue.Object.from(merged).literal())))
}

private fun deckMergeVaultFields(
    fields: Map<String, JsonValue>, rawDeltas: Map<String, JsonValue>, old: Map<String, JsonValue>,
    merged: MutableMap<String, JsonValue>, previousDeltas: MutableMap<String, JsonValue>,
) {
    for ((key, value) in fields) {
        val version = rawDeltas[key] ?: previousDeltas[key]
        val next = deckMergeUnknown(value, version as? JsonValue.Object, old[key]) ?: continue
        merged[key] = next
        if (version is JsonValue.Object && deckEnvelope(next)) previousDeltas.remove(key)
    }
}

private fun deckMergeUnknown(value: JsonValue, version: JsonValue.Object?, previous: JsonValue?): JsonValue? {
    val hlc = (version?.get("hlc") as? JsonValue.Text)?.value
    val device = (version?.get("dev") as? JsonValue.Text)?.value
    if (hlc == null || device == null) return if (deckEnvelope(previous)) null else value
    if (deckEnvelope(previous)) {
        val prior = previous as JsonValue.Object
        if (deckCompare(hlc, device, "", (prior["hlc"] as JsonValue.Text).value, (prior["dev"] as JsonValue.Text).value, "") <= 0) return null
    }
    return JsonValue.Object.from(version!!.asMap() + ("value" to value))
}

private fun deckEnvelope(value: JsonValue?): Boolean = value is JsonValue.Object &&
    value["hlc"] is JsonValue.Text && value["dev"] is JsonValue.Text && value.containsKey("value")
