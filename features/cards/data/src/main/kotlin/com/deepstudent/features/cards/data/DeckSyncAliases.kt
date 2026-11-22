package com.deepstudent.features.cards.data

import com.deepstudent.core.common.json.JsonValue
import com.deepstudent.platform.database.SqlOperations

internal fun SqlOperations.deckResolve(id: String): String {
    val seen = mutableSetOf<String>()
    var current = id
    while (true) {
        require(seen.add(current))
        val next = query("SELECT canonical_id FROM __sync_id_aliases WHERE table_name='anki_decks' AND remote_id=?", listOf(text(current))) { it.text(0) }.singleOrNull()
            ?: return current
        require(next.isNotBlank() && next != current)
        current = next
    }
}

internal fun SqlOperations.deckCanonicalId(change: IncomingDeckChange): String {
    val resolved = deckResolve(change.id)
    val local = deckRow(resolved)
    val name = (change.data?.get("name") as? JsonValue.Text)?.value ?: return resolved
    val namedId = query("SELECT id FROM anki_decks WHERE name=?", listOf(text(name))) { it.text(0) }.singleOrNull()
        ?: return resolved
    if (namedId == resolved) return resolved
    require(local == null && resolved == change.id)
    deckPersistAlias(change.id, namedId)
    return namedId
}

private fun SqlOperations.deckPersistAlias(remote: String, canonical: String) {
    require(remote != canonical)
    execute("INSERT INTO __sync_id_aliases(table_name,remote_id,canonical_id) VALUES('anki_decks',?,?)", listOf(text(remote), text(canonical)))
}
