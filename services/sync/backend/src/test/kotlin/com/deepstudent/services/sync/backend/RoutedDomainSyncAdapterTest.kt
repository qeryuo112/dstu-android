package com.deepstudent.services.sync.backend

import com.deepstudent.services.sync.api.DomainSyncAdapter
import com.deepstudent.services.sync.backend.transfer.DomainIncomingRoute
import com.deepstudent.services.sync.backend.transfer.RoutedDomainSyncAdapter
import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Test

class RoutedDomainSyncAdapterTest {
    @Test fun mixedDatabaseBatchCommitsAncillaryBeforePrimaryCursorAndReplaysSafely() {
        val primary = Domain()
        val effects = mutableListOf<String>()
        val adapter = RoutedDomainSyncAdapter(primary, "vfs", setOf("notes"), listOf(
            DomainIncomingRoute("mistakes", setOf("anki_decks")) { effects.add("deck"); check(primary.cursor == 0L) },
        ))
        adapter.apply("peer", 1, listOf(change("vfs", "notes"), change("mistakes", "anki_decks")))
        assertEquals(listOf("deck"), effects)
        assertEquals(1, primary.rows.size)
        assertEquals(1, primary.cursor)
        adapter.apply("peer", 1, listOf(change("vfs", "notes"), change("mistakes", "anki_decks")))
        assertEquals(listOf("deck"), effects)
    }

    @Test fun unsupportedTableIsRejectedBeforeAnySideEffectAndFailureNeverAdvancesCursor() {
        val primary = Domain()
        var effects = 0
        val route = DomainIncomingRoute("mistakes", setOf("anki_decks")) { effects++; error("public fixture failure") }
        val adapter = RoutedDomainSyncAdapter(primary, "vfs", setOf("notes"), listOf(route))
        assertThrows(SyncFailure::class.java) {
            adapter.apply("peer", 1, listOf(change("mistakes", "anki_decks"), change("chat_v2", "chat_v2_messages")))
        }
        assertEquals(0, effects)
        assertThrows(IllegalStateException::class.java) {
            adapter.apply("peer", 1, listOf(change("mistakes", "anki_decks"), change("vfs", "notes")))
        }
        assertEquals(0, primary.cursor)
    }

    private fun change(database: String, table: String) = """{"database_name":"$database","table_name":"$table"}""".toByteArray()

    private class Domain : DomainSyncAdapter {
        var rows = emptyList<ByteArray>()
        var cursor = 0L
        override fun pending(): List<ByteArray> = emptyList()
        override fun markPublished(changeLogIds: List<Long>, version: Long) = Unit
        override fun cursor(device: String): Long = cursor
        override fun apply(device: String, seq: Long, changes: List<ByteArray>) { rows = changes; cursor = seq }
    }
}
