package com.deepstudent.services.backup.data

import com.deepstudent.platform.database.SqlOperations
import com.deepstudent.platform.database.SqlReadOperations
import com.deepstudent.platform.database.SqlValue

/* Versioned, local-only contract. Existing Android consumers continue using their established names.
 * These tables are not desktop migrations, a wire format, or a replacement for remote fencing. */
private data class BackupIdentityTable(val name: String, val sql: String, val columns: String, val unique: Set<String>)
private val identityTables = listOf(
    BackupIdentityTable("__dstu_identity_meta", "version INTEGER NOT NULL PRIMARY KEY", "version:INTEGER:1:1", setOf("version")),
    BackupIdentityTable("sync_upload_state", "instance TEXT NOT NULL,device TEXT NOT NULL,seq INTEGER NOT NULL,PRIMARY KEY(instance,device)",
        "instance:TEXT:1:1,device:TEXT:1:2,seq:INTEGER:1:0", setOf("instance,device")),
    BackupIdentityTable("__android_sync_cursor_v2", "instance TEXT NOT NULL,device TEXT NOT NULL,seq INTEGER NOT NULL,PRIMARY KEY(instance,device)",
        "instance:TEXT:1:1,device:TEXT:1:2,seq:INTEGER:1:0", setOf("instance,device")),
    BackupIdentityTable("__dstu_identity_source_cursor", "instance_id TEXT NOT NULL,uploader_device_id TEXT NOT NULL,last_seq INTEGER NOT NULL,PRIMARY KEY(instance_id,uploader_device_id)",
        "instance_id:TEXT:1:1,uploader_device_id:TEXT:1:2,last_seq:INTEGER:1:0", setOf("instance_id,uploader_device_id")),
    BackupIdentityTable("__dstu_identity_binding", "instance_id TEXT NOT NULL PRIMARY KEY,provider TEXT NOT NULL,endpoint_hint TEXT NOT NULL",
        "instance_id:TEXT:1:1,provider:TEXT:1:0,endpoint_hint:TEXT:1:0", setOf("instance_id")),
    BackupIdentityTable("__dstu_identity_watermark", "instance_id TEXT NOT NULL,source_device_id TEXT NOT NULL,kind TEXT NOT NULL,last_applied_offset INTEGER NOT NULL,PRIMARY KEY(instance_id,source_device_id,kind)",
        "instance_id:TEXT:1:1,source_device_id:TEXT:1:2,kind:TEXT:1:3,last_applied_offset:INTEGER:1:0", setOf("instance_id,source_device_id,kind")),
    BackupIdentityTable("__dstu_identity_publication", "instance_id TEXT NOT NULL,device_id TEXT NOT NULL,kind TEXT NOT NULL,operation_id TEXT NOT NULL,seq INTEGER NOT NULL,PRIMARY KEY(instance_id,device_id,kind,operation_id),UNIQUE(instance_id,device_id,kind,seq)",
        "instance_id:TEXT:1:1,device_id:TEXT:1:2,kind:TEXT:1:3,operation_id:TEXT:1:4,seq:INTEGER:1:0",
        setOf("instance_id,device_id,kind,operation_id", "instance_id,device_id,kind,seq")),
    BackupIdentityTable("__dstu_identity_legacy_key", "instance_id TEXT NOT NULL,object_key TEXT NOT NULL,PRIMARY KEY(instance_id,object_key)",
        "instance_id:TEXT:1:1,object_key:TEXT:1:2", setOf("instance_id,object_key")),
    BackupIdentityTable("__dstu_identity_receipt", "takeover_key TEXT NOT NULL PRIMARY KEY,instance_id TEXT NOT NULL,device_id TEXT NOT NULL,sidecar_sha256 TEXT NOT NULL",
        "takeover_key:TEXT:1:1,instance_id:TEXT:1:0,device_id:TEXT:1:0,sidecar_sha256:TEXT:1:0", setOf("takeover_key")),
    BackupIdentityTable("__dstu_identity_commit", "operation_id TEXT NOT NULL PRIMARY KEY,target_installation_id TEXT NOT NULL,instance_id TEXT NOT NULL,device_id TEXT NOT NULL,archive_sha256 TEXT NOT NULL,sidecar_sha256 TEXT NOT NULL,takeover_key TEXT NOT NULL UNIQUE,witness_sha256 TEXT NOT NULL,aliases_sha256 TEXT NOT NULL",
        "operation_id:TEXT:1:1,target_installation_id:TEXT:1:0,instance_id:TEXT:1:0,device_id:TEXT:1:0,archive_sha256:TEXT:1:0,sidecar_sha256:TEXT:1:0,takeover_key:TEXT:1:0,witness_sha256:TEXT:1:0,aliases_sha256:TEXT:1:0", setOf("operation_id", "takeover_key")),
)

