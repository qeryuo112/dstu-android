package com.deepstudent.services.sync.data.changelog

import com.deepstudent.core.common.json.JsonValue
import com.deepstudent.core.common.logging.Logger
import com.deepstudent.core.common.logging.NoopLogger
import com.deepstudent.platform.database.SharedDatabase
import com.deepstudent.platform.database.SqlDatabase
import com.deepstudent.platform.database.SqlReadOperations
import com.deepstudent.platform.database.SqlValue
import com.deepstudent.services.sync.api.SyncChangeReceipt
import com.deepstudent.services.sync.api.SyncError
import com.deepstudent.services.sync.backend.SyncFailure
import com.deepstudent.services.sync.backend.SyncObjectLimits
import com.deepstudent.services.sync.backend.transfer.RepositorySeed
import com.deepstudent.services.sync.backend.transfer.SeedPublication
import java.time.Instant

/** Uses only the effective frozen RowSync table/key set passed by the production registry. */
class SqlPublicationSource(
    private val database: SqlDatabase,
    val scope: SharedDatabase,
    private val keys: Map<String, List<String>>,
    private val check: () -> Unit = {},
    private val logger: Logger = NoopLogger,
) {
    fun pending(enrich: (SqlReadOperations, List<ByteArray>) -> List<ByteArray> = { _, rows -> rows }): List<ByteArray> = ChangeLogReader(
        database,
        scope.id,
        keys::get,
        logger,
        preserveVaultClocks = true,
        rowSyncOnly = true,
    ).readAll(check, enrich)

    fun acknowledge(ids: List<Long>, version: Long) {
        require(version > 0)
        database.transaction {
            for (id in ids) {
                check()
                execute(
                    "UPDATE __change_log SET sync_version=? WHERE id=? AND COALESCE(sync_version,0)=0",
                    listOf(SqlValue.Integer(version), SqlValue.Integer(id)),
                )
            }
        }
        logger.info(
            "sync.publication.acknowledged",
            JsonValue.Object.Builder().put("owner", "sync")
                .put("database", scope.id).put("stage", "ACKNOWLEDGE").put("count", ids.size).build(),
        )
    }

    internal fun seed(changes: BoundedPublicationRows): List<SyncChangeReceipt> = database.readSnapshot {
        val reader = ChangeRowImages(this, preserveVaultClocks = true)
        val now = Instant.now().toString()
        for ((table, columns) in keys.toSortedMap()) {
            var offset = 0L
            while (true) {
                check()
                val records = seedKeys(table, columns, offset)
                for (id in records) {
                    check()
                    val row = JsonValue.parseObject(reader.seed(table, id, columns, now).decodeToString())
                    changes.add(JsonValue.Object.from(row.asMap() + ("database_name" to JsonValue.text(scope.id))).literal().toByteArray())
                }
                if (records.size < SEED_PAGE) break
                offset += records.size
            }
        }
        // These ids and the inventory share a read snapshot. Later writes stay pending.
        if (keys.isEmpty()) return@readSnapshot emptyList()
        val filter = keys.keys.joinToString(",") { "?" }
        val args = keys.keys.map(SqlValue::Text) + SqlValue.Integer(MAX_RECEIPTS + 1)
        query("SELECT id FROM __change_log WHERE COALESCE(sync_version,0)=0 AND table_name IN ($filter) ORDER BY id LIMIT ?", args) {
            check()
            SyncChangeReceipt(scope.id, it.long(0))
        }.also { if (it.size > MAX_RECEIPTS) throw SyncFailure(SyncError.LIMIT_EXCEEDED) }
    }
}

/** No fallback to pending collection if the complete inventory doesn't fit the foundation bound. */
class SqlRepositorySeed(private val sources: List<SqlPublicationSource>) : RepositorySeed {
    init {
        require(sources.size == SharedDatabase.entries.size && sources.map { it.scope }.toSet() == SharedDatabase.entries.toSet())
    }

    override fun snapshot(): SeedPublication {
        val changes = BoundedPublicationRows()
        val receipts = sources.flatMap { it.seed(changes) }
        if (receipts.size > MAX_RECEIPTS) throw SyncFailure(SyncError.LIMIT_EXCEEDED)
        return SeedPublication(changes.rows.toList(), receipts)
    }
}

internal class BoundedPublicationRows {
    val rows = mutableListOf<ByteArray>()
    private var length = 0L

    fun add(row: ByteArray) {
        length += row.size + 1L
        if (length > SyncObjectLimits.PAYLOAD_BYTES) throw SyncFailure(SyncError.LIMIT_EXCEEDED)
        rows += row
    }
}

private fun SqlReadOperations.seedKeys(table: String, columns: List<String>, offset: Long): List<String> {
    require(columns.isNotEmpty())
    val quoted = columns.joinToString(",", transform = ::quoteIdentifier)
    val select = columns.joinToString(",") { "CAST(${quoteIdentifier(it)} AS TEXT)" }
    // Match the S0.21 capture trigger: unchanged bundled templates stay local even on first sync.
    val eligible = if (table == "custom_anki_templates") " WHERE is_built_in=0 OR user_modified=1 OR user_deleted=1" else ""
    return query(
        "SELECT $select FROM ${quoteIdentifier(table)}$eligible ORDER BY $quoted LIMIT $SEED_PAGE OFFSET ?",
        listOf(SqlValue.Integer(offset)),
    ) {
        if (columns.size == 1) {
            it.text(0)
        } else {
            JsonValue.Object.from(columns.mapIndexed { index, name -> name to JsonValue.text(it.text(index)) }.toMap()).literal()
        }
    }
}

private const val SEED_PAGE = 128
private const val MAX_RECEIPTS = 100_000L
