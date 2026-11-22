package com.deepstudent.features.notes.data

import com.deepstudent.core.common.json.JsonValue
import com.deepstudent.platform.database.SqlReadOperations

internal fun SqlReadOperations.syncDependencies(changes: List<NoteSyncChange>, checkCancelled: () -> Unit): List<NoteSyncChange> {
    val result = changes.toMutableList()
    val seen = changes.map { it.table to it.id }.toMutableSet()
    var index = 0
    while (index < result.size) {
        checkCancelled()
        val change = result[index++]
        if (change.operation == "DELETE") continue
        for ((table, id) in syncReferences(change)) {
            if (!seen.add(table to id)) continue
            val data = syncRead(table, id) ?: error("missing dependency")
            result.add(change.copy(table = table, id = id, operation = "INSERT", data = data, logId = null))
        }
    }
    return result
}

private fun SqlReadOperations.syncReferences(change: NoteSyncChange): List<Pair<String, String>> {
    val data = change.data as? JsonValue.Object ?: return emptyList()
    val result = mutableListOf<Pair<String, String>>()
    fun field(table: String, name: String) {
        (data[name] as? JsonValue.Text)?.value?.takeIf { it.isNotBlank() }?.let { result.add(table to it) }
    }
    val folderColumn = mapOf("folder_items" to "folder_id", "folders" to "parent_id")[change.table]
    folderColumn?.let { field("folders", it) }
    when (change.table) {
        "notes" -> {
            field("resources", "resource_id")
            result += syncChildren("note_document_formats", "note_id", "note_id", change.id)
            result += syncChildren("note_document_revisions", "version_id", "note_id", change.id)
            result += syncChildren("note_learning_relations", "id", "note_id", change.id)
            result += syncChildren("folder_items", "id", "item_id", change.id, " AND item_type='note'")
        }
        "resources" -> {
            field("blobs", "external_hash")
            result += syncChildren("files", "id", "resource_id", change.id)
        }
        "files" -> {
            field("resources", "resource_id")
            field("blobs", "blob_hash")
        }
        "note_learning_relations" -> field("resources", "resource_id")
    }
    return result
}

private fun SqlReadOperations.syncChildren(table: String, key: String, column: String, value: String, extra: String = ""): List<Pair<String, String>> =
    query("SELECT ${syncQuote(key)} FROM ${syncQuote(table)} WHERE ${syncQuote(column)}=?$extra ORDER BY ${syncQuote(key)}", listOf(text(value))) {
        table to it.text(0)
    }
