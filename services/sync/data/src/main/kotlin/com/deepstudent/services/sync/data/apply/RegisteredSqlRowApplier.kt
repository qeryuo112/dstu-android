package com.deepstudent.services.sync.data.apply

import com.deepstudent.core.common.json.JsonValue
import com.deepstudent.platform.database.SqlOperations
import com.deepstudent.platform.database.SqlReadOperations
import com.deepstudent.platform.database.SqlValue
import com.deepstudent.services.sync.data.changelog.quoteIdentifier
import com.deepstudent.services.sync.data.changelog.tableMetadata

/**
 * Basic LWW row owner for explicitly registered, migrated RowSync tables. Special domain invariants
 * (assets, immutable events, derived recomputation and business-key aliases) require their own owner.
 */
class RegisteredSqlRowApplier(private val registration: RegisteredIncomingRows) {
    fun validate(rows: List<ByteArray>) = registration.validate(rows)

    fun apply(sql: SqlOperations, rows: List<ByteArray>, checkCancelled: () -> Unit = {}) {
        val decoded = rows.map {
            checkCancelled()
            registration.decode(it)
        }
        val deleted = decoded.filter { it.operation == "DELETE" }.map { it.table to it.id }.toSet()
        val complete = decoded.filter { row ->
            if (row.operation != "DELETE" && row.data == null) {
                require(row.table to row.id in deleted)
                false
            } else {
                true
            }
        }
        sql.execute(
            "CREATE TABLE IF NOT EXISTS __android_sync_row_versions(" +
                "table_name TEXT NOT NULL,record_id TEXT NOT NULL,changed_at TEXT NOT NULL,device TEXT NOT NULL," +
                "seq INTEGER NOT NULL,deleted INTEGER NOT NULL,PRIMARY KEY(table_name,record_id))",
        )
        incomingOrder(complete).forEach { row ->
            checkCancelled()
            sql.incomingApplyRow(row, registration.policy(row.table))
        }
    }
    private fun incomingOrder(rows: List<IncomingRow>): List<IncomingRow> {
        val sorted = rows.sortedBy(registration::rank)
        val index = sorted.withIndex().filter { it.value.operation != "DELETE" }
            .associate { (position, row) -> (row.table to row.id) to position }
        val emitted = mutableSetOf<Int>()
        val visiting = mutableSetOf<Int>()
        val result = mutableListOf<IncomingRow>()
        fun emit(position: Int) {
            if (position in emitted) return
            require(visiting.add(position)) { "cyclic parent references" }
            val row = sorted[position]
            if (row.operation != "DELETE") {
                val parent = (row.data?.get("parent_id") as? JsonValue.Text)?.value
                val dependency = index[row.table to parent]
                if (dependency != null && dependency != position) emit(dependency)
            }
            visiting.remove(position)
            if (emitted.add(position)) result += row
        }
        sorted.indices.forEach(::emit)
        return result
    }
}

private fun SqlOperations.incomingApplyRow(row: IncomingRow, policy: IncomingRowPolicy?) {
    val metadata = tableMetadata(this, row.table, row.keys)
    val local = incomingRead(row, metadata.columns)
    policy?.verify(this, row.operation, row.data, local)
    if (incomingStale(row, local)) return
    if (row.operation == "DELETE") {
        incomingDelete(row, metadata.columns)
    } else {
        incomingUpsert(row, metadata.columns, local, policy)
    }
    execute(
        "INSERT INTO __android_sync_row_versions(table_name,record_id,changed_at,device,seq,deleted) VALUES(?,?,?,?,?,?) " +
            "ON CONFLICT(table_name,record_id) DO UPDATE SET changed_at=excluded.changed_at," +
            "device=excluded.device,seq=excluded.seq,deleted=excluded.deleted",
        listOf(
            SqlValue.Text(row.table),
            SqlValue.Text(row.id),
            SqlValue.Text(incomingTimestamp(row)),
            SqlValue.Text(row.source),
            SqlValue.Integer(row.seq),
            SqlValue.Integer(if (row.operation == "DELETE") 1 else 0),
        ),
    )
}

private fun SqlReadOperations.incomingRead(row: IncomingRow, columns: List<String>): JsonValue.Object? {
    val names = columns.joinToString(",") { quoteIdentifier(it) }
    val types = columns.joinToString(",") { "typeof(${quoteIdentifier(it)})" }
    val predicate = row.keys.joinToString(" AND ") { "${quoteIdentifier(it)}=?" }
    val statement = "SELECT $names,$types FROM ${quoteIdentifier(row.table)} WHERE $predicate LIMIT 2"
    val rows = query(statement, row.keyValues.map(SqlValue::Text)) { result ->
        JsonValue.Object.from(
            columns.mapIndexed { i, name ->
                val value = when (result.text(i + columns.size)) {
                    "null" -> JsonValue.Null
                    "integer" -> JsonValue.integer(result.long(i))
                    "real" -> JsonValue.Number.raw(result.double(i).toString())
                    "text" -> JsonValue.text(result.text(i))
                    "blob" -> JsonValue.obj("\$dsblob" to JsonValue.text(java.util.Base64.getEncoder().encodeToString(result.blob(i))))
                    else -> error("invalid SQLite type")
                }
                name to value
            }.toMap(),
        )
    }
    require(rows.size <= 1)
    return rows.singleOrNull()
}

