package com.deepstudent.services.sync.data.apply

import com.deepstudent.core.common.json.JsonValue
import com.deepstudent.platform.database.SharedDatabase
import com.deepstudent.platform.database.SqlValue
import com.deepstudent.services.sync.data.capture.SyncCaptureRegistry
import com.deepstudent.services.sync.data.changelog.parseRecordKey
import java.util.Base64
import java.util.Locale

/** A domain explicitly registers tables; every entry is checked against the frozen RowSync contract. */
class IncomingDomain(val owner: SharedDatabase, val name: String, tables: List<String>) {
    val tables = tables.toList()
    init {
        require(name.isNotBlank() && this.tables.isNotEmpty() && this.tables.distinct().size == this.tables.size)
    }
}

class RegisteredIncomingRows(
    private val owner: SharedDatabase,
    registry: SyncCaptureRegistry,
    domains: List<IncomingDomain>,
    private val policies: Map<String, IncomingRowPolicy> = emptyMap(),
) {
    private val keys = registry.rowSyncKeys(owner)
    private val orderedTables = domains.flatMap { domain ->
        require(domain.owner == owner)
        domain.tables
    }
    val tables: Set<String> = orderedTables.toSet()
    private val ranks = orderedTables.withIndex().associate { it.value to it.index }
    internal fun rank(row: IncomingRow): Int {
        val order = ranks.getValue(row.table)
        return if (row.operation == "DELETE") 10000 - order else order
    }
    init {
        require(domains.isNotEmpty() && domains.sumOf { it.tables.size } == tables.size)
        require(tables.all { it in keys } && policies.keys.all { it in tables })
    }

    internal fun decode(bytes: ByteArray): IncomingRow {
        val root = JsonValue.parseObject(bytes.decodeToString(throwOnInvalidSequence = true))
        fun text(name: String) = (root[name] as? JsonValue.Text)?.value ?: error("invalid sync row")
        val database = (root["database_name"] as? JsonValue.Text)?.value ?: SharedDatabase.VFS.id
        val table = text("table_name")
        require(database == owner.id && table in tables)
        val header = decodeHeader(root)
        val id = header.id
        val operation = header.operation
        val changedAt = header.changedAt
        val data = decodeData(root["data"])
        val keyColumns = keys.getValue(table)
        val keyValues = parseRecordKey(table, id, keyColumns)
        validateKeys(operation, data, keyColumns, keyValues)
        data?.asMap()?.values?.forEach(::incomingValue)
        policies[table]?.validate(operation, data)
        val source = (root["source_device_id"] as? JsonValue.Text)?.value.orEmpty()
        val seq = (root["source_seq"] as? JsonValue.Number)?.asLongOrNull() ?: 0L
        require(seq >= 0)
        return IncomingRow(table, id, operation, changedAt, data, source, seq, IncomingKeys(keyColumns, keyValues))
    }

    fun validate(rows: List<ByteArray>) {
        rows.forEach(::decode)
    }
    internal fun policy(table: String): IncomingRowPolicy? = policies[table]
}

private data class IncomingHeader(val id: String, val operation: String, val changedAt: String)
private fun decodeHeader(root: JsonValue.Object): IncomingHeader {
    fun text(name: String) = (root[name] as? JsonValue.Text)?.value ?: error("invalid sync row")
    val id = text("record_id")
    val operation = text("operation").uppercase(Locale.ROOT)
    val changedAt = text("changed_at")
    require(id.isNotBlank() && '\u0000' !in id && changedAt.isNotBlank())
    require(operation in setOf("INSERT", "UPDATE", "DELETE"))
    return IncomingHeader(id, operation, changedAt)
}

private fun decodeData(value: JsonValue?): JsonValue.Object? = when (value) {
    null, JsonValue.Null -> null
    is JsonValue.Object -> value
    else -> error("invalid sync data")
}

private fun validateKeys(operation: String, data: JsonValue.Object?, columns: List<String>, values: List<String>) {
    if (data == null || operation == "DELETE") return
    columns.zip(values).forEach { (column, key) ->
        val value = data[column] ?: error("missing sync key")
        require(incomingKeyString(value) == key)
    }
}

internal data class IncomingRow(
    val table: String,
    val id: String,
    val operation: String,
    val changedAt: String,
    val data: JsonValue.Object?,
    val source: String,
    val seq: Long,
    val key: IncomingKeys,
) {
    val keys get() = key.columns
    val keyValues get() = key.values
}
internal data class IncomingKeys(val columns: List<String>, val values: List<String>)

private fun incomingKeyString(value: JsonValue): String = when (value) {
    is JsonValue.Text -> value.value
    is JsonValue.Number -> value.literal
    is JsonValue.Bool -> value.value.toString()
    else -> value.literal()
}

internal fun incomingValue(value: JsonValue): SqlValue = when (value) {
    JsonValue.Null -> SqlValue.Null
    is JsonValue.Bool -> SqlValue.Integer(if (value.value) 1 else 0)
    is JsonValue.Text -> SqlValue.Text(value.value)
    is JsonValue.Number -> incomingNumber(value)
    is JsonValue.Array -> SqlValue.Text(value.literal())
    is JsonValue.Object -> incomingObject(value)
}

private fun incomingNumber(value: JsonValue.Number): SqlValue = if (value.isIntegerLiteral()) {
    SqlValue.Integer(requireNotNull(value.asLongOrNull()))
} else {
    SqlValue.Real(value.literal.toDouble().also { require(it.isFinite()) })
}

private fun incomingObject(value: JsonValue.Object): SqlValue {
    val blob = value["\$dsblob"] as? JsonValue.Text
    return if (value.size == 1 && blob != null) SqlValue.Blob(Base64.getDecoder().decode(blob.value)) else SqlValue.Text(value.literal())
}
