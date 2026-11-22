package com.deepstudent.services.sync.data.changelog

import com.deepstudent.core.common.json.JsonValue
import com.deepstudent.core.common.error.DstuException

/** Mirrors frozen desktop composite object, array, colon and llm_usage_daily legacy keys. */
internal fun parseRecordKey(table: String, recordId: String, columns: List<String>): List<String> {
    require(columns.isNotEmpty())
    if (columns.size == 1) return listOf(recordId)
    if (table == "llm_usage_daily") parseDaily(recordId)?.let { return it }
    val parsed = try { JsonValue.parse(recordId) } catch (_: DstuException) { null }
    when (parsed) {
        is JsonValue.Object -> return columns.map { name -> keyValue(requireNotNull(parsed[name]) { "missing composite key" }) }
        is JsonValue.Array -> if (parsed.values.size == columns.size) return parsed.values.map(::keyValue)
        else -> Unit
    }
    val split = recordId.split(':')
    require(split.size == columns.size) { "invalid composite record key" }
    return split
}

private fun parseDaily(recordId: String): List<String>? {
    val parsed = try { JsonValue.parse(recordId) } catch (_: DstuException) { null }
    if (parsed is JsonValue.Object) {
        val fields = listOf("date", "caller_type", "model", "provider").map { (parsed[it] as? JsonValue.Text)?.value.orEmpty() }
        if (fields.all { it.isNotEmpty() }) return fields
    }
    val parts = recordId.split('_')
    return if (parts.size >= 4) parts.take(3) + parts.drop(3).joinToString("_") else null
}

private fun keyValue(value: JsonValue): String = when (value) {
    is JsonValue.Text -> value.value
    is JsonValue.Number -> value.literal
    is JsonValue.Bool -> value.value.toString()
    else -> value.literal()
}
