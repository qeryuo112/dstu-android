package com.deepstudent.services.sync.data.transfer

import com.deepstudent.core.common.id.Digests
import com.deepstudent.core.common.json.JsonValue
import com.deepstudent.platform.database.SqlDatabase
import com.deepstudent.platform.database.SqlOperations
import com.deepstudent.platform.database.SqlValue
import com.deepstudent.services.sync.api.SyncError
import com.deepstudent.services.sync.backend.SyncFailure
import com.deepstudent.services.sync.backend.transfer.PendingPublication
import com.deepstudent.services.sync.backend.transfer.PublicationJournal

class SqlUploadState(private val database: SqlDatabase, private val instance: String, private val device: String) : PublicationJournal {
    init {
        database.transaction {
            execute("CREATE TABLE IF NOT EXISTS sync_upload_state (instance TEXT NOT NULL,device TEXT NOT NULL,seq INTEGER NOT NULL,PRIMARY KEY(instance,device))")
            execute("""CREATE TABLE IF NOT EXISTS sync_pending_publication (instance TEXT NOT NULL,device TEXT NOT NULL,
                object_key TEXT NOT NULL,seq INTEGER NOT NULL,version INTEGER NOT NULL,payload BLOB NOT NULL,
                payload_hash TEXT NOT NULL,change_log_ids TEXT NOT NULL,PRIMARY KEY(instance,device))""".trimIndent())
        }
    }

    override fun published(): Long = database.query("SELECT seq FROM sync_upload_state WHERE instance=? AND device=?", scope()) {
        it.long(0)
    }.singleOrNull() ?: 0

    override fun pending(): PendingPublication? = database.query(
        "SELECT object_key,seq,version,payload,payload_hash,change_log_ids FROM sync_pending_publication WHERE instance=? AND device=?", scope(),
    ) {
        val bytes = it.blob(3)
        if (bytes.size > com.deepstudent.services.sync.backend.SyncObjectLimits.WIRE_BYTES || Digests.sha256Hex(bytes) != it.text(4)) invalid()
        val ids = (JsonValue.parse(it.text(5)) as? JsonValue.Array)?.values ?: invalid()
        PendingPublication(it.text(0), it.long(1), it.long(2), bytes, ids.map { value ->
            (value as? JsonValue.Number)?.asLongOrNull() ?: invalid()
        })
    }.singleOrNull()

    override fun reserve(publication: PendingPublication) {
        database.transaction {
            execute("""INSERT INTO sync_pending_publication(instance,device,object_key,seq,version,payload,payload_hash,change_log_ids)
                VALUES(?,?,?,?,?,?,?,?) ON CONFLICT(instance,device) DO NOTHING""".trimIndent(), scope() + listOf(
                SqlValue.Text(publication.key), SqlValue.Integer(publication.sequence), SqlValue.Integer(publication.version),
                SqlValue.Blob(publication.payload), SqlValue.Text(Digests.sha256Hex(publication.payload)),
                SqlValue.Text(JsonValue.array(publication.changeLogIds.map(JsonValue::integer)).literal()),
            ))
        }
        val saved = pending() ?: invalid()
        if (saved.key != publication.key || !saved.payload.contentEquals(publication.payload)) invalid()
    }

    override fun complete(publication: PendingPublication) = database.transaction {
        val current = query("SELECT object_key FROM sync_pending_publication WHERE instance=? AND device=?", scope()) { it.text(0) }.singleOrNull()
        if (current != publication.key) invalid()
        publish(publication.sequence)
        execute("DELETE FROM sync_pending_publication WHERE instance=? AND device=?", scope())
    }

    override fun markPublished(seq: Long) = database.transaction { publish(seq) }

    private fun SqlOperations.publish(seq: Long) {
        if (seq <= 0) invalid()
        execute("INSERT INTO sync_upload_state(instance,device,seq) VALUES(?,?,?) ON CONFLICT(instance,device) DO UPDATE SET seq=max(seq,excluded.seq)",
            scope() + SqlValue.Integer(seq))
    }

    private fun scope(): List<SqlValue> = listOf(SqlValue.Text(instance), SqlValue.Text(device))
    private fun invalid(): Nothing = throw SyncFailure(SyncError.STORAGE)
}
