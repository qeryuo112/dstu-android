package com.deepstudent.services.script.data

import com.deepstudent.core.common.id.TaskId
import com.deepstudent.core.common.json.JsonValue
import com.deepstudent.core.common.logging.LogLevel
import com.deepstudent.core.common.logging.LogRecord
import com.deepstudent.core.common.logging.Logger
import com.deepstudent.core.common.logging.NoopLogger
import com.deepstudent.platform.database.SqlDatabase
import com.deepstudent.platform.database.SqlOperations
import com.deepstudent.platform.database.SqlReadOperations
import com.deepstudent.platform.database.SqlRow
import com.deepstudent.platform.database.SqlValue
import com.deepstudent.services.script.backend.ScriptCompletionOutbox
import com.deepstudent.services.script.backend.ScriptOutboxFact
import com.deepstudent.services.script.backend.ScriptTerminalState
import java.time.Instant

/**
 * Opt-in completion outbox for the script business database.
 *
 * The receipt table stays the only business authority: the outbox holds nothing but a
 * monotonically increasing `seq` and the `fact_id` of the receipt, and every read joins
 * `script_receipts`. A trigger appends the outbox row in the same transaction that inserts
 * the receipt, so a crash cannot leave a receipt without its outbox row or an outbox row
 * without its receipt. Consumers read with a stable sequence cursor; [acknowledge] only
 * advances the cursor over a contiguous acknowledged prefix, so a gap is never skipped.
 *
 * This adapter is not part of the default app composition; the existing
 * [com.deepstudent.services.script.backend.ScriptReceiptStore] pending/ack path keeps working.
 */
class SqlScriptCompletionOutbox(private val database: SqlDatabase, private val logger: Logger = NoopLogger) : ScriptCompletionOutbox {
    init {
        initialize()
    }

    override suspend fun pending(consumerId: String, limit: Int): List<ScriptOutboxFact> {
        require(consumerId.isNotBlank()) { "consumer id must not be blank" }
        require(limit in 1..1000) { "invalid outbox page size" }
        val started = System.nanoTime()
        return database.readSnapshot {
            val cursor = cursorOf(consumerId)
            query(
                "SELECT o.seq, r.fact_id, r.task_id, r.generation, r.state, r.receipt, r.committed_at " +
                    "FROM script_receipt_outbox o JOIN script_receipts r ON r.fact_id = o.fact_id " +
                    "WHERE o.seq > ? AND NOT EXISTS(" +
                    "SELECT 1 FROM script_outbox_acks a WHERE a.consumer_id = ? AND a.fact_id = o.fact_id) " +
                    "ORDER BY o.seq LIMIT ?",
                listOf(integer(cursor), text(consumerId), integer(limit.toLong())),
                ::fact,
            )
        }.also {
            log(RecoveryPhase.PENDING, Outcome.APPLIED, started, JsonValue.obj("count" to JsonValue.integer(it.size.toLong())))
        }
    }

    override suspend fun acknowledge(consumerId: String, factId: String) {
        require(consumerId.isNotBlank() && factId.isNotBlank()) { "invalid outbox acknowledgement" }
        val started = System.nanoTime()
        val advanced = database.transaction {
            // Unknown fact ids are rejected: a consumer must not manufacture acknowledgements.
            val seq = query(
                "SELECT seq FROM script_receipt_outbox WHERE fact_id = ?",
                listOf(text(factId)),
            ) { it.long(0) }.singleOrNull() ?: error("unknown outbox fact")
            execute(
                "INSERT OR IGNORE INTO script_outbox_acks(consumer_id, fact_id, acknowledged_at) " +
                    "VALUES (?, ?, ?)",
                listOf(text(consumerId), text(factId), text(Instant.now().toString())),
            )
            advanceCursor(consumerId, seq)
        }
        // Diagnostics stay outside the transaction; only phase/outcome/timing are recorded.
        log(
            RecoveryPhase.ACK,
            Outcome.APPLIED,
            started,
            JsonValue.obj(
                "cursor_from" to JsonValue.integer(advanced?.first ?: 0L),
                "cursor_to" to JsonValue.integer(advanced?.second ?: 0L),
            ),
        )
    }

