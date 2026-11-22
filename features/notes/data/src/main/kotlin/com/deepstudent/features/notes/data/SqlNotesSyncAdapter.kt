package com.deepstudent.features.notes.data

import com.deepstudent.core.common.json.JsonValue
import com.deepstudent.core.common.logging.Logger
import com.deepstudent.core.common.logging.NoopLogger
import com.deepstudent.platform.database.SqlDatabase
import com.deepstudent.platform.database.SqlOperations
import com.deepstudent.platform.database.SqlReadOperations
import com.deepstudent.platform.database.SqlValue
import com.deepstudent.services.sync.api.DomainSyncAdapter
import java.util.UUID
import java.util.concurrent.CancellationException

/** Supplies captured wire changes and keeps [enrich] on the very same read-only snapshot. */
fun interface NotesPendingSource {
    fun read(enrich: (SqlReadOperations, List<ByteArray>) -> List<ByteArray>): List<ByteArray>
}

class SqlNotesSyncAdapter(
    private val database: SqlDatabase,
    private val instanceId: String,
    private val verifyBlob: (String, String, Long) -> Boolean = { _, _, _ -> false },
    private val logger: Logger = NoopLogger,
    private val checkCancelled: () -> Unit = ::syncCheckCancelled,
    private val pendingSource: NotesPendingSource,
) : DomainSyncAdapter {
    init {
        require(instanceId.isNotBlank())
        database.transaction { syncSetup() }
    }

    override fun pending(): List<ByteArray> = guarded("pending") {
        pendingSource.read { sql, wire ->
            val changes = wire.map { checkCancelled(); NoteSyncChange.parse(it) }
            require(changes.all { it.table in NoteSyncRows.tables })
            sql.syncDependencies(changes, checkCancelled).map { checkCancelled(); it.bytes() }
        }
    }

    override fun markPublished(changeLogIds: List<Long>, version: Long) = guarded("publish") {
        require(version > 0)
        database.transaction {
            for (id in changeLogIds) {
                checkCancelled()
                execute("UPDATE __change_log SET sync_version=? WHERE id=? AND COALESCE(sync_version,0)=0",
                    listOf(SqlValue.Integer(version), SqlValue.Integer(id)))
            }
        }
    }

    override fun cursor(device: String): Long = guarded("cursor") { database.syncCursor(instanceId, device) }

    override fun apply(device: String, seq: Long, changes: List<ByteArray>) = guarded("apply") {
        require(device.isNotBlank() && seq > 0)
        if (seq <= database.syncCursor(instanceId, device)) return@guarded
        val decoded = changes.map { checkCancelled(); NoteSyncChange.parse(it) }
        require(decoded.all { it.table in NoteSyncRows.tables })
        val complete = syncRequireImagesOrMatchingDelete(decoded)
        complete.forEach(::verifyIncomingBlob)
        database.transaction {
            if (seq <= syncCursor(instanceId, device)) return@transaction
            val max = query("SELECT COALESCE(MAX(id),0) FROM __change_log") { it.long(0) }.single()
            val effective = syncCoalesce(complete)
            for (change in syncSort(effective)) {
                checkCancelled()
                applyRow(change, device, seq)
            }
            checkCancelled()
            execute("UPDATE __change_log SET sync_version=1 WHERE id>? AND COALESCE(sync_version,0)=0", listOf(SqlValue.Integer(max)))
            execute("INSERT INTO __android_sync_cursor_v2(instance,device,seq) VALUES(?,?,?) ON CONFLICT(instance,device) DO UPDATE SET seq=excluded.seq",
                listOf(text(instanceId), text(device), SqlValue.Integer(seq)))
        }
    }

    private fun verifyIncomingBlob(change: NoteSyncChange) {
        checkCancelled()
        if (change.table != "blobs" || change.operation == "DELETE") return
        val data = change.data as? JsonValue.Object ?: error("invalid")
        val hash = (data["hash"] as? JsonValue.Text)?.value ?: error("invalid")
        val path = (data["relative_path"] as? JsonValue.Text)?.value ?: error("invalid")
        val size = (data["size"] as? JsonValue.Number)?.asLongOrNull() ?: error("invalid")
        check(verifyBlob(hash, path, size))
    }

    private fun SqlOperations.applyRow(change: NoteSyncChange, device: String, seq: Long) {
        try {
            syncApply(change, instanceId, device, seq)
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (failure: Exception) {
            logger.info("notes_sync.row_failed", JsonValue.Object.Builder().put("owner", "notes")
                .put("table", change.table).put("operation", change.operation)
                .put("errorType", failure.javaClass.simpleName).build())
            throw IllegalStateException("Sync row apply failed")
        }
    }

    private inline fun <T> guarded(phase: String, block: () -> T): T {
        val start = System.nanoTime()
        val correlation = UUID.randomUUID().toString()
        try {
            val result = block()
            diagnostic(phase, correlation, start, "complete")
            return result
        } catch (cancelled: CancellationException) {
            diagnostic(phase, correlation, start, "cancelled")
            throw cancelled
        } catch (_: Exception) {
            diagnostic(phase, correlation, start, "failed")
            throw IllegalStateException("Sync operation failed")
        }
    }

    private fun diagnostic(phase: String, correlation: String, start: Long, status: String) {
        logger.info("notes_sync", JsonValue.Object.Builder().put("correlationId", correlation)
            .put("owner", "notes").put("phase", phase).put("status", status)
            .put("elapsedMs", JsonValue.integer((System.nanoTime() - start) / 1_000_000)).build())
    }
}

internal fun syncCheckCancelled() {
    if (Thread.currentThread().isInterrupted) throw CancellationException("Sync cancelled")
}

private fun SqlOperations.syncCursor(instance: String, device: String): Long =
    query("SELECT seq FROM __android_sync_cursor_v2 WHERE instance=? AND device=?", listOf(text(instance), text(device))) {
        it.long(0)
    }.singleOrNull() ?: 0

/** Desktop skips a missing image only when a real same-table, same-key DELETE is in this batch. */
private fun syncRequireImagesOrMatchingDelete(changes: List<NoteSyncChange>): List<NoteSyncChange> {
    val deleted = changes.filter { it.operation == "DELETE" }.map { it.table to it.id }.toSet()
    return changes.filter { change ->
        if (change.operation == "DELETE" || change.data != null) true
        else {
            require(change.table to change.id in deleted) { "upsert has no image or matching delete" }
            false
        }
    }
}

private fun syncCoalesce(changes: List<NoteSyncChange>): List<NoteSyncChange> {
    val groups = changes.filter { it.table != "note_document_revisions" }.groupBy { it.table to it.id }
    val winners = groups.mapValues { (_, group) -> group.reduce { left, right ->
        val leftTime = syncTimestamp((left.data as? JsonValue.Object)?.get("updated_at")) ?: left.changedAt
        val rightTime = syncTimestamp((right.data as? JsonValue.Object)?.get("updated_at")) ?: right.changedAt
        val order = syncCompare(leftTime, left.source, "", rightTime, right.source, "")
        if (order > 0 || (order == 0 && left.operation == "DELETE" && right.operation != "DELETE")) left else right
    } }
    return changes.filter { it.table == "note_document_revisions" || winners[it.table to it.id] === it }
}

private fun syncSort(changes: List<NoteSyncChange>): List<NoteSyncChange> {
    val folders = changes.filter { it.table == "folders" && it.operation != "DELETE" }
    val remaining = folders.toMutableList()
    val ordered = mutableListOf<NoteSyncChange>()
    while (remaining.isNotEmpty()) {
        val ids = remaining.map { it.id }.toSet()
        val ready = remaining.filter { change ->
            val parent = ((change.data as? JsonValue.Object)?.get("parent_id") as? JsonValue.Text)?.value
            parent == null || parent !in ids
        }
        require(ready.isNotEmpty())
        ordered.addAll(ready)
        remaining.removeAll(ready.toSet())
    }
    val others = changes.filterNot { it in folders }.sortedBy { syncOrder(it) }
    val deletes = others.filter { it.operation == "DELETE" && it.table != "folders" }
    val folderDeletes = others.filter { it.operation == "DELETE" && it.table == "folders" }
    return ordered + others.filter { it.operation != "DELETE" } + deletes + folderDeletes
}

private val syncRanks = mapOf("blobs" to 0, "resources" to 1, "folders" to 2, "files" to 3, "notes" to 4,
    "folder_items" to 5, "note_document_formats" to 5, "note_document_revisions" to 6, "note_learning_relations" to 6)

private fun syncOrder(change: NoteSyncChange): Int {
    val rank = syncRanks[change.table] ?: error("invalid")
    return if (change.operation == "DELETE") -rank else rank
}
