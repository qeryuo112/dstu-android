package com.deepstudent.features.notes.data

import com.deepstudent.core.common.json.JsonValue
import com.deepstudent.platform.database.SqlReadOperations
import com.deepstudent.platform.database.SqlRow
import java.util.Base64

internal object NoteSyncRows {
    fun read(tx: SqlReadOperations, table: String, key: String, id: String): JsonValue.Object? {
        require(table in tables && key in keys)
        val columns = tx.query("SELECT name FROM pragma_table_info(?) ORDER BY cid", listOf(text(table))) { it.text(0) }
            .filterNot { derived(table, it) }
        require(key in columns)
        val values = columns.joinToString(",") { quote(it) }
        val types = columns.joinToString(",") { "typeof(${quote(it)})" }
        return tx.query(
            "SELECT $values,$types FROM ${quote(table)} WHERE ${quote(key)}=?",
            listOf(text(id)),
        ) { row ->
            JsonValue.Object.from(columns.mapIndexed { index, column ->
                column to value(row, index, index + columns.size)
            }.toMap())
        }.singleOrNull()
    }

    private fun value(row: SqlRow, index: Int, type: Int): JsonValue = when (row.text(type)) {
        "null" -> JsonValue.Null
        "integer" -> JsonValue.integer(row.long(index))
        "real" -> JsonValue.Number.raw(row.double(index).toString())
        "text" -> JsonValue.text(row.text(index))
        "blob" -> JsonValue.obj("\$dsblob" to JsonValue.text(Base64.getEncoder().encodeToString(row.blob(index))))
        else -> error("Unsupported SQLite storage type")
    }

    fun derived(table: String, column: String): Boolean = when (table) {
        "note_document_revisions" -> column in setOf("seq", "edit_bucket")
        "note_learning_relations" -> column == "revision"
        "resources" -> column in resourceDerived
        "files" -> column in fileDerived
        else -> false
    }

    private fun quote(value: String): String = "\"${value.replace("\"", "\"\"")}\""

    val tables = setOf("resources", "notes", "folders", "folder_items", "files", "blobs", "note_document_formats",
        "note_document_revisions", "note_learning_relations")
    private val keys = setOf("id", "version_id", "note_id", "hash")
    private val resourceDerived = setOf("index_state", "index_hash", "index_error", "indexed_at", "index_retry_count",
        "index_next_retry_at", "index_generation", "mm_index_state", "mm_index_error", "mm_index_retry_count",
        "mm_index_next_retry_at", "mm_embedding_dim", "mm_indexing_mode", "mm_indexed_at", "mm_index_generation")
    private val fileDerived = setOf("mm_index_state", "mm_index_error", "mm_indexed_pages_json", "mm_embedding_dim",
        "mm_indexing_mode", "mm_indexed_at")
}
