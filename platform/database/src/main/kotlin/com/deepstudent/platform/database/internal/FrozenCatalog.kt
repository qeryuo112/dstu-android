package com.deepstudent.platform.database.internal

import com.deepstudent.platform.database.CoordinatedSchemaRepair
import com.deepstudent.platform.database.MigrationDiagnostic
import com.deepstudent.platform.database.MigrationException
import com.deepstudent.platform.database.MigrationSource
import com.deepstudent.platform.database.SharedDatabase
import java.security.MessageDigest
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.int
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive

internal data class FrozenMigration(
    val databaseId: String,
    val version: Int,
    val name: String,
    val path: String,
    val sha256: String,
    val checksum: String,
    val sql: String,
)

internal data class SchemaScope(
    val tables: List<String>,
    val indexes: List<String>,
    val triggers: List<String>,
)

internal data class FrozenSchema(val version: Int, val fingerprint: String, val scope: SchemaScope)

internal data class FrozenCatalog(
    val migrations: List<FrozenMigration>,
    val schemas: Map<SharedDatabase, FrozenSchema>,
) {
    fun forDatabase(database: SharedDatabase): List<FrozenMigration> =
        migrations.filter { it.databaseId == database.id }.sortedBy { it.version }
}

internal object FrozenCatalogLoader {
    private const val LOCK_PATH = "config/schema/final-lock.json"
    private const val LOCK_SHA = "5fd1b3a4bd4288ce577c3f17677c9ca6accfab56c03a80130a8797baed5c08e8"
    private const val SET_SHA = "a3c70a046b3d981c2ceba348ad9e7a14e3113d076925e6dbad50ccc67a6d2675"

    fun load(source: MigrationSource): FrozenCatalog = sanitizeSource {
        val lockBytes = safeRead(source, LOCK_PATH)
        if (sha256(lockBytes) != LOCK_SHA) fail(MigrationDiagnostic.SOURCE_INVALID)
        val root = Json.parseToJsonElement(lockBytes.decodeToString(throwOnInvalidSequence = true)).jsonObject
        val sql = root.objectAt("sql")
        val rows = sql.arrayAt("files")
        if (rows.size != 138 || sql.intAt("upstreamCount") != 135 || sql.intAt("coordinatedCount") != 3) {
            fail(MigrationDiagnostic.SOURCE_INVALID)
        }
        val migrations = rows.map { loadMigration(source, it.jsonObject) }
        validateSet(migrations, sql.stringAt("setSha256"))
        val schemas = root.arrayAt("databases").associate { element ->
            val row = element.jsonObject
            val id = SharedDatabase.entries.single { it.id == row.stringAt("id") }
            val names = row.objectAt("managedSchema").objectAt("names")
            id to FrozenSchema(
                row.intAt("version"), row.stringAt("fingerprint"),
                SchemaScope(names.strings("tables"), names.strings("indexes"), names.strings("triggers")),
            )
        }
        if (schemas.size != SharedDatabase.entries.size) fail(MigrationDiagnostic.SOURCE_INVALID)
        mergeRepair(source, sha256(lockBytes), migrations, schemas)
    }

    private fun mergeRepair(
        source: MigrationSource, anchorSha: String, migrations: List<FrozenMigration>,
        schemas: Map<SharedDatabase, FrozenSchema>,
    ): FrozenCatalog {
        val repair = CoordinatedSchemaRepair.loadWithVerifiedAnchor(source, anchorSha)
        val previous = schemas[repair.database] ?: fail(MigrationDiagnostic.SOURCE_INVALID)
        val oldMigrations = migrations.filter { it.databaseId == repair.database.id }
        if (previous.version != repair.before.version || previous.fingerprint != repair.before.fingerprint ||
            oldMigrations.size != repair.before.count || oldMigrations.maxOf { it.version } != repair.before.version ||
            repair.version <= previous.version || repair.after.count != oldMigrations.size + 1
        ) fail(MigrationDiagnostic.SOURCE_INVALID)
        return FrozenCatalog(
            migrations + repair.migration,
            schemas + (repair.database to previous.copy(version = repair.version, fingerprint = repair.after.fingerprint)),
        )
    }

    private fun loadMigration(source: MigrationSource, row: JsonObject): FrozenMigration {
        val path = row.stringAt("file")
        val id = row.stringAt("database")
        val version = row.intAt("version")
        val name = row.stringAt("name")
        val bytes = safeRead(source, path)
        val expectedSha = row.stringAt("sha256")
        val db = SharedDatabase.entries.firstOrNull { it.id == id }
        if (bytes.size != row.intAt("bytes") || sha256(bytes) != expectedSha) {
            throw MigrationException(MigrationDiagnostic.SQL_BYTES_DRIFT, db, version, "migration", name)
        }
        val sql = bytes.decodeToString(throwOnInvalidSequence = true)
        val checksum = refineryChecksum(name, version, bytes)
        if (checksum != row.stringAt("refineryChecksum")) {
            throw MigrationException(MigrationDiagnostic.REFINERY_DRIFT, db, version, "migration", name)
        }
        return FrozenMigration(id, version, name, path, expectedSha, checksum, sql)
    }

    private fun validateSet(migrations: List<FrozenMigration>, locked: String) {
        val identities = migrations.map { it.databaseId to it.version }
        if (identities.toSet().size != identities.size) fail(MigrationDiagnostic.SOURCE_INVALID)
        val text = migrations.joinToString("") {
            "${it.databaseId}\u0000${it.version}\u0000${it.name}\u0000${it.sha256}\u0000${it.checksum}\n"
        }
        if (locked != SET_SHA || sha256(text.toByteArray()) != SET_SHA) fail(MigrationDiagnostic.SOURCE_INVALID)
    }
}

// A source is an external port: even a MigrationException it throws is untrusted.
@Suppress("TooGenericExceptionCaught")
internal fun safeRead(source: MigrationSource, path: String): ByteArray = try {
    source.read(path)
} catch (_: Exception) {
    throw MigrationException(MigrationDiagnostic.SOURCE_INVALID, causeClass = "SourceReadFailure")
}

internal fun sha256(bytes: ByteArray): String =
    MessageDigest.getInstance("SHA-256").digest(bytes).joinToString("") { "%02x".format(it.toInt() and 255) }

internal fun fail(diagnostic: MigrationDiagnostic): Nothing = throw MigrationException(diagnostic)

@Suppress("TooGenericExceptionCaught")
internal fun <T> sanitizeSource(block: () -> T): T = try {
    block()
} catch (failure: MigrationException) {
    throw failure
} catch (failure: Exception) {
    throw MigrationException(MigrationDiagnostic.SOURCE_INVALID, causeClass = "SourceFormatFailure")
}

private fun JsonObject.objectAt(key: String): JsonObject = getValue(key).jsonObject
private fun JsonObject.arrayAt(key: String): JsonArray = getValue(key).jsonArray
private fun JsonObject.stringAt(key: String): String = getValue(key).jsonPrimitive.content
private fun JsonObject.intAt(key: String): Int = getValue(key).jsonPrimitive.int
private fun JsonObject.strings(key: String): List<String> = arrayAt(key).map { it.jsonPrimitive.content }
