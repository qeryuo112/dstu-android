package com.deepstudent.services.script.data

import com.deepstudent.core.common.id.TaskId
import com.deepstudent.core.common.json.JsonValue
import com.deepstudent.platform.database.DatabaseFactory
import com.deepstudent.services.script.backend.ScriptFailureRecord
import com.deepstudent.services.script.backend.ScriptReceipt
import com.deepstudent.services.script.backend.ScriptReceiptContent
import com.deepstudent.services.script.backend.ScriptTerminalState
import com.deepstudent.services.script.backend.ScriptTraceEntry
import java.nio.file.Files
import java.time.Instant
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class SqlScriptReceiptStoreTest {
    private val taskId = TaskId.parse("script-task")
    private val now = Instant.parse("2026-11-17T00:00:00Z")

    @Test
    fun receiptsAreIdempotentAndSurviveReopen() = runBlocking {
        val dir = Files.createTempDirectory("script-receipts").toFile()
        val file = dir.resolve("script.db")
        try {
            DatabaseFactory.openLocal(file).use { database ->
                val store = SqlScriptReceiptStore(database)
                store.commit(receipt())
                store.commit(receipt())
                assertEquals(receipt(), store.findReceipt(taskId, 1))
                assertEquals(listOf("script-ptc:script-task:1"), store.pending("runtime", 10).map { it.factId })
                store.acknowledge("runtime", "script-ptc:script-task:1")
                store.acknowledge("runtime", "script-ptc:script-task:1")
                assertTrue(store.pending("runtime", 10).isEmpty())
            }
            DatabaseFactory.openLocal(file).use { database ->
                assertEquals(receipt(), SqlScriptReceiptStore(database).findReceipt(taskId, 1))
            }
        } finally {
            dir.deleteRecursively()
        }
    }

    @Test
    fun conflictingFactFailsClosed() = runBlocking {
        val dir = Files.createTempDirectory("script-receipts-conflict").toFile()
        try {
            DatabaseFactory.openLocal(dir.resolve("script.db")).use { database ->
                val store = SqlScriptReceiptStore(database)
                store.commit(receipt())
                val changed = receipt().copy(receipt = "ptc:different")
                assertTrue(runCatching { store.commit(changed) }.exceptionOrNull() is IllegalStateException)
                assertNull(store.findReceipt(TaskId.parse("missing"), 1))
            }
        } finally {
            dir.deleteRecursively()
        }
    }

    private fun receipt(): ScriptReceipt = ScriptReceipt(
        factId = "script-ptc:script-task:1",
        taskId = taskId,
        generation = 1,
        state = ScriptTerminalState.COMPLETED,
        receipt = "ptc:completed:2:digest",
        committedAt = now,
        content = ScriptReceiptContent(result = JsonValue.obj("sum" to JsonValue.integer(42)),
        trace = listOf(
            ScriptTraceEntry(0, "builtin-script_echo", "sha256:a", 1, 8, true),
            ScriptTraceEntry(1, "builtin-script_add", "sha256:b", 1, 8, true),
        ),
        failure = null,
        durationMillis = 7,
        callsUsed = 2),
    )

    @Test
    fun failedReceiptKeepsTypedFailure() = runBlocking {
        val dir = Files.createTempDirectory("script-receipts-failed").toFile()
        try {
            DatabaseFactory.openLocal(dir.resolve("script.db")).use { database ->
                val store = SqlScriptReceiptStore(database)
                val failed = receipt().copy(
                    factId = "script-ptc:failed:1",
                    taskId = TaskId.parse("failed"),
                    state = ScriptTerminalState.FAILED,
                    content = ScriptReceiptContent(result = null,
                    trace = emptyList(),
                    callsUsed = 0,
                    failure = ScriptFailureRecord(
                        com.deepstudent.services.script.api.ScriptFailureKind.POLICY_VIOLATION,
                        "denied",
                    ), durationMillis = 0),
                )
                store.commit(failed)
                assertEquals(failed, store.findReceipt(TaskId.parse("failed"), 1))
            }
        } finally {
            dir.deleteRecursively()
        }
    }
}
