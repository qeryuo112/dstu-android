package com.deepstudent.services.sync.data.apply

import com.deepstudent.core.common.id.CorrelationId
import com.deepstudent.core.common.json.JsonValue
import com.deepstudent.core.common.logging.LogLevel
import com.deepstudent.core.common.logging.LogRecord
import com.deepstudent.core.common.logging.Logger
import com.deepstudent.core.common.logging.NoopLogger
import com.deepstudent.platform.database.SharedDatabase
import com.deepstudent.platform.database.SqlDatabase
import com.deepstudent.platform.database.SqlOperations
import com.deepstudent.platform.database.SqlValue
import com.deepstudent.services.sync.api.DomainSyncAdapter
import com.deepstudent.services.sync.api.SyncChangeReceipt
import com.deepstudent.services.sync.api.SyncError
import com.deepstudent.services.sync.backend.SyncFailure
import com.deepstudent.services.sync.data.capture.SyncCaptureRegistry
import java.util.UUID
import java.util.concurrent.CancellationException

/**
 * An explicitly registered non-primary database owner. [applyRows] must perform all effects using
 * the supplied transaction; it must not open another transaction on the underlying database.
 * [validateRows] runs for the whole batch before any owner is committed.
 */
class SqlIncomingOwner(
    val database: SharedDatabase,
    val open: () -> SqlDatabase,
    tables: Set<String>,
    val validateRows: (List<ByteArray>) -> Unit = {},
    val applyRows: SqlOperations.(List<ByteArray>) -> Unit,
) {
    val tables: Set<String> = tables.toSet()
}

class IncomingApplyCallbacks(
    val validatePrimary: (List<ByteArray>) -> Unit = {},
    val checkCancelled: () -> Unit = {},
    val logger: Logger = NoopLogger,
)

/**
 * Coordinates independent database commits without claiming a cross-database transaction.
 * The primary adapter must apply its rows and consumer cursor atomically. A caller must hold the
 * existing process/repository lease across the run: the monitor only serializes this instance.
 */