    override fun consumerCursor(consumerId: String): Long {
        require(consumerId.isNotBlank()) { "consumer id must not be blank" }
        return database.readSnapshot { cursorOf(consumerId) }
    }

    override suspend fun backfill(): Int {
        val started = System.nanoTime()
        val inserted = database.transaction { backfillLocked() }
        log(RecoveryPhase.BACKFILL, Outcome.APPLIED, started, JsonValue.obj("inserted" to JsonValue.integer(inserted.toLong())))
        return inserted
    }

    private fun SqlReadOperations.cursorOf(consumerId: String): Long = query(
        "SELECT cursor FROM script_outbox_consumers WHERE consumer_id = ?",
        listOf(text(consumerId)),
    ) { it.long(0) }.singleOrNull() ?: 0L

    /**
     * The cursor may only cross a seq once every entry up to and including it is acknowledged.
     * The next value is the seq immediately before the first unacknowledged entry, or the
     * current outbox high-water mark when this consumer has acknowledged every entry.
     * Returns the old/new pair so the caller can log after the transaction commits.
     */
    private fun SqlOperations.advanceCursor(consumerId: String, acknowledgedSeq: Long): Pair<Long, Long>? {
        val cursor = cursorOf(consumerId)
        if (acknowledgedSeq <= cursor) return null
        // The target is a real acknowledged seq strictly below the first unacknowledged one;
        // when every entry is acknowledged it is the current outbox high-water mark.
        val next = query(
            "SELECT COALESCE(MAX(o.seq), ?) FROM script_receipt_outbox o WHERE o.seq < COALESCE(" +
                "(SELECT MIN(u.seq) FROM script_receipt_outbox u WHERE u.seq > ? AND NOT EXISTS(" +
                "SELECT 1 FROM script_outbox_acks a WHERE a.consumer_id = ? AND a.fact_id = u.fact_id)), " +
                "(SELECT COALESCE(MAX(seq), 0) + 1 FROM script_receipt_outbox))",
            listOf(integer(cursor), integer(cursor), text(consumerId)),
        ) { it.long(0) }.single()
        if (next <= cursor) return null
        execute(
            "INSERT INTO script_outbox_consumers(consumer_id, cursor) VALUES (?, ?) " +
                "ON CONFLICT(consumer_id) DO UPDATE SET cursor = excluded.cursor " +
                "WHERE excluded.cursor > script_outbox_consumers.cursor",
            listOf(text(consumerId), integer(next)),
        )
        return cursor to next
    }

    /** Appends only missing receipt facts; an existing fact can never fork a second sequence. */
    private fun SqlOperations.backfillLocked(): Int {
        execute(
            "INSERT INTO script_receipt_outbox(fact_id) " +
                "SELECT r.fact_id FROM script_receipts r WHERE NOT EXISTS(" +
                "SELECT 1 FROM script_receipt_outbox o WHERE o.fact_id = r.fact_id) " +
                "ORDER BY r.committed_at, r.fact_id",
        )
        return query("SELECT changes()") { it.long(0) }.single().toInt()
    }

