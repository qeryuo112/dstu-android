package com.deepstudent.features.cards.data

import com.deepstudent.core.common.json.JsonValue

internal data class IncomingDeckChange(
    val id: String,
    val operation: String,
    val changedAt: String,
    val data: JsonValue.Object?,
    val source: String,
    val seq: ULong,
) {
    val version: String get() = (data?.get("updated_at") as? JsonValue.Text)?.value ?: changedAt

    companion object {
        fun parse(bytes: ByteArray): IncomingDeckChange {
            val root = JsonValue.parseObject(bytes.decodeToString(throwOnInvalidSequence = true))
            require(root.text("database_name") == "mistakes" && root.text("table_name") == "anki_decks")
            val id = root.text("record_id")
            require(id.isNotBlank() && '\u0000' !in id)
            val operation = root.text("operation").uppercase()
            require(operation in setOf("INSERT", "UPDATE", "DELETE"))
            val changedAt = root.text("changed_at")
            require(changedAt.isNotBlank())
            val source = root.optionalText("source_device_id") ?: ""
            val seq = root.optionalUnsigned("source_seq")
            root.optionalSigned("change_log_id")
            val suppress = root["suppress_change_log"]
            require(suppress == null || suppress == JsonValue.Null || suppress is JsonValue.Bool)
            val data = root["data"]
            if (operation == "DELETE") require(data == null || data == JsonValue.Null)
            else validateDeckData(id, data as? JsonValue.Object ?: error("invalid"))
            return IncomingDeckChange(id, operation, changedAt, data as? JsonValue.Object, source, seq)
        }
    }
}

private fun JsonValue.Object.text(key: String): String = (this[key] as? JsonValue.Text)?.value ?: error("invalid")

private fun JsonValue.Object.optionalText(key: String): String? = when (val value = this[key]) {
    null, JsonValue.Null -> null
    is JsonValue.Text -> value.value
    else -> error("invalid")
}

private fun JsonValue.Object.optionalUnsigned(key: String): ULong = when (val value = this[key]) {
    null, JsonValue.Null -> 0uL
    is JsonValue.Number -> value.literal.toULongOrNull() ?: error("invalid")
    else -> error("invalid")
}

private fun JsonValue.Object.optionalSigned(key: String): Long? = when (val value = this[key]) {
    null, JsonValue.Null -> null
    is JsonValue.Number -> value.asLongOrNull() ?: error("invalid")
    else -> error("invalid")
}

internal val deckColumns = listOf("id", "name", "description", "config_json", "created_at", "updated_at", "deleted_at", "device_id", "local_version")
private val requiredText = setOf("id", "name", "created_at", "updated_at")

private fun validateDeckData(id: String, data: JsonValue.Object) {
    require((data["id"] as? JsonValue.Text)?.value == id)
    for ((key, value) in data.asMap()) {
        if (key in deckColumns) validateDeckColumn(key, value)
    }
    val deltas = data["__sync_field_deltas"]
    require(deltas == null || deltas == JsonValue.Null || deltas is JsonValue.Object)
}

private fun validateDeckColumn(key: String, value: JsonValue) {
    if (key == "local_version") {
        require(value == JsonValue.Null || (value is JsonValue.Number && value.asLongOrNull() != null))
    } else {
        require(value is JsonValue.Text || (value == JsonValue.Null && key !in requiredText))
    }
    if (key in requiredText) require((value as JsonValue.Text).value.isNotBlank())
}
