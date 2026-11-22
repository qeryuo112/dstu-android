package com.deepstudent.services.sync.data.changelog

import com.deepstudent.platform.database.SqlReadOperations
import com.deepstudent.platform.database.SqlValue

internal data class TableMetadata(val columns: List<String>, val keys: List<String>)

/** Registry membership is checked by the reader before using any dynamic table identifier. */
internal fun tableMetadata(sql: SqlReadOperations, table: String, keys: List<String>): TableMetadata {
    require(table.isNotBlank() && !table.startsWith("__") && !table.startsWith("sqlite_") && '\u0000' !in table)
    val rows = sql.query("SELECT name FROM pragma_table_info(?) ORDER BY cid", listOf(SqlValue.Text(table))) { it.text(0) }
    require(rows.isNotEmpty()) { "registered sync table is absent" }
    val columns = rows.filterNot { isLocalDerived(table, it) }
    require(keys.isNotEmpty() && keys.distinct().size == keys.size && keys.all { it in columns }) {
        "registered sync table has no usable key"
    }
    return TableMetadata(columns, keys.toList())
}

internal fun quoteIdentifier(value: String): String {
    require(value.isNotBlank() && '\u0000' !in value)
    return "\"${value.replace("\"", "\"\"")}\""
}

/** Frozen isLocalDerivedSyncColumn: these fields are owned by each local device. */
internal fun isLocalDerived(table: String, column: String): Boolean = when (table) {
    "note_document_revisions" -> column in setOf("seq", "edit_bucket")
    "note_learning_relations" -> column == "revision"
    "resources" -> column in resourceDerived
    "files", "exam_sheets" -> column in fileDerived
    "chat_v2_session_groups" -> column == "preferred_project_root_path"
    else -> false
}

private val resourceDerived = setOf("index_state", "index_hash", "index_error", "indexed_at", "index_retry_count",
    "index_next_retry_at", "index_generation", "mm_index_state", "mm_index_error", "mm_index_retry_count",
    "mm_index_next_retry_at", "mm_embedding_dim", "mm_indexing_mode", "mm_indexed_at", "mm_index_generation")
private val fileDerived = setOf("mm_index_state", "mm_index_error", "mm_indexed_pages_json", "mm_embedding_dim",
    "mm_indexing_mode", "mm_indexed_at")