private val pendingTable = BackupIdentityTable(
    "sync_pending_publication", "",
    "instance:TEXT:1:1,device:TEXT:1:2,object_key:TEXT:1:0,seq:INTEGER:1:0,version:INTEGER:1:0,payload:BLOB:1:0,payload_hash:TEXT:1:0,change_log_ids:TEXT:1:0",
    setOf("instance,device"),
)

internal fun SqlOperations.backupIdentitySetup() {
    for (table in identityTables) {
        execute("CREATE TABLE IF NOT EXISTS ${table.name} (${table.sql})")
        backupIdentityValidateTable(table)
    }
    val versions = query("SELECT version FROM __dstu_identity_meta") { it.long(0) }
    if (versions.isEmpty()) execute("INSERT INTO __dstu_identity_meta(version) VALUES(1)")
    else if (versions != listOf(1L)) throw BackupIdentityException(BackupIdentityFailure.CONTRACT_MISMATCH)
}

internal fun SqlOperations.backupIdentityHasPendingTable(): Boolean {
    val types = query("SELECT type FROM sqlite_master WHERE name=? COLLATE NOCASE", listOf(SqlValue.Text(pendingTable.name))) { it.text(0) }
    if (types.isEmpty()) return false
    if (types != listOf("table")) throw BackupIdentityException(BackupIdentityFailure.CONTRACT_MISMATCH)
    backupIdentityValidateTable(pendingTable)
    return true
}

private fun SqlReadOperations.backupIdentityValidateTable(table: BackupIdentityTable) {
    // SQLite identifiers are case-insensitive, but SqlSyncManifest discovers its table by exact name.
    val objects = query("SELECT name,type FROM sqlite_master WHERE name=? COLLATE NOCASE", listOf(SqlValue.Text(table.name))) {
        it.text(0) to it.text(1)
    }
    if (objects != listOf(table.name to "table")) throw BackupIdentityException(BackupIdentityFailure.CONTRACT_MISMATCH)
    val columns = query("SELECT name,type,\"notnull\",pk,hidden FROM pragma_table_xinfo(?) ORDER BY cid", listOf(SqlValue.Text(table.name))) {
        if (it.long(4) != 0L) throw BackupIdentityException(BackupIdentityFailure.CONTRACT_MISMATCH)
        "${it.text(0)}:${it.text(1).uppercase()}:${it.long(2)}:${it.long(3)}"
    }.joinToString(",")
    val triggers = query("SELECT 1 FROM sqlite_master WHERE type='trigger' AND tbl_name=? COLLATE NOCASE LIMIT 1", listOf(SqlValue.Text(table.name))) { it.long(0) }
    val unique = backupIdentityUniqueIndices(table)
    // An INTEGER PRIMARY KEY aliases the rowid and has no sqlite autoindex.
    val required = if (table.name == "__dstu_identity_meta") emptySet() else table.unique
    if (columns != table.columns || triggers.isNotEmpty() || !unique.containsAll(required)) {
        throw BackupIdentityException(BackupIdentityFailure.CONTRACT_MISMATCH)
    }
}

private fun SqlReadOperations.backupIdentityUniqueIndices(table: BackupIdentityTable): Set<String> {
    val indices = query("SELECT name FROM pragma_index_list(?) WHERE \"unique\"=1 AND partial=0", listOf(SqlValue.Text(table.name))) { it.text(0) }
    return indices.mapNotNull { name ->
        val keys = query("SELECT name,coll FROM pragma_index_xinfo(?) WHERE key=1 ORDER BY seqno", listOf(SqlValue.Text(name))) {
            if (it.isNull(0) || it.text(1) != "BINARY") null else it.text(0)
        }
        if (keys.any { it == null }) null else keys.joinToString(",")
    }.toSet()
}

/** Validate only existing read-side objects; never create or repair anything during reconciliation. */
internal fun SqlReadOperations.backupIdentityValidateCommitSchema() {
    val names = setOf("__dstu_identity_meta", "__dstu_identity_receipt", "__dstu_identity_commit")
    for (table in identityTables.filter { it.name in names }) backupIdentityValidateTable(table)
    if (query("SELECT version FROM __dstu_identity_meta") { it.long(0) } != listOf(1L)) {
        throw BackupIdentityException(BackupIdentityFailure.CONTRACT_MISMATCH)
    }
}

internal fun SqlOperations.backupIdentityResetConsumption() {
    execute("DELETE FROM __android_sync_cursor_v2")
    execute("DELETE FROM __dstu_identity_source_cursor")
    execute("DELETE FROM __dstu_identity_watermark")
    execute("DELETE FROM __dstu_identity_legacy_key")
    execute("DELETE FROM __dstu_identity_binding")
    // A copied archive must not retain an activation stamp for an earlier restore operation.
    execute("DELETE FROM __dstu_identity_commit")
    // Receipt intentionally survives ordinary resets; only a remote claim provides global exclusion.
}
