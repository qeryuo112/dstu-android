package com.deepstudent.app.runtime.script

import com.deepstudent.core.common.id.TaskId
import com.deepstudent.core.common.json.JsonValue
import com.deepstudent.platform.database.DatabaseFactory
import com.deepstudent.services.runtime.api.RuntimeState
import com.deepstudent.services.script.api.ScriptFailureKind
import com.deepstudent.services.script.backend.ScriptFailureRecord
import com.deepstudent.services.script.backend.ScriptReceipt
import com.deepstudent.services.script.backend.ScriptReceiptContent
import com.deepstudent.services.script.backend.ScriptTerminalState
import com.deepstudent.services.script.data.SqlScriptCompletionOutbox
import com.deepstudent.services.script.data.SqlScriptReceiptStore
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test
import java.nio.file.Files
import java.time.Instant

/** App-level adapter tests over real SQLite; the business store remains the fact authority. */
class ScriptOutboxCompletionSourceTest {
    private val now = Instant.parse("2026-11-17T00:00:00Z")

    @Test
    fun adapterMapsOutboxFactsAndKeepsTheExistingStoreForLookup() = runBlocking {
        val dir = Files.createTempDirectory("script-outbox-source").toFile()
        try {
            DatabaseFactory.openLocal(dir.resolve("script.db")).use { database ->
                val store = SqlScriptReceiptStore(database)
                store.commit(receipt("a", 1))
                store.commit(
                    receipt("failed", 2).copy(
                        state = ScriptTerminalState.FAILED,
                        receipt = "ptc:failed:denied",
                        content = ScriptReceiptContent(
                            null,
                            emptyList(),
                            ScriptFailureRecord(ScriptFailureKind.POLICY_VIOLATION, "denied"),
                            3,
                            0,
                        ),
                    ),
                )
                val source = ScriptOutboxCompletionSource(SqlScriptCompletionOutbox(database), store)

                val pending = source.pending("runtime", 10)
                assertEquals(listOf(factId("a", 1), factId("failed", 2)), pending.map { it.factId })
                assertEquals(listOf(RuntimeState.COMPLETED, RuntimeState.FAILED), pending.map { it.state })
                assertEquals("ptc:completed:a:digest", pending.first().receipt)

                assertEquals(pending.first(), source.find(TaskId.parse("a"), 1))
                assertNull(source.find(TaskId.parse("missing"), 1))

                source.acknowledge("runtime", factId("a", 1))
                assertEquals(listOf(factId("failed", 2)), source.pending("runtime", 10).map { it.factId })
            }
        } finally {
            dir.deleteRecursively()
        }
    }

    @Test
    fun consumersStayIsolatedAcrossReopen() = runBlocking {
        val dir = Files.createTempDirectory("script-outbox-source-consumers").toFile()
        val file = dir.resolve("script.db")
        try {
            DatabaseFactory.openLocal(file).use { database ->
                val store = SqlScriptReceiptStore(database)
                listOf(receipt("a", 1), receipt("b", 2)).forEach { store.commit(it) }
                val source = ScriptOutboxCompletionSource(SqlScriptCompletionOutbox(database), store)

                source.pending("runtime", 10)
                source.acknowledge("runtime", factId("a", 1))

                assertEquals(listOf(factId("b", 2)), source.pending("runtime", 10).map { it.factId })
                assertEquals(listOf(factId("a", 1), factId("b", 2)), source.pending("other", 10).map { it.factId })
            }
            DatabaseFactory.openLocal(file).use { database ->
                val store = SqlScriptReceiptStore(database)
                val source = ScriptOutboxCompletionSource(SqlScriptCompletionOutbox(database), store)
                assertEquals(listOf(factId("b", 2)), source.pending("runtime", 10).map { it.factId })
                assertEquals(listOf(factId("a", 1), factId("b", 2)), source.pending("other", 10).map { it.factId })
            }
        } finally {
            dir.deleteRecursively()
        }
    }

    @Test
    fun pendingPagesNeverSkipUnacknowledgedFacts() = runBlocking {
        val dir = Files.createTempDirectory("script-outbox-source-pages").toFile()
        try {
            DatabaseFactory.openLocal(dir.resolve("script.db")).use { database ->
                val store = SqlScriptReceiptStore(database)
                listOf(receipt("a", 1), receipt("b", 2), receipt("c", 3)).forEach { store.commit(it) }
                val source = ScriptOutboxCompletionSource(SqlScriptCompletionOutbox(database), store)

                assertEquals(listOf(factId("a", 1), factId("b", 2)), source.pending("runtime", 2).map { it.factId })
                source.acknowledge("runtime", factId("b", 2))
                // b is acknowledged but a is not: the page refills past b instead of skipping a.
                assertEquals(listOf(factId("a", 1), factId("c", 3)), source.pending("runtime", 2).map { it.factId })
                source.acknowledge("runtime", factId("a", 1))
                assertEquals(listOf(factId("c", 3)), source.pending("runtime", 2).map { it.factId })
            }
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
