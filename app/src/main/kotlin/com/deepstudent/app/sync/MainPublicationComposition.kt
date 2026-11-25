package com.deepstudent.app.sync

import com.deepstudent.core.common.json.JsonValue
import com.deepstudent.core.common.logging.Logger
import com.deepstudent.features.notes.data.SqlNotesSyncAdapter
import com.deepstudent.platform.database.SharedDatabase
import com.deepstudent.platform.database.SqlDatabase
import com.deepstudent.services.sync.api.DomainSyncAdapter
import com.deepstudent.services.sync.data.capture.SyncCaptureRegistry
import com.deepstudent.services.sync.data.changelog.SqlPublicationSet
import com.deepstudent.services.sync.data.changelog.SqlPublicationSource
import com.deepstudent.services.sync.data.changelog.SqlRepositorySeed

internal class MainPublicationComposition(
    databases: Map<SharedDatabase, SqlDatabase>,
    registry: SyncCaptureRegistry,
    logger: Logger,
    check: () -> Unit,
) {
    private val sources = SharedDatabase.entries.map { owner ->
        SqlPublicationSource(databases.getValue(owner), owner, registry.rowSyncKeys(owner), check, logger)
    }

    fun seed(required: Boolean): SqlRepositorySeed? = if (required) SqlRepositorySeed(sources) else null

    fun combine(incoming: DomainSyncAdapter, notes: SqlNotesSyncAdapter, noteTables: Set<String>): DomainSyncAdapter =
        SqlPublicationSet(sources, incoming) { source ->
            if (source.scope != SharedDatabase.VFS) {
                source.pending()
            } else {
                source.pending { sql, rows ->
                    val (dedicated, rest) = rows.partition {
                        val value = JsonValue.parseObject(it.decodeToString())["table_name"] as? JsonValue.Text
                        value?.value in noteTables
                    }
                    notes.enrichCaptured(sql, dedicated) + rest
                }
            }
        }
}
