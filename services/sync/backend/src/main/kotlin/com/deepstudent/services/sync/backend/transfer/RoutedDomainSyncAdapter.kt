package com.deepstudent.services.sync.backend.transfer

import com.deepstudent.core.common.json.JsonValue
import com.deepstudent.services.sync.api.DomainSyncAdapter
import com.deepstudent.services.sync.api.SyncError
import com.deepstudent.services.sync.backend.SyncFailure

class DomainIncomingRoute(
    val database: String,
    val tables: Set<String>,
    val apply: (List<ByteArray>) -> Unit,
)

class RoutedDomainSyncAdapter(
    private val primary: DomainSyncAdapter,
    private val primaryDatabase: String,
    private val primaryTables: Set<String>,
    private val routes: List<DomainIncomingRoute>,
) : DomainSyncAdapter {
    override fun pending(): List<ByteArray> = primary.pending()
    override fun markPublished(changeLogIds: List<Long>, version: Long) = primary.markPublished(changeLogIds, version)
    override fun cursor(device: String): Long = primary.cursor(device)

    override fun apply(device: String, seq: Long, changes: List<ByteArray>) {
        if (seq <= primary.cursor(device)) return
        val classified = changes.map { it to route(it) }
        for (target in routes) {
            val rows = classified.filter { it.second === target }.map { it.first }
            if (rows.isNotEmpty()) target.apply(rows)
        }
        primary.apply(device, seq, classified.filter { it.second == null }.map { it.first })
    }

    private fun route(bytes: ByteArray): DomainIncomingRoute? {
        val value = try { JsonValue.parseObject(bytes.decodeToString(throwOnInvalidSequence = true)) }
        catch (_: Exception) { invalid() }
        val database = (value["database_name"] as? JsonValue.Text)?.value ?: primaryDatabase
        val table = (value["table_name"] as? JsonValue.Text)?.value ?: invalid()
        if (database == primaryDatabase && table in primaryTables) return null
        return routes.singleOrNull { it.database == database && table in it.tables } ?: invalid()
    }

    private fun invalid(): Nothing = throw SyncFailure(SyncError.INCOMPATIBLE_FORMAT)
}
