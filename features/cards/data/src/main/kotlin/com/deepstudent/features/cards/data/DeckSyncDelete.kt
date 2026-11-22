package com.deepstudent.features.cards.data

import com.deepstudent.core.common.json.JsonValue
import com.deepstudent.platform.database.SqlOperations
import com.deepstudent.platform.database.SqlValue

private data class DeleteVersion(val time: String, val device: String, val seq: ULong)

internal fun SqlOperations.deckBlockedByDelete(change: IncomingDeckChange, id: String, local: JsonValue.Object?): Boolean {
    val previous = deckDeleteVersion(id) ?: deckLocalDelete(local) ?: return false
    val source = change.source.ifBlank { (change.data?.get("device_id") as? JsonValue.Text)?.value.orEmpty() }
    val order = deckCompare(change.version, source, "", previous.time, previous.device, "")
    return order < 0 || (order == 0 && change.seq <= previous.seq)
}

private fun SqlOperations.deckDeleteVersion(id: String): DeleteVersion? = query(
    "SELECT changed_at,source_device_id,source_seq FROM __sync_delete_versions WHERE table_name='anki_decks' AND record_id=?",
    listOf(text(id)),
) { DeleteVersion(it.text(0), it.text(1), it.long(2).coerceAtLeast(0).toULong()) }.singleOrNull()

private fun deckLocalDelete(local: JsonValue.Object?): DeleteVersion? {
    val time = (local?.get("deleted_at") as? JsonValue.Text)?.value ?: return null
    val device = (local["device_id"] as? JsonValue.Text)?.value.orEmpty()
    return DeleteVersion(time, device, 0uL)
}

internal fun SqlOperations.deckRecordDelete(change: IncomingDeckChange, id: String) {
    val seq = change.seq.coerceAtMost(Long.MAX_VALUE.toULong()).toLong()
    execute("INSERT INTO __sync_delete_versions(table_name,record_id,changed_at,source_device_id,source_seq) VALUES('anki_decks',?,?,?,?) ON CONFLICT(table_name,record_id) DO UPDATE SET changed_at=excluded.changed_at,source_device_id=excluded.source_device_id,source_seq=excluded.source_seq",
        listOf(text(id), text(change.changedAt), text(change.source), SqlValue.Integer(seq)))
}

internal fun SqlOperations.deckClearDelete(id: String) {
    execute("DELETE FROM __sync_delete_versions WHERE table_name='anki_decks' AND record_id=?", listOf(text(id)))
}
