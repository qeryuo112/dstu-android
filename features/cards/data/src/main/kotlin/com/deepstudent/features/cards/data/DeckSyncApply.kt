package com.deepstudent.features.cards.data

import com.deepstudent.core.common.json.JsonValue
import com.deepstudent.platform.database.SqlOperations

internal fun SqlOperations.deckApply(change: IncomingDeckChange) {
    val id = deckCanonicalId(change)
    val local = deckRow(id)
    if (change.operation == "DELETE") {
        deckDelete(change, id, local)
    } else {
        deckUpsert(change, id, local)
    }
}

private fun SqlOperations.deckUpsert(change: IncomingDeckChange, id: String, local: JsonValue.Object?) {
    val incoming = JsonValue.Object.from(change.data!!.asMap() + ("id" to JsonValue.text(id)))
    val millis = (incoming["updated_at"] as? JsonValue.Text)?.value?.let(::deckTimestampMillis)
    if (millis != null) require(millis <= System.currentTimeMillis() + 60_000)
    if (deckBlockedByDelete(change, id, local)) return
    if (deckLocalWins(local, incoming, change)) return
    deckVault(id, incoming)
    deckWrite(id, incoming, local)
    deckClearDelete(id)
}

private fun SqlOperations.deckDelete(change: IncomingDeckChange, id: String, local: JsonValue.Object?) {
    val time = deckTimestampMillis(change.changedAt) ?: error("invalid")
    require(time <= System.currentTimeMillis() + 60_000)
    if (deckBlockedByDelete(change, id, local)) return
    if (deckLocalWins(local, null, change)) return
    if (local != null && local["deleted_at"] == JsonValue.Null) {
        execute("UPDATE anki_decks SET deleted_at=? WHERE id=? AND deleted_at IS NULL", listOf(text(change.changedAt), text(id)))
    }
    deckRecordDelete(change, id)
}

private fun deckLocalWins(local: JsonValue.Object?, incoming: JsonValue.Object?, change: IncomingDeckChange): Boolean {
    val localTime = (local?.get("updated_at") as? JsonValue.Text)?.value ?: return false
    val remoteTime = if (incoming == null) change.changedAt else (incoming["updated_at"] as? JsonValue.Text)?.value ?: return false
    val millis = deckTimestampMillis(remoteTime)
    if (millis != null) require(millis <= System.currentTimeMillis() + 60_000)
    val localDevice = (local["device_id"] as? JsonValue.Text)?.value.orEmpty()
    val remoteDevice = change.source.ifBlank { (incoming?.get("device_id") as? JsonValue.Text)?.value.orEmpty() }
    val devices = if (localDevice.isNotBlank() && remoteDevice.isNotBlank()) localDevice to remoteDevice else "" to ""
    return deckCompare(localTime, devices.first, local.literal(), remoteTime, devices.second, incoming?.literal().orEmpty()) > 0
}
