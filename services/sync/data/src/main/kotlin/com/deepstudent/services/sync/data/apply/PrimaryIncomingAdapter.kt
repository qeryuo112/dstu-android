package com.deepstudent.services.sync.data.apply

import com.deepstudent.core.common.json.JsonValue
import com.deepstudent.platform.database.SqlValue
import com.deepstudent.services.sync.api.DomainSyncAdapter

/** Combines the existing primary owner and explicitly registered companion rows in one transaction. */
class PrimaryIncomingAdapter(
    private val primary: DomainSyncAdapter,
    private val database: IncomingTransactionDatabase,
    private val primaryTables: Set<String>,
    private val companion: RegisteredSqlRowApplier,
    private val checkCancelled: () -> Unit = {},
) : DomainSyncAdapter by primary {
    override fun apply(device: String, seq: Long, changes: List<ByteArray>) {
        val (primaryRows, companionRows) = changes.partition { bytes ->
            val row = JsonValue.parseObject(bytes.decodeToString(throwOnInvalidSequence = true))
            val table = (row["table_name"] as? JsonValue.Text)?.value ?: error("invalid sync table")
            table in primaryTables
        }
        companion.validate(companionRows)
        database.transaction {
            val before = query("SELECT COALESCE(MAX(id),0) FROM __change_log") { it.long(0) }.single()
            checkCancelled()
            primary.apply(device, seq, primaryRows)
            checkCancelled()
            companion.apply(this, companionRows, checkCancelled)
            checkCancelled()
            execute("UPDATE __change_log SET sync_version=1 WHERE id>? AND COALESCE(sync_version,0)=0", listOf(SqlValue.Integer(before)))
            checkCancelled()
        }
    }
}