    private fun initialize() {
        val started = System.nanoTime()
        val inserted = database.transaction {
            val receipts = query(
                "SELECT name FROM sqlite_master WHERE type = 'table' AND name = 'script_receipts'",
            ) { it.text(0) }
            check(receipts.isNotEmpty()) { "script receipts schema must be initialized before the outbox" }
            execute("CREATE TABLE IF NOT EXISTS script_outbox_schema (version INTEGER NOT NULL)")
            val versions = query("SELECT version FROM script_outbox_schema") { it.long(0) }
            check(versions.isEmpty() || versions == listOf(1L)) { "unsupported script outbox schema" }
            if (versions.isEmpty()) {
                execute(OUTBOX)
                execute(ACKS)
                execute(CONSUMERS)
                execute(RECEIPT_TRIGGER)
                execute("INSERT INTO script_outbox_schema(version) VALUES (1)")
            }
            check(
                query("SELECT name FROM pragma_table_info('script_receipt_outbox')") { it.text(0) } ==
                    listOf("seq", "fact_id"),
            ) { "unsupported script outbox layout" }
            check(
                query(
                    "SELECT name FROM sqlite_master WHERE type = 'trigger' AND name = 'script_receipt_outbox_append'",
                ) { it.text(0) }.isNotEmpty(),
            ) { "script outbox trigger is missing" }
            // Receipts committed before the trigger existed are appended here, then read back
            // through the join so a partially linked outbox fails initialization instead of
            // silently losing delivery facts.
            val appended = backfillLocked()
            val receiptCount = query("SELECT COUNT(*) FROM script_receipts") { it.long(0) }.single()
            val outboxCount = query("SELECT COUNT(*) FROM script_receipt_outbox") { it.long(0) }.single()
            val linkedCount = query(
                "SELECT COUNT(*) FROM script_receipt_outbox o JOIN script_receipts r ON r.fact_id = o.fact_id",
            ) { it.long(0) }.single()
            check(receiptCount == outboxCount && receiptCount == linkedCount) {
                "script outbox backfill is not stable"
            }
            appended
        }
        if (inserted > 0) {
            log(RecoveryPhase.BACKFILL, Outcome.APPLIED, started, JsonValue.obj("inserted" to JsonValue.integer(inserted.toLong())))
        }
    }

    private fun fact(row: SqlRow): ScriptOutboxFact = ScriptOutboxFact(
        seq = row.long(0),
        factId = row.text(1),
        taskId = TaskId.parse(row.text(2)),
        generation = row.long(3),
        state = ScriptTerminalState.valueOf(row.text(4)),
        receipt = row.text(5),
        committedAt = Instant.parse(row.text(6)),
    )

    private fun text(value: String): SqlValue = SqlValue.Text(value)
    private fun integer(value: Long): SqlValue = SqlValue.Integer(value)

    /**
     * Structured diagnostics carry only phase, owner, outcome and a monotonic duration.
     * Consumer ids, fact ids and receipt text never reach the log.
     */
    private fun log(phase: RecoveryPhase, outcome: Outcome, startedNanos: Long, fields: JsonValue.Object) {
        try {
            val entries = mutableListOf(
                "phase" to JsonValue.text(phase.name),
                "owner" to JsonValue.text(OWNER),
                "outcome" to JsonValue.text(outcome.name),
                "elapsed_us" to JsonValue.integer((System.nanoTime() - startedNanos) / 1000L),
            )
            entries += fields.asMap().toList()
            logger.log(
                LogRecord(
                    LogLevel.INFO,
                    "script.outbox.$phase",
                    fields = JsonValue.obj(*entries.toTypedArray()),
                ),
            )
        } catch (_: Exception) {
            // Diagnostics never change a committed outbox step.
        }
    }

    private enum class RecoveryPhase { PENDING, ACK, BACKFILL }
    private enum class Outcome { APPLIED, BLOCKED }

    private companion object {
        const val OWNER = "script/outbox"
        val OUTBOX = """
            CREATE TABLE script_receipt_outbox (
                seq INTEGER PRIMARY KEY AUTOINCREMENT,
                fact_id TEXT NOT NULL UNIQUE REFERENCES script_receipts(fact_id)
            )
        """.trimIndent()
        val ACKS = """
            CREATE TABLE script_outbox_acks (
                consumer_id TEXT NOT NULL,
                fact_id TEXT NOT NULL REFERENCES script_receipt_outbox(fact_id),
                acknowledged_at TEXT NOT NULL,
                PRIMARY KEY(consumer_id, fact_id)
            )
        """.trimIndent()
        val CONSUMERS = """
            CREATE TABLE script_outbox_consumers (
                consumer_id TEXT PRIMARY KEY NOT NULL,
                cursor INTEGER NOT NULL CHECK(cursor >= 0)
            )
        """.trimIndent()
        val RECEIPT_TRIGGER = """
            CREATE TRIGGER script_receipt_outbox_append AFTER INSERT ON script_receipts BEGIN
                INSERT OR IGNORE INTO script_receipt_outbox(fact_id) VALUES (NEW.fact_id);
            END
        """.trimIndent()
    }
}
