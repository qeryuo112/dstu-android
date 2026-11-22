package com.deepstudent.platform.database

import com.deepstudent.platform.database.internal.FrozenMigration
import com.deepstudent.platform.database.internal.refineryChecksum
import com.deepstudent.platform.database.internal.safeRead
import com.deepstudent.platform.database.internal.sanitizeSource
import com.deepstudent.platform.database.internal.sha256
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.int
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive

/**
 * The separately approved S2.04 append, not a replacement for the S0 final lock.
 * Loading fails closed on missing, unreadable, malformed or altered inputs.
 */
public class CoordinatedSchemaRepair private constructor(
    public val database: SharedDatabase,
    public val version: Int,
    public val sql: String,
    internal val before: RepairState,
    internal val after: RepairState,
    internal val migration: FrozenMigration,
) {
    public companion object {
        private const val ORIGINAL_PATH = "config/schema/final-lock.json"
        private const val ORIGINAL_SHA = "5fd1b3a4bd4288ce577c3f17677c9ca6accfab56c03a80130a8797baed5c08e8"
        private const val ORIGINAL_SET_SHA = "a3c70a046b3d981c2ceba348ad9e7a14e3113d076925e6dbad50ccc67a6d2675"
        private const val REPAIR_PATH = "config/schema/s2-04-repair.json"
        private const val REPAIR_SHA = "ec1ce53307dd93c45584c55b7fc38c6e52a3b28b3a7fa5f0514b56c78f0b283c"
        private const val SQL_PATH = "config/schema/coordinated/vfs/V20261120__fix_plugin_delete_trigger.sql"
        private const val NAME = "fix_plugin_delete_trigger"

        /** Verify the original-lock anchor, approved supplement bytes, exact SQL path and SQL bytes. */
        public fun load(source: MigrationSource): CoordinatedSchemaRepair = sanitizeSource {
            val anchorSha = sha256(safeRead(source, ORIGINAL_PATH))
            loadWithVerifiedAnchor(source, anchorSha)
        }

        // Called by the catalog only after it has checked the original lock and every original migration.
        internal fun loadWithVerifiedAnchor(source: MigrationSource, anchorSha: String): CoordinatedSchemaRepair = sanitizeSource {
            check(anchorSha == ORIGINAL_SHA)
            val bytes = safeRead(source, REPAIR_PATH)
            check(sha256(bytes) == REPAIR_SHA)
            val root = Json.parseToJsonElement(bytes.decodeToString(throwOnInvalidSequence = true)).jsonObject
            validateAnchor(root, anchorSha)
            validateIdentity(root)
            val migration = loadRepairSql(source, root.getValue("migration").jsonObject)
            CoordinatedSchemaRepair(
                SharedDatabase.VFS, migration.version, migration.sql,
                readState(root.getValue("before").jsonObject), readState(root.getValue("after").jsonObject), migration,
            )
        }

        private fun validateAnchor(root: JsonObject, anchorSha: String) {
            val anchor = root.getValue("anchor").jsonObject
            check(root.number("formatVersion") == 1 && root.text("unit") == "S2.04")
            check(anchor.text("file") == ORIGINAL_PATH && anchor.text("sha256") == anchorSha)
            check(anchor.text("sqlSetSha256") == ORIGINAL_SET_SHA)
            check(root.text("scope") == "unchanged from anchored final-lock managedSchema.names")
        }

        private fun validateIdentity(root: JsonObject) {
            val before = root.getValue("before").jsonObject
            val after = root.getValue("after").jsonObject
            val row = root.getValue("migration").jsonObject
            check(root.text("database") == SharedDatabase.VFS.id && row.text("database") == SharedDatabase.VFS.id)
            check(before.number("version") == 20261117 && before.number("migrationCount") == 75)
            check(after.number("version") == 20261120 && after.number("migrationCount") == 76)
            check(row.number("version") == after.number("version"))
            check(row.text("name") == NAME && row.text("file") == SQL_PATH)
        }

        private fun loadRepairSql(source: MigrationSource, row: JsonObject): FrozenMigration {
            val sqlBytes = safeRead(source, SQL_PATH)
            if (sqlBytes.size != row.number("bytes") || sha256(sqlBytes) != row.text("sha256")) {
                throw MigrationException(MigrationDiagnostic.SQL_BYTES_DRIFT, SharedDatabase.VFS, 20261120, "migration", NAME)
            }
            val sql = sqlBytes.decodeToString(throwOnInvalidSequence = true)
            val checksum = refineryChecksum(NAME, 20261120, sqlBytes)
            if (checksum != row.text("refineryChecksum")) {
                throw MigrationException(MigrationDiagnostic.REFINERY_DRIFT, SharedDatabase.VFS, 20261120, "migration", NAME)
            }
            return FrozenMigration(SharedDatabase.VFS.id, 20261120, NAME, SQL_PATH, row.text("sha256"), checksum, sql)
        }

        private fun readState(row: JsonObject): RepairState =
            RepairState(row.number("version"), row.text("fingerprint"), row.number("migrationCount"))
    }
}

internal data class RepairState(val version: Int, val fingerprint: String, val count: Int)
private fun JsonObject.text(key: String): String = getValue(key).jsonPrimitive.content
private fun JsonObject.number(key: String): Int = getValue(key).jsonPrimitive.int