private fun SqlReadOperations.incomingStale(row: IncomingRow, local: JsonValue.Object?): Boolean {
    val previous = query(
        "SELECT changed_at,device,seq,deleted FROM __android_sync_row_versions WHERE table_name=? AND record_id=?",
        listOf(SqlValue.Text(row.table), SqlValue.Text(row.id)),
    ) {
        IncomingVersion(it.text(0), it.text(1), it.long(2), it.long(3) != 0L)
    }.singleOrNull()
    if (incomingDeleteBlocks(row, previous)) return true
    if (local == null) return false
    val localTime = incomingTimestamp(local["updated_at"]) ?: return false
    val remoteTime = if (row.operation == "DELETE") row.changedAt else incomingTimestamp(row.data?.get("updated_at")) ?: return false
    val localDevice = (local["device_id"] as? JsonValue.Text)?.value.orEmpty()
    val remoteDevice = row.source.ifBlank { (row.data?.get("device_id") as? JsonValue.Text)?.value.orEmpty() }
    val devices = if (localDevice.isNotBlank() && remoteDevice.isNotBlank()) localDevice to remoteDevice else "" to ""
    return incomingCompare(localTime, devices.first, local.canonical(), remoteTime, devices.second, row.data?.canonical().orEmpty()) > 0
}

private fun incomingDeleteBlocks(row: IncomingRow, previous: IncomingVersion?): Boolean {
    if (previous == null || !previous.deleted) return false
    val order = incomingCompare(incomingTimestamp(row), row.source, "", previous.time, previous.device, "")
    return order < 0 || (order == 0 && row.seq <= previous.seq)
}

private data class IncomingVersion(val time: String, val device: String, val seq: Long, val deleted: Boolean)
private fun incomingTimestamp(row: IncomingRow): String = incomingTimestamp(row.data?.get("updated_at")) ?: row.changedAt
private fun incomingTimestamp(value: JsonValue?): String? = when (value) {
    is JsonValue.Text -> value.value
    is JsonValue.Number -> value.literal
    else -> null
}

private fun SqlOperations.incomingDelete(row: IncomingRow, columns: List<String>) {
    val predicate = row.keys.joinToString(" AND ") { "${quoteIdentifier(it)}=?" }
    val args = row.keyValues.map(SqlValue::Text)
    if ("deleted_at" in columns) {
        execute("UPDATE ${quoteIdentifier(row.table)} SET deleted_at=? WHERE $predicate", listOf(SqlValue.Text(row.changedAt)) + args)
    } else {
        execute("DELETE FROM ${quoteIdentifier(row.table)} WHERE $predicate", args)
    }
}

private fun SqlOperations.incomingUpsert(row: IncomingRow, columns: List<String>, local: JsonValue.Object?, policy: IncomingRowPolicy?) {
    val source = requireNotNull(row.data)
    val incoming = (policy?.merge(source, local) ?: source).asMap()
    val data = incoming.filterKeys { it in columns }.toMutableMap()
    if (local != null && query(
            "SELECT 1 FROM __change_log WHERE table_name=? AND record_id=? AND COALESCE(sync_version,0)=0 LIMIT 1",
            listOf(SqlValue.Text(row.table), SqlValue.Text(row.id)),
        ) { true }.isNotEmpty()
    ) {
        IncomingFieldMerge.merge(row.table, local, data)
    }
    require(data.isNotEmpty())
    incomingVault(row.table, row.id, incoming.filterKeys { it !in columns })
    val names = data.keys.map(::quoteIdentifier)
    val assignments = data.keys.filterNot { it in row.keys }.joinToString(",") { "${quoteIdentifier(it)}=excluded.${quoteIdentifier(it)}" }
    val action = if (assignments.isEmpty()) "DO NOTHING" else "DO UPDATE SET $assignments"
    val conflict = row.keys.joinToString(",") { quoteIdentifier(it) }
    execute(
        "INSERT INTO ${quoteIdentifier(row.table)} (${names.joinToString(",")}) VALUES (${names.joinToString(",") { "?" }}) " +
            "ON CONFLICT($conflict) $action",
        data.values.map(::incomingValue),
    )
}
