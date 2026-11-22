package com.deepstudent.services.sync.data.transfer

import com.deepstudent.core.common.json.JsonValue
import com.deepstudent.platform.database.SqlDatabase
import com.deepstudent.platform.database.SqlValue
import com.deepstudent.services.sync.backend.transfer.PublicationJournal
import java.security.MessageDigest
import java.time.Instant
import java.util.UUID

class SqlSyncManifest(
    private val database: SqlDatabase,
    private val instanceId: String,
    private val deviceId: String,
    private val journal: PublicationJournal,
    private val tables: Map<String, String>,
) {
    fun snapshot(): JsonValue.Object {
        val schema = database.query("SELECT COALESCE(MAX(version),0) FROM refinery_schema_history") { it.long(0) }.single()
        val version = database.query("SELECT COALESCE(MAX(sync_version),0) FROM __change_log") { maxOf(0, it.long(0)) }.single()
        val updated = database.query("SELECT MAX(changed_at) FROM __change_log") {
            if (it.isNull(0)) JsonValue.Null else JsonValue.text(it.text(0))
        }.single()
        val state = JsonValue.Object.Builder().put("schema_version", schema).put("data_version", version)
            .put("checksum", checksum()).put("last_updated_at", updated).build()
        return JsonValue.Object.Builder().put("sync_transaction_id", UUID.randomUUID().toString())
            .put("databases", JsonValue.obj("vfs" to state)).put("status", "Complete")
            .put("created_at", Instant.now().toString()).put("device_id", deviceId).put("format_version", 4)
            .put("published_max_seq", journal.published()).put("cursors", cursors())
            .put("snapshot_seen", JsonValue.Object.EMPTY).build()
    }

    private fun cursors(): JsonValue.Object {
        val exists = database.query("SELECT 1 FROM sqlite_master WHERE type='table' AND name='__android_sync_cursor_v2'") { it.long(0) }.isNotEmpty()
        if (!exists) return JsonValue.Object.EMPTY
        val rows = database.query("SELECT device,seq FROM __android_sync_cursor_v2 WHERE instance=?", listOf(SqlValue.Text(instanceId))) {
            it.text(0) to JsonValue.integer(it.long(1))
        }
        return JsonValue.Object.from(rows.toMap())
    }

    private fun checksum(): String {
        val digest = MessageDigest.getInstance("SHA-256")
        fun append(text: String) { digest.update(text.toByteArray()) }
        append("vfs\n")
        for ((table, primaryKey) in tables.toSortedMap()) {
            if (table == "note_document_revisions") continue
            val columns = columns(table)
            append("table:$table\ncolumns:${columns.joinToString(",")}\n")
            val expression = columns.joinToString(",") { "quote(${quote(it)})" }
            val rows = database.query("SELECT $expression FROM ${quote(table)} ORDER BY ${quote(primaryKey)}") { row ->
                columns.indices.joinToString("", prefix = "row", postfix = "\n") { "\u001f" + row.text(it) }
            }
            rows.forEach(::append)
        }
        return digest.digest().take(16).joinToString("") { "%02x".format(it.toInt() and 255) }
    }

    private fun columns(table: String): List<String> = database.query(
        "SELECT name FROM pragma_table_info(?) ORDER BY cid", listOf(SqlValue.Text(table)),
    ) { it.text(0) }.filterNot { table == "note_learning_relations" && it == "revision" }

    private fun quote(value: String): String {
        require(Regex("[A-Za-z_][A-Za-z0-9_]*").matches(value))
        return "\"$value\""
    }
}
