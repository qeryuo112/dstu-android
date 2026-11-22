package com.deepstudent.features.notes.data

import com.deepstudent.core.common.json.JsonValue
import com.deepstudent.platform.database.SqlReadOperations

internal data class NoteSyncChange(
    val table: String, val id: String, val operation: String, val changedAt: String,
    val data: JsonValue?, val logId: Long? = null, val source: String = "", val seq: Long = 0,
) {
    fun bytes(): ByteArray = JsonValue.Object.Builder().put("table_name", table).put("record_id", id)
        .put("operation", operation).put("data", data ?: JsonValue.Null).put("changed_at", changedAt)
        .putIfNotNull("change_log_id", logId?.let(JsonValue::integer)).put("database_name", "vfs")
        .putIfNotNull("source_device_id", source.takeIf { it.isNotBlank() }?.let(JsonValue::text))
        .putIfNotNull("source_seq", seq.takeIf { it > 0 }?.let(JsonValue::integer))
        .build().literal().toByteArray()

    companion object {
        fun parse(bytes: ByteArray): NoteSyncChange {
            val json = JsonValue.parseObject(bytes.decodeToString(throwOnInvalidSequence = true))
            fun string(name: String) = (json[name] as? JsonValue.Text)?.value ?: error("invalid change")
            val operation = string("operation").uppercase()
            require(operation in setOf("INSERT", "UPDATE", "DELETE"))
            val db = json["database_name"]
            require(db == null || db == JsonValue.Null || db == JsonValue.text("vfs"))
            return NoteSyncChange(string("table_name"), string("record_id"), operation, string("changed_at"),
                json["data"]?.takeUnless { it == JsonValue.Null }, (json["change_log_id"] as? JsonValue.Number)?.asLongOrNull(),
                (json["source_device_id"] as? JsonValue.Text)?.value.orEmpty(),
                (json["source_seq"] as? JsonValue.Number)?.asLongOrNull() ?: 0)
        }
    }
}

internal fun SqlReadOperations.syncRead(table: String, id: String): JsonValue.Object? {
    val row = NoteSyncRows.read(this, table, syncKey(table), id) ?: return null
    return JsonValue.Object.from(syncUnknown(table, id) + row.asMap())
}

internal fun syncKey(table: String): String = when (table) {
    "blobs" -> "hash"
    "note_document_revisions" -> "version_id"
    "note_document_formats" -> "note_id"
    else -> "id"
}
