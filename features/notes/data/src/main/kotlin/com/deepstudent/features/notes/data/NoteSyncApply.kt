package com.deepstudent.features.notes.data

import com.deepstudent.core.common.json.JsonValue
import com.deepstudent.platform.database.SqlOperations
import com.deepstudent.platform.database.SqlValue

internal fun SqlOperations.syncApply(change: NoteSyncChange, instance: String, device: String, seq: Long) {
    val source = change.source.ifBlank { device }
    val sourceSeq = change.seq.takeIf { it > 0 } ?: seq
    val remote = change.data as? JsonValue.Object
    val version = syncTimestamp(remote?.get("updated_at")) ?: change.changedAt
    if (syncIsStale(change, instance, source, sourceSeq, version, remote)) return
    if (change.table == "note_document_revisions" && change.operation == "DELETE") return
    if (change.operation == "DELETE") syncDelete(change) else syncUpsert(change)
    execute("INSERT INTO __android_sync_versions_v2(instance,table_name,record_id,changed_at,device,seq,deleted) VALUES(?,?,?,?,?,?,?) ON CONFLICT(instance,table_name,record_id) DO UPDATE SET changed_at=excluded.changed_at,device=excluded.device,seq=excluded.seq,deleted=excluded.deleted",
        listOf(text(instance), text(change.table), text(change.id), text(version), text(source), SqlValue.Integer(sourceSeq),
            SqlValue.Integer(if (change.operation == "DELETE") 1 else 0)))
}

private fun SqlOperations.syncIsStale(change: NoteSyncChange, instance: String, source: String, seq: Long, version: String, remote: JsonValue.Object?): Boolean {
    val previous = query("SELECT changed_at,device,seq,deleted FROM __android_sync_versions_v2 WHERE instance=? AND table_name=? AND record_id=?",
        listOf(text(instance), text(change.table), text(change.id))) {
        SyncVersion(it.text(0), it.text(1), it.long(2), it.long(3) != 0L)
    }.singleOrNull()
    if (previous != null && previous.deleted && syncDeleteBlocks(previous, version, source, seq)) return true
    val local = syncRead(change.table, change.id) ?: return false
    return change.table != "note_document_revisions" && syncLocalWins(local, remote, change, source)
}

private data class SyncVersion(val time: String, val device: String, val seq: Long, val deleted: Boolean)

private fun syncDeleteBlocks(previous: SyncVersion, version: String, source: String, seq: Long): Boolean {
    val order = syncCompare(version, source, "", previous.time, previous.device, "")
    return order < 0 || (order == 0 && seq <= previous.seq)
}

private fun syncLocalWins(local: JsonValue.Object, remote: JsonValue.Object?, change: NoteSyncChange, source: String): Boolean {
    val localTime = syncTimestamp(local["updated_at"]) ?: return false
    val remoteTime = if (change.operation == "DELETE") change.changedAt else syncTimestamp(remote?.get("updated_at")) ?: return false
    val localDevice = (local["device_id"] as? JsonValue.Text)?.value.orEmpty()
    val remoteDevice = source.ifBlank { (remote?.get("device_id") as? JsonValue.Text)?.value.orEmpty() }
    val devices = if (localDevice.isNotBlank() && remoteDevice.isNotBlank()) localDevice to remoteDevice else "" to ""
    return syncCompare(localTime, devices.first, local.literal(), remoteTime, devices.second, remote?.literal().orEmpty()) > 0
}

private fun SqlOperations.syncDelete(change: NoteSyncChange) {
    val table = syncQuote(change.table)
    val key = syncQuote(syncKey(change.table))
    val columns = syncColumns(change.table)
    val sql = if ("deleted_at" in columns) "UPDATE $table SET deleted_at=? WHERE $key=?" else "DELETE FROM $table WHERE $key=?"
    val timestamp = if (change.table == "resources") SqlValue.Integer(syncMillis(change.changedAt) ?: error("invalid")) else text(change.changedAt)
    val args = if ("deleted_at" in columns) listOf(timestamp, text(change.id)) else listOf(text(change.id))
    execute(sql, args)
}

private fun SqlOperations.syncUpsert(change: NoteSyncChange) {
    val incoming = (change.data as? JsonValue.Object)?.asMap() ?: error("invalid")
    val key = syncKey(change.table)
    require((incoming[key] as? JsonValue.Text)?.value == change.id)
    val columns = syncColumns(change.table).toSet()
    val row = incoming.filterKeys { it in columns && !NoteSyncRows.derived(change.table, it) }.toMutableMap()
    if (change.table == "folder_items") syncFolderAlias(change.id, row)
    val actualId = (row[key] as? JsonValue.Text)?.value ?: change.id
    syncVault(change.table, actualId, incoming.filterKeys { it !in columns })
    if (change.table == "note_document_revisions" && syncRevision(change.id, row)) return
    val local = syncRead(change.table, actualId)
    if (local != null) syncMergeFields(change.table, local, row)
    require(row.isNotEmpty())
    val names = row.keys.map(::syncQuote)
    val assignments = row.keys.filter { it != key }.joinToString(",") { "${syncQuote(it)}=excluded.${syncQuote(it)}" }
    val action = if (assignments.isEmpty()) "DO NOTHING" else "DO UPDATE SET $assignments"
    val conflict = "(${syncQuote(key)})"
    execute("INSERT INTO ${syncQuote(change.table)} (${names.joinToString(",")}) VALUES (${names.joinToString(",") { "?" }}) ON CONFLICT$conflict $action",
        row.values.map(::syncSqlValue))
}

private fun SqlOperations.syncFolderAlias(id: String, row: MutableMap<String, JsonValue>) {
    if (row["deleted_at"] != null && row["deleted_at"] != JsonValue.Null) return
    val folder = row["folder_id"] ?: return
    val type = row["item_type"] ?: return
    val item = row["item_id"] ?: return
    val existing = query("SELECT id FROM folder_items WHERE folder_id IS ? AND item_type=? AND item_id=? AND deleted_at IS NULL",
        listOf(syncSqlValue(folder), syncSqlValue(type), syncSqlValue(item))) { it.text(0) }.singleOrNull()
    if (existing != null && existing != id) row["id"] = JsonValue.text(existing)
}

private fun syncRevisionEquals(key: String, local: JsonValue?, remote: JsonValue): Boolean {
    if (!key.endsWith("_json")) return local == remote
    fun parse(value: JsonValue?): JsonValue? = if (value is JsonValue.Text) JsonValue.parse(value.value) else value
    return parse(local) == parse(remote)
}

private fun SqlOperations.syncRevision(id: String, row: MutableMap<String, JsonValue>): Boolean {
    val existing = syncRead("note_document_revisions", id)
    if (existing != null) {
        require(row.filterKeys { it != "pinned" }.all { (key, value) -> syncRevisionEquals(key, existing[key], value) })
        if ((row["pinned"] as? JsonValue.Number)?.asLongOrNull() == 1L) {
            execute("UPDATE note_document_revisions SET pinned=1 WHERE version_id=?", listOf(text(id)))
        }
        return true
    }
    row["edit_bucket"] = JsonValue.integer(0)
    return false
}