class IncomingBatchCoordinator(
    private val primary: DomainSyncAdapter,
    private val primaryDatabase: SharedDatabase,
    primaryTables: Set<String>,
    owners: List<SqlIncomingOwner>,
    registry: SyncCaptureRegistry,
    private val instance: String,
    private val callbacks: IncomingApplyCallbacks = IncomingApplyCallbacks(),
) : DomainSyncAdapter {
    private val validatePrimary get() = callbacks.validatePrimary
    private val checkCancelled get() = callbacks.checkCancelled
    private val logger get() = callbacks.logger
    private val primaryTables = primaryTables.toSet()
    private val owners = owners.toList()
    private val monitor = Any()

    init {
        require(instance.isNotBlank() && this.primaryTables.isNotEmpty())
        require(this.owners.map { it.database }.distinct().size == this.owners.size)
        require(this.owners.none { it.database == primaryDatabase })
        require(this.primaryTables.all { it in registry.rowSyncKeys(primaryDatabase) })
        this.owners.forEach { owner ->
            require(owner.tables.isNotEmpty() && owner.tables.all { it in registry.rowSyncKeys(owner.database) })
        }
    }

    override fun pending(): List<ByteArray> = primary.pending()
    override fun markPublished(changeLogIds: List<Long>, version: Long) = primary.markPublished(changeLogIds, version)
    override fun markPublishedReceipts(receipts: List<SyncChangeReceipt>, version: Long) = primary.markPublishedReceipts(receipts, version)
    override fun cursor(device: String): Long = primary.cursor(device)

    override fun apply(device: String, seq: Long, changes: List<ByteArray>) =
        applyWithCorrelation(device, seq, changes, UUID.randomUUID().toString())

    override fun applyWithCorrelation(device: String, seq: Long, changes: List<ByteArray>, correlationId: String) = synchronized(monitor) {
        val correlation = CorrelationId.parse(correlationId)
        require(device.isNotBlank() && seq > 0)
        val cursor = primary.cursor(device)
        require(cursor >= 0)
        if (seq <= cursor) {
            report("sync.apply.consume", primaryDatabase, "CONSUME", "REPLAYED", seq, correlation, System.nanoTime())
            return@synchronized
        }
        require(cursor != Long.MAX_VALUE && seq == cursor + 1) { "noncontiguous sync batch" }
        checkCancelled()
        val stable = changes.map { it.copyOf() }
        val routed = classify(stable)
        validatePrimary(routed[primaryDatabase].orEmpty().map { it.copyOf() })
        owners.forEach {
            checkCancelled()
            it.validateRows(routed[it.database].orEmpty().map { bytes -> bytes.copyOf() })
        }
        val digest = incomingBatchDigest(stable)
        val opened = owners.associateWith { it.open() }
        // Check even owners absent from this retry: a changed batch must not omit an earlier commit.
        owners.forEach { owner ->
            checkCancelled()
            opened.getValue(owner).transaction {
                incomingReceiptSetup()
                incomingVerifyReceipt(instance, device, seq, digest)
            }
        }
        owners.forEach { owner ->
            val rows = routed[owner.database].orEmpty()
            if (rows.isNotEmpty()) commitOwner(owner, opened.getValue(owner), rows, device, seq, digest, correlation)
        }
        consume(device, seq, routed[primaryDatabase].orEmpty(), correlation)
    }

    private fun classify(changes: List<ByteArray>): Map<SharedDatabase, List<ByteArray>> {
        val result = mutableMapOf<SharedDatabase, MutableList<ByteArray>>()
        val deletes = mutableSetOf<Triple<SharedDatabase, String, String>>()
        val incomplete = mutableSetOf<Triple<SharedDatabase, String, String>>()
        changes.forEach { bytes ->
            checkCancelled()
            val (db, table, id, operation, data) = classifyRow(bytes)
            val key = Triple(db, table, id)
            if (operation == "DELETE") {
                deletes += key
            } else if (data == null || data == JsonValue.Null) {
                incomplete += key
            } else if (data !is JsonValue.Object) {
                invalid()
            }
            result.getOrPut(db) { mutableListOf() }.add(bytes)
        }
        if (!deletes.containsAll(incomplete)) invalid()
        return result
    }

    private fun classifyRow(bytes: ByteArray): RoutedRow {
        val row = parseRoutedJson(bytes)
        val db = incomingDatabase(row["database_name"])
        val table = (row["table_name"] as? JsonValue.Text)?.value ?: invalid()
        val id = (row["record_id"] as? JsonValue.Text)?.value ?: invalid()
        val operation = (row["operation"] as? JsonValue.Text)?.value?.uppercase(java.util.Locale.ROOT) ?: invalid()
        val changedAt = (row["changed_at"] as? JsonValue.Text)?.value ?: invalid()
        validateRoutedIdentity(id, changedAt, operation)
        if (!isAllowed(db, table)) invalid()
        return RoutedRow(db, table, id, operation, row["data"])
    }

    private fun validateRoutedIdentity(id: String, changedAt: String, operation: String) {
        if (id.isBlank() || '\u0000' in id || changedAt.isBlank() || operation !in setOf("INSERT", "UPDATE", "DELETE")) invalid()
    }

    private fun parseRoutedJson(bytes: ByteArray): JsonValue.Object = try {
        JsonValue.parseObject(bytes.decodeToString(throwOnInvalidSequence = true))
    } catch (cancelled: CancellationException) {
        throw cancelled
    } catch (_: Exception) {
        invalid()
    }

    private fun incomingDatabase(value: JsonValue?): SharedDatabase {
        if (value == null || value == JsonValue.Null) return primaryDatabase
        return SharedDatabase.entries.singleOrNull { JsonValue.text(it.id) == value } ?: invalid()
    }

    private fun isAllowed(database: SharedDatabase, table: String): Boolean = if (database == primaryDatabase) {
        table in primaryTables
    } else {
        owners.singleOrNull { it.database == database }?.tables?.contains(table) == true
    }

    private fun commitOwner(
        owner: SqlIncomingOwner,
        db: SqlDatabase,
        rows: List<ByteArray>,
        device: String,
        seq: Long,
        digest: String,
        correlation: CorrelationId,
    ) {
        val started = System.nanoTime()
        var status = "FAILED"
        try {
            val replayed = db.transaction {
                checkCancelled()
                incomingReceiptSetup()
                val previous = incomingVerifyReceipt(instance, device, seq, digest)
                if (!previous) {
                    val before = query("SELECT COALESCE(MAX(id),0) FROM __change_log") { it.long(0) }.single()
                    owner.applyRows(this, rows.map { it.copyOf() })
                    checkCancelled()
                    execute(
                        "UPDATE __change_log SET sync_version=1 WHERE id>? AND COALESCE(sync_version,0)=0",
                        listOf(SqlValue.Integer(before)),
                    )
                    incomingInsertReceipt(instance, device, seq, digest)
                }
                checkCancelled()
                previous
            }
            status = if (replayed) "REPLAYED" else "COMMITTED"
        } catch (cancelled: CancellationException) {
            status = "CANCELLED"
            throw cancelled
        } finally {
            report("sync.apply.owner", owner.database, "APPLY", status, seq, correlation, started)
        }
    }

    private fun consume(device: String, seq: Long, rows: List<ByteArray>, correlation: CorrelationId) {
        val started = System.nanoTime()
        var status = "FAILED"
        try {
            checkCancelled()
            primary.apply(device, seq, rows)
            check(primary.cursor(device) >= seq) { "primary did not commit the consumer cursor" }
            status = "COMMITTED"
        } catch (cancelled: CancellationException) {
            status = "CANCELLED"
            throw cancelled
        } finally {
            report("sync.apply.consume", primaryDatabase, "CONSUME", status, seq, correlation, started)
        }
    }

    private fun report(
        event: String,
        db: SharedDatabase,
        stage: String,
        status: String,
        seq: Long,
        correlation: CorrelationId,
        started: Long,
    ) {
        val fields = JsonValue.Object.Builder().put("owner", "sync/apply")
            .put("phase", "DOWNLOAD").put("stage", stage).put("database", db.id)
            .put("status", status).put("sequence", seq)
            .put("elapsedMs", (System.nanoTime() - started) / 1_000_000).build()
        try {
            logger.log(LogRecord(LogLevel.INFO, event, correlationId = correlation, fields = fields))
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (_: Exception) {
            // Diagnostic failures must not replace the synchronization result.
        }
    }

    private fun invalid(): Nothing = throw SyncFailure(SyncError.INCOMPATIBLE_FORMAT)
}

private data class RoutedRow(val database: SharedDatabase, val table: String, val id: String, val operation: String, val data: JsonValue?)
