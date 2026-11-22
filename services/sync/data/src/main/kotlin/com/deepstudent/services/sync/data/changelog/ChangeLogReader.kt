package com.deepstudent.services.sync.data.changelog

import com.deepstudent.core.common.json.JsonValue
import com.deepstudent.core.common.logging.Logger
import com.deepstudent.core.common.logging.NoopLogger
import com.deepstudent.platform.database.SqlDatabase
import com.deepstudent.platform.database.SqlReadOperations
import com.deepstudent.platform.database.SqlValue
import java.util.concurrent.CancellationException

/**
 * Reads log metadata and record images in one database-owned, read-only WAL snapshot.
 * The caller supplies the registry's RowSync membership for this database. No acknowledgement,
 * cursor or row is written here. `id`, not `sync_version` or wall-clock time, orders the pages.
 */
class ChangeLogReader(
    private val database: SqlDatabase,
    private val databaseName: String,
    private val logicalKey: (String) -> List<String>?,
    private val logger: Logger = NoopLogger,
    // The existing Notes adapter published vault clocks even when the log had no delta for that field.
    private val preserveVaultClocks: Boolean = false,
) {
    init { require(databaseName in setOf("vfs", "chat_v2", "mistakes", "llm_usage")) }

    /** The snapshot and its SQL operations are valid only for the duration of [block]. */
    fun <T> withSnapshot(block: (SqlReadOperations, ChangeLogSnapshot) -> T): T {
        val started = System.nanoTime()
        try {
            val result = database.readSnapshot {
                val snapshot = ChangeLogSnapshot(this, databaseName, logicalKey, preserveVaultClocks)
                try { block(this, snapshot) } finally { snapshot.closeScope() }
            }
            diagnostic("complete", started)
            return result
        } catch (cancelled: CancellationException) {
            diagnostic("cancelled", started)
            throw cancelled
        } catch (failure: Exception) {
            diagnostic("failed", started)
            throw failure
        }
    }

    /** Compatibility adapter for the existing list-returning sync port; pages themselves are bounded. */
    fun readAll(
        checkCancelled: () -> Unit = {},
        enrich: (SqlReadOperations, List<ByteArray>) -> List<ByteArray> = { _, changes -> changes },
    ): List<ByteArray> = withSnapshot { sql, snapshot ->
        val changes = mutableListOf<ByteArray>()
        var after: Long? = null
        var more = true
        while (more) {
            checkCancelled()
            val page = snapshot.page(after)
            changes.addAll(page.changes)
            after = page.nextAfterId
            more = page.hasMore
        }
        checkCancelled()
        enrich(sql, changes)
    }

    private fun diagnostic(status: String, started: Long) {
        logger.info("sync.change_log.read", JsonValue.Object.Builder().put("owner", "sync/changelog")
            .put("database", databaseName).put("status", status)
            .put("elapsedMs", (System.nanoTime() - started) / 1_000_000).build())
    }
}

/** A page advances over log ids (including deletes), not over domain-enrichment records. */
data class ChangeLogPage(val changes: List<ByteArray>, val nextAfterId: Long?, val hasMore: Boolean)

class ChangeLogSnapshot internal constructor(
    private val sql: SqlReadOperations,
    private val databaseName: String,
    private val logicalKey: (String) -> List<String>?,
    preserveVaultClocks: Boolean,
) {
    private val owner = Thread.currentThread()
    private var active = true
    private val highWater = sql.query("SELECT MAX(id) FROM __change_log") { if (it.isNull(0)) null else it.long(0) }.single()
    val highWaterId: Long? get() { requireScope(); return highWater }
    private val fieldDeltas = sql.query("SELECT name FROM pragma_table_info('__change_log')") { it.text(0) }
        .contains("field_deltas_json")
    private val images = ChangeRowImages(sql, preserveVaultClocks)

    /** A null cursor starts at the first signed SQLite id. Limits bound SQL and materialization per page. */
    fun page(afterId: Long? = null, limit: Int = DEFAULT_PAGE_SIZE): ChangeLogPage {
        requireScope()
        require(limit in 1..MAX_PAGE_SIZE)
        val high = highWater ?: return ChangeLogPage(emptyList(), afterId, false)
        val args = mutableListOf<SqlValue>(SqlValue.Integer(high))
        val after = if (afterId == null) "" else " AND id>?".also { args += SqlValue.Integer(afterId) }
        args += SqlValue.Integer((limit + 1).toLong())
        val delta = if (fieldDeltas) "field_deltas_json" else "NULL"
        val rows = sql.query("SELECT id,table_name,record_id,operation,changed_at,$delta FROM __change_log " +
            "WHERE COALESCE(sync_version,0)=0 AND id<=?$after ORDER BY id LIMIT ?", args) { row ->
            ChangeLogEntry(row.long(0), row.text(1), row.text(2), row.text(3), row.text(4),
                if (row.isNull(5)) null else row.text(5))
        }
        val selected = rows.take(limit)
        val changes = selected.map { entry ->
            val keys = requireNotNull(logicalKey(entry.table)) { "change log table is not registered for RowSync" }
            images.encode(databaseName, entry, keys)
        }
        return ChangeLogPage(changes, selected.lastOrNull()?.id ?: afterId, rows.size > limit)
    }

    internal fun closeScope() { active = false }
    private fun requireScope() { check(active && Thread.currentThread() === owner) { "change log snapshot is out of scope" } }

    companion object {
        const val DEFAULT_PAGE_SIZE: Int = 128
        const val MAX_PAGE_SIZE: Int = 500
    }
}

internal data class ChangeLogEntry(
    val id: Long,
    val table: String,
    val recordId: String,
    val operation: String,
    val changedAt: String,
    val fieldDeltas: String?,
)
