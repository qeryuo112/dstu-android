package com.deepstudent.services.script.data

import com.deepstudent.core.common.id.TaskId
import com.deepstudent.core.common.json.JsonValue
import com.deepstudent.core.common.logging.LogRecord
import com.deepstudent.core.common.logging.Logger
import com.deepstudent.platform.database.DatabaseFactory
import com.deepstudent.platform.database.SqlDatabase
import com.deepstudent.services.script.backend.ScriptReceipt
import com.deepstudent.services.script.backend.ScriptReceiptContent
import com.deepstudent.services.script.backend.ScriptTerminalState
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.nio.file.Files
import java.time.Instant

/** Real SQLite tests for the opt-in outbox: resume after reopen and per-consumer isolation. */
class SqlScriptCompletionOutboxTest {
    private val now = Instant.parse("2026-11-17T00:00:00Z")

    @Test
    fun outboxStoresOnlySequenceAndFactAndAppendsWithTheReceipt() = runBlocking {
        withDatabase("script-outbox-shape") { database ->
            val store = SqlScriptReceiptStore(database)
            store.commit(receipt("a", 1))
            store.commit(receipt("b", 2))
            val outbox = SqlScriptCompletionOutbox(database)

            assertEquals(
                listOf("seq", "fact_id"),
                database.readSnapshot {
                    query("SELECT name FROM pragma_table_info('script_receipt_outbox')") { it.text(0) }
                },
            )
            assertEquals(listOf(factId("a", 1), factId("b", 2)), outbox.pending("runtime", 10).map { it.factId })
            assertEquals(0L, outbox.consumerCursor("runtime"))

            // The trigger appends in the receipt transaction, so a later commit is visible at once.
            store.commit(receipt("c", 3))
            assertEquals(listOf(factId("c", 3)), outbox.pending("runtime", 10).drop(2).map { it.factId })
        }
    }

    @Test
    fun consumerCursorNeverCrossesAnUnacknowledgedFact() = runBlocking {
        withDatabase("script-outbox-prefix") { database ->
            val store = SqlScriptReceiptStore(database)
            listOf(receipt("a", 1), receipt("b", 2), receipt("c", 3)).forEach { store.commit(it) }
            val outbox = SqlScriptCompletionOutbox(database)

            // Out-of-order acknowledgement cannot move the cursor over the gap.
            outbox.acknowledge("runtime", factId("b", 2))
            assertEquals(0L, outbox.consumerCursor("runtime"))
            assertEquals(listOf(factId("a", 1), factId("c", 3)), outbox.pending("runtime", 10).map { it.factId })

            outbox.acknowledge("runtime", factId("a", 1))
            assertEquals(2L, outbox.consumerCursor("runtime"))
            assertEquals(listOf(factId("c", 3)), outbox.pending("runtime", 10).map { it.factId })

            outbox.acknowledge("runtime", factId("c", 3))
            assertEquals(3L, outbox.consumerCursor("runtime"))
            assertTrue(outbox.pending("runtime", 10).isEmpty())
        }
    }

    @Test
    fun reopenedDatabaseResumesFromThePersistedCursor() = runBlocking {
        val dir = Files.createTempDirectory("script-outbox-resume").toFile()
        val file = dir.resolve("script.db")
        try {
            DatabaseFactory.openLocal(file).use { database ->
                val store = SqlScriptReceiptStore(database)
                listOf(receipt("a", 1), receipt("b", 2), receipt("c", 3)).forEach { store.commit(it) }
                SqlScriptCompletionOutbox(database).acknowledge("runtime", factId("a", 1))
            }
            DatabaseFactory.openLocal(file).use { database ->
                val outbox = SqlScriptCompletionOutbox(database)
                assertEquals(1L, outbox.consumerCursor("runtime"))
                assertEquals(listOf(factId("b", 2), factId("c", 3)), outbox.pending("runtime", 10).map { it.factId })
            }
        } finally {
            dir.deleteRecursively()
        }
    }

    @Test
    fun consumersKeepIndependentCursorsAndAcknowledgements() = runBlocking {
        val dir = Files.createTempDirectory("script-outbox-consumers").toFile()
        val file = dir.resolve("script.db")
        try {
            DatabaseFactory.openLocal(file).use { database ->
                val store = SqlScriptReceiptStore(database)
                listOf(receipt("a", 1), receipt("b", 2)).forEach { store.commit(it) }
                val outbox = SqlScriptCompletionOutbox(database)

                outbox.acknowledge("runtime", factId("a", 1))
                outbox.acknowledge("projection", factId("b", 2))

                assertEquals(1L, outbox.consumerCursor("runtime"))
                assertEquals(0L, outbox.consumerCursor("projection"))
                assertEquals(listOf(factId("b", 2)), outbox.pending("runtime", 10).map { it.factId })
                assertEquals(listOf(factId("a", 1)), outbox.pending("projection", 10).map { it.factId })
            }
            DatabaseFactory.openLocal(file).use { database ->
                val outbox = SqlScriptCompletionOutbox(database)
                assertEquals(1L, outbox.consumerCursor("runtime"))
                assertEquals(0L, outbox.consumerCursor("projection"))
            }
        } finally {
            dir.deleteRecursively()
        }
    }

