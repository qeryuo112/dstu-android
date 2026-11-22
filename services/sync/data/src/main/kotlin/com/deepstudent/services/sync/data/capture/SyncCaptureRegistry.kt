package com.deepstudent.services.sync.data.capture

import com.deepstudent.platform.database.MigrationSource
import com.deepstudent.platform.database.SharedDatabase
import java.util.Collections

/** Classification is a projection of the S0.G-locked production registry, not a second table list. */
enum class SyncCategory { RowSync, LocalRuntime, DerivedRebuild, BackupOnly, Deprecated }
enum class CaptureOperation { INSERT, UPDATE, DELETE }

internal data class CaptureEntrySchema(
    val virtual: Boolean,
    val present: Boolean,
    val sqlitePrimaryKey: List<String>,
    val triggers: List<FrozenTrigger>,
    val hasJsonBlobs: Boolean,
)

class SyncCaptureEntry internal constructor(
    val database: SharedDatabase,
    val table: String,
    val category: SyncCategory,
    val conflictPolicy: String,
    logicalPrimaryKey: List<String>,
    businessUniqueKeys: List<String>,
    internal val schema: CaptureEntrySchema,
) {
    val logicalPrimaryKey: List<String> = Collections.unmodifiableList(logicalPrimaryKey.toList())
    val businessUniqueKeys: List<String> = Collections.unmodifiableList(businessUniqueKeys.toList())
    val virtual: Boolean get() = schema.virtual
    val present: Boolean get() = schema.present
    val hasJsonBlobs: Boolean get() = schema.hasJsonBlobs
    internal val sqlitePrimaryKey: List<String> get() = schema.sqlitePrimaryKey
    internal val triggers: List<FrozenTrigger> get() = schema.triggers
}

internal data class FrozenTrigger(
    val name: String,
    val operation: CaptureOperation,
    val captures: Boolean,
    val sha256: String,
)

class SyncCaptureRegistry internal constructor(entries: List<SyncCaptureEntry>, internal val versions: Map<SharedDatabase, Int>, internal val changeLogSchemas: Map<SharedDatabase, String>) {
    val entries: List<SyncCaptureEntry> = Collections.unmodifiableList(entries.toList())
    private val byKey = entries.associateBy { it.database to it.table }

    fun find(database: SharedDatabase, table: String): SyncCaptureEntry? = byKey[database to table]

    /** Effective shared-schema versions, including verified coordinated repairs. */
    fun schemaVersions(): Map<String, Int> = Collections.unmodifiableMap(
        versions.entries.associate { it.key.id to it.value },
    )

    /** For the existing single-column manifest checksum. Fails rather than dropping composite keys. */
    fun checksumKeys(database: SharedDatabase): Map<String, String> {
        val selected = entries.filter { it.database == database && it.category == SyncCategory.RowSync }
        require(selected.all { it.logicalPrimaryKey.size == 1 }) { "checksum requires single-column keys" }
        return Collections.unmodifiableMap(selected.associate { it.table to it.logicalPrimaryKey.single() })
    }

    companion object {
        /** Loads verified frozen inputs and approved coordinated repairs without mutating any database. */
        fun load(source: MigrationSource): SyncCaptureRegistry = CaptureRegistryLoader.load(source)
    }
}

enum class CaptureSourceDiagnostic { UNAVAILABLE, BYTES_DRIFT, CONTRACT_INVALID }
class SyncCaptureSourceException(val diagnostic: CaptureSourceDiagnostic) :
    IllegalStateException("sync capture source: ${diagnostic.name}")
