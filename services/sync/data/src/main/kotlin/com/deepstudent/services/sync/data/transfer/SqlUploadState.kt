package com.deepstudent.services.sync.data.transfer

import com.deepstudent.core.common.id.Digests
import com.deepstudent.core.common.json.JsonValue
import com.deepstudent.platform.database.SqlDatabase
import com.deepstudent.platform.database.SqlOperations
import com.deepstudent.platform.database.SqlValue
import com.deepstudent.services.sync.api.SyncChangeReceipt
import com.deepstudent.services.sync.api.SyncError
import com.deepstudent.services.sync.backend.SyncFailure
import com.deepstudent.services.sync.backend.transfer.PendingPublication
import com.deepstudent.services.sync.backend.transfer.PublicationJournal

class SqlUploadState(private val database: SqlDatabase, private val instance: String, private val device: String) : PublicationJournal {
    init {
        database.transaction {
            execute(
                "CREATE TABLE IF NOT EXISTS sync_upload_state (instance TEXT NOT NULL,device TEXT NOT NULL,seq INTEGER NOT NULL,PRIMARY KEY(instance,device))",
            )
            execute(
                """CREATE TABLE IF NOT EXISTS sync_pending_publication (instance TEXT NOT NULL,device TEXT NOT NULL,
                object_key TEXT NOT NULL,seq INTEGER NOT NULL,version INTEGER NOT NULL,payload BLOB NOT NULL,
                payload_hash TEXT NOT NULL,change_log_ids TEXT NOT NULL,PRIMARY KEY(instance,device))
                """.trimIndent(),
            )
        }
    }

    override fun published(): Long = database.query("SELECT seq FROM sync_upload_state WHERE instance=? AND device=?", scope()) {
        it.long(0)
    }.singleOrNull() ?: 0

    override fun pending(): PendingPublication? = database.query(
        "SELECT object_key,seq,version,payload,payload_hash,change_log_ids FROM sync_pending_publication WHERE instance=? AND device=?",
        scope(),
    ) {
        val bytes = it.blob(3)
        val encodedReceipts = it.text(5)
        if (bytes.size > com.deepstudent.services.sync.backend.SyncObjectLimits.WIRE_BYTES ||
            checksum(bytes, encodedReceipts, it.text(0), it.long(1), it.long(2)) != it.text(4)
        ) {
            invalid()
        }
        val (receipts, seed) = decodeReceipts(encodedReceipts)
        PendingPublication(it.text(0), it.long(1), it.long(2), bytes, receipts, seed)
    }.singleOrNull()

    override fun reserve(publication: PendingPublication) {
        val encodedReceipts = encodeReceipts(publication)
        database.transaction {
            execute(
                """INSERT INTO sync_pending_publication(instance,device,object_key,seq,version,payload,payload_hash,change_log_ids)
                VALUES(?,?,?,?,?,?,?,?) ON CONFLICT(instance,device) DO NOTHING
                """.trimIndent(),
                scope() + listOf(
                    SqlValue.Text(publication.key),
                    SqlValue.Integer(publication.sequence),
                    SqlValue.Integer(publication.version),
                    SqlValue.Blob(publication.payload),
                    SqlValue.Text(
                        checksum(
                            publication.payload,
                            encodedReceipts,
                            publication.key,
                            publication.sequence,
                            publication.version,
                        ),
                    ),
                    SqlValue.Text(encodedReceipts),
                ),
            )
        }
        val saved = pending() ?: invalid()
        if (saved.key != publication.key || saved.sequence != publication.sequence || saved.version != publication.version ||
            saved.seed != publication.seed || saved.receipts != publication.receipts || !saved.payload.contentEquals(publication.payload)
        ) {
            invalid()
        }
    }

    override fun complete(publication: PendingPublication) = database.transaction {
        val current = query("SELECT object_key FROM sync_pending_publication WHERE instance=? AND device=?", scope()) {
            it.text(0)
        }.singleOrNull()
        if (current != publication.key) invalid()
        publish(publication.sequence)
        execute("DELETE FROM sync_pending_publication WHERE instance=? AND device=?", scope())
    }

    override fun markPublished(seq: Long) = database.transaction { publish(seq) }

    private fun SqlOperations.publish(seq: Long) {
        if (seq <= 0) invalid()
        execute(
            "INSERT INTO sync_upload_state(instance,device,seq) VALUES(?,?,?) ON CONFLICT(instance,device) DO UPDATE SET seq=max(seq,excluded.seq)",
            scope() + SqlValue.Integer(seq),
        )
    }

    private fun checksum(payload: ByteArray, receipts: String, key: String, sequence: Long, version: Long): String {
        val bytes = Digests.sha256Hex(payload)
        return if (receipts.startsWith("[")) bytes else Digests.sha256HexUtf8("$sequence\n$version\n$key\n$bytes\n$receipts")
    }

    private fun encodeReceipts(publication: PendingPublication): String = JsonValue.Object.Builder()
        .put("seed", publication.seed)
        .put(
            "receipts",
            JsonValue.array(
                publication.receipts.map { receipt ->
                    JsonValue.Object.Builder().put("database", receipt.database).put("id", receipt.changeLogId).build()
                },
            ),
        ).build().literal()

    private fun decodeReceipts(json: String): Pair<List<SyncChangeReceipt>, Boolean> = try {
        when (val body = JsonValue.parse(json)) {
            // Before S2.29 the sole outgoing database was VFS. Do not re-encrypt older reservations.
            is JsonValue.Array -> body.values.map { receipt(SyncChangeReceipt.VFS, it) } to false

            is JsonValue.Object -> decodeCurrentReceipts(body)

            else -> invalid()
        }.also { (rows, _) -> if (rows.distinct().size != rows.size) invalid() }
    } catch (_: Exception) {
        invalid()
    }

    private fun decodeCurrentReceipts(body: JsonValue.Object): Pair<List<SyncChangeReceipt>, Boolean> {
        val rows = (body["receipts"] as? JsonValue.Array)?.values ?: invalid()
        val seed = (body["seed"] as? JsonValue.Bool)?.value ?: invalid()
        return rows.map { row ->
            val value = row as? JsonValue.Object ?: invalid()
            receipt((value["database"] as? JsonValue.Text)?.value ?: invalid(), value["id"])
        } to seed
    }

    private fun receipt(database: String, id: JsonValue?): SyncChangeReceipt =
        SyncChangeReceipt(database, (id as? JsonValue.Number)?.asLongOrNull() ?: invalid())

    private fun scope(): List<SqlValue> = listOf(SqlValue.Text(instance), SqlValue.Text(device))
    private fun invalid(): Nothing = throw SyncFailure(SyncError.STORAGE)
}
