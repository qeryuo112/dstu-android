package com.deepstudent.services.script.data

import com.deepstudent.core.common.id.TaskId
import com.deepstudent.platform.database.SqlDatabase
import com.deepstudent.platform.database.SqlRow
import com.deepstudent.platform.database.SqlValue
import com.deepstudent.services.script.backend.ScriptCompletion
import com.deepstudent.services.script.backend.ScriptReceipt
import com.deepstudent.services.script.backend.ScriptReceiptCodec
import com.deepstudent.services.script.backend.ScriptReceiptStore
import com.deepstudent.services.script.backend.ScriptTerminalState
import java.time.Instant

/**
 * Owns only script receipts. The caller supplies the database that this service
 * owns; this class never opens or imports the runtime ledger.
 */
class SqlScriptReceiptStore(private val database: SqlDatabase) : ScriptReceiptStore {
    init {
        initialize()
    }

    override suspend fun findReceipt(taskId: TaskId, generation: Long): ScriptReceipt? =
        database.query(
            "SELECT payload FROM script_receipts WHERE task_id = ? AND generation = ?",
            listOf(text(taskId.value), integer(generation)),
        ) { ScriptReceiptCodec.decode(it.text(0)) }.singleOrNull()

    override suspend fun commit(receipt: ScriptReceipt) {
        database.transaction {
            val existing = query(
                "SELECT payload FROM script_receipts WHERE fact_id = ? OR " +
                    "(task_id = ? AND generation = ?)",
                listOf(text(receipt.factId), text(receipt.taskId.value), integer(receipt.generation)),
            ) { ScriptReceiptCodec.decode(it.text(0)) }.singleOrNull()
            if (existing != null) {
                check(existing == receipt) { "script receipt conflict" }
                return@transaction
            }
            execute(
                "INSERT INTO script_receipts(fact_id, task_id, generation, state, receipt, " +
                    "payload, committed_at) VALUES (?, ?, ?, ?, ?, ?, ?)",
                listOf(
                    text(receipt.factId), text(receipt.taskId.value), integer(receipt.generation),
                    text(receipt.state.name), text(receipt.receipt),
                    text(ScriptReceiptCodec.encode(receipt)), text(receipt.committedAt.toString()),
                ),
            )
        }
    }

    override suspend fun pending(consumerId: String, limit: Int): List<ScriptCompletion> {
        require(consumerId.isNotBlank()) { "consumer id must not be blank" }
        require(limit in 1..1000) { "invalid receipt page size" }
        return database.query(
            "SELECT r.fact_id, r.task_id, r.generation, r.state, r.receipt, r.committed_at " +
                "FROM script_receipts r LEFT JOIN script_receipt_acks a ON " +
                "a.fact_id = r.fact_id AND a.consumer_id = ? " +
                "WHERE a.fact_id IS NULL ORDER BY r.committed_at, r.fact_id LIMIT ?",
            listOf(text(consumerId), integer(limit.toLong())),
            ::completion,
        )
    }

    override suspend fun acknowledge(consumerId: String, factId: String) {
        require(consumerId.isNotBlank() && factId.isNotBlank()) { "invalid receipt acknowledgement" }
        database.execute(
            "INSERT OR IGNORE INTO script_receipt_acks(fact_id, consumer_id, acknowledged_at) " +
                "VALUES (?, ?, ?)",
            listOf(text(factId), text(consumerId), text(Instant.now().toString())),
        )
    }

    private fun initialize() {
        database.transaction {
            execute("CREATE TABLE IF NOT EXISTS script_receipt_schema (version INTEGER NOT NULL)")
            val versions = query("SELECT version FROM script_receipt_schema") { it.long(0) }
            check(versions.isEmpty() || versions == listOf(1L)) {
                "unsupported script receipt database version"
            }
            if (versions.isEmpty()) {
                execute(SCHEMA)
                execute(ACKS)
                execute("INSERT INTO script_receipt_schema(version) VALUES (1)")
            }
        }
    }

    private fun completion(row: SqlRow): ScriptCompletion = ScriptCompletion(
        row.text(0),
        TaskId.parse(row.text(1)),
        row.long(2),
        ScriptTerminalState.valueOf(row.text(3)),
        row.text(4),
        Instant.parse(row.text(5)),
    )

    private fun text(value: String): SqlValue = SqlValue.Text(value)
    private fun integer(value: Long): SqlValue = SqlValue.Integer(value)

    private companion object {
        val SCHEMA = """
            CREATE TABLE script_receipts (
                fact_id TEXT PRIMARY KEY NOT NULL,
                task_id TEXT NOT NULL,
                generation INTEGER NOT NULL,
                state TEXT NOT NULL,
                receipt TEXT NOT NULL,
                payload TEXT NOT NULL,
                committed_at TEXT NOT NULL,
                UNIQUE(task_id, generation)
            )
        """.trimIndent()
        val ACKS = """
            CREATE TABLE script_receipt_acks (
                fact_id TEXT NOT NULL REFERENCES script_receipts(fact_id),
                consumer_id TEXT NOT NULL,
                acknowledged_at TEXT NOT NULL,
                PRIMARY KEY(fact_id, consumer_id)
            )
        """.trimIndent()
    }
}