    @Test
    fun receiptsCommittedBeforeInstallationAreBackfilledOnce() = runBlocking {
        withDatabase("script-outbox-backfill") { database ->
            val store = SqlScriptReceiptStore(database)
            store.commit(receipt("old", 1))
            val outbox = SqlScriptCompletionOutbox(database)

            assertEquals(listOf(factId("old", 1)), outbox.pending("runtime", 10).map { it.factId })
            assertEquals(0, outbox.backfill())
            assertEquals(1, outbox.pending("runtime", 10).size)
        }
    }

    @Test
    fun unknownFactIsRejectedWithoutMovingTheCursor() = runBlocking {
        withDatabase("script-outbox-unknown") { database ->
            val store = SqlScriptReceiptStore(database)
            store.commit(receipt("a", 1))
            val outbox = SqlScriptCompletionOutbox(database)

            val failure = runCatching { outbox.acknowledge("runtime", "script-ptc:missing:1") }.exceptionOrNull()
            assertTrue(failure is IllegalStateException)
            assertEquals(0L, outbox.consumerCursor("runtime"))
            assertEquals(listOf(factId("a", 1)), outbox.pending("runtime", 10).map { it.factId })
        }
    }

    @Test
    fun diagnosticsNeverExposeConsumerOrFactIdentifiers() = runBlocking {
        val records = mutableListOf<LogRecord>()
        val logger = object : Logger {
            override fun log(record: LogRecord) {
                records += record
            }
        }
        withDatabase("script-outbox-logs") { database ->
            val store = SqlScriptReceiptStore(database)
            store.commit(receipt("a", 1))
            val outbox = SqlScriptCompletionOutbox(database, logger)
            outbox.pending("runtime", 10)
            outbox.acknowledge("runtime", factId("a", 1))
        }
        assertTrue(records.isNotEmpty())
        records.forEach { record ->
            val rendered = record.toString()
            assertFalse(rendered, rendered.contains("runtime"))
            assertFalse(rendered, rendered.contains(factId("a", 1)))
            assertFalse(rendered, rendered.contains("ptc:completed:a:digest"))
            // Structured fields are fixed: phase, owner, outcome and a monotonic duration.
            assertTrue(rendered, record.fields.containsKey("phase"))
            assertTrue(rendered, record.fields.containsKey("owner"))
            assertTrue(rendered, record.fields.containsKey("outcome"))
            assertTrue(rendered, record.fields.containsKey("elapsed_us"))
        }
    }

    @Test
    fun blockedReceiptCommitLeavesNoReceiptAndNoOutboxRow() = runBlocking {
        withDatabase("script-outbox-rollback") { database ->
            val store = SqlScriptReceiptStore(database)
            val outbox = SqlScriptCompletionOutbox(database)
            database.execute(
                "CREATE TRIGGER script_receipt_block AFTER INSERT ON script_receipts " +
                    "BEGIN SELECT RAISE(ABORT, 'blocked'); END",
            )

            val failure = runCatching { store.commit(receipt("a", 1)) }.exceptionOrNull()
            assertTrue(failure != null)
            // The append trigger shares the receipt transaction, so both sides roll back together.
            assertEquals(0L, database.readSnapshot { query("SELECT COUNT(*) FROM script_receipts") { it.long(0) }.single() })
            assertEquals(0L, database.readSnapshot { query("SELECT COUNT(*) FROM script_receipt_outbox") { it.long(0) }.single() })
            assertTrue(outbox.pending("runtime", 10).isEmpty())
        }
    }

    private suspend fun withDatabase(name: String, block: suspend (SqlDatabase) -> Unit) {
        val dir = Files.createTempDirectory(name).toFile()
        try {
            DatabaseFactory.openLocal(dir.resolve("script.db")).use { block(it) }
        } finally {
            dir.deleteRecursively()
        }
    }

    private fun receipt(name: String, generation: Long): ScriptReceipt = ScriptReceipt(
        factId = factId(name, generation),
        taskId = TaskId.parse(name),
        generation = generation,
        state = ScriptTerminalState.COMPLETED,
        receipt = "ptc:completed:$name:digest",
        committedAt = now,
        content = ScriptReceiptContent(
            result = JsonValue.obj("name" to JsonValue.text(name)),
            trace = emptyList(),
            failure = null,
            durationMillis = 1,
            callsUsed = 0,
        ),
    )

    private fun factId(name: String, generation: Long) = "script-ptc:$name:$generation"
}
