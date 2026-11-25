package com.deepstudent.services.sync.data.capture

import com.deepstudent.platform.database.CoordinatedSchemaRepair
import com.deepstudent.platform.database.MigrationDiagnostic
import com.deepstudent.platform.database.MigrationException
import com.deepstudent.platform.database.MigrationSource
import com.deepstudent.platform.database.SharedDatabase
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.boolean
import kotlinx.serialization.json.int
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import java.security.MessageDigest
import java.util.concurrent.CancellationException

internal object CaptureRegistryLoader {
    private const val LOCK = "config/schema/final-lock.json"

    // The S0.G main-thread acceptance receipt anchors this byte hash; it is not another registry.
    private const val ACCEPTED_LOCK_SHA = "5fd1b3a4bd4288ce577c3f17677c9ca6accfab56c03a80130a8797baed5c08e8"
    private const val BASE = "config/sync-registry-contract.json"
    private const val SCHEMA = "config/baseline/schema.json"
    private const val TEMPLATE = "config/contracts/templates-assets.json"
    private const val MEDIA = "config/contracts/media-time.json"
    private const val PLUGIN = "config/contracts/plugin-data.json"

    fun load(source: MigrationSource): SyncCaptureRegistry {
        try {
            val lockBytes = read(source, LOCK)
            if (sha256(lockBytes) != ACCEPTED_LOCK_SHA) throw SyncCaptureSourceException(CaptureSourceDiagnostic.BYTES_DRIFT)
            val lock = parse(lockBytes)
            check(lock.str("format") == "dstu-s0-g-final-lock/v1" && lock.str("status") == "validated")
            val baseline = parse(artifact(source, lock, "S0.19", BASE))
            val template = parse(artifact(source, lock, "S0.21", TEMPLATE)).obj("classification").obj("after")
            val media = parse(artifact(source, lock, "S0.22", MEDIA)).obj("event").obj("classification")
            val plugin = parse(artifact(source, lock, "S0.24", PLUGIN)).obj("schema").obj("classification")
            val final = lock.obj("registry")
            val coordinated = coordinatedTriggers(source, lock)
            val entries = baselineEntries(baseline, template, final, coordinated).toMutableList()
            appendNewEntries(entries, final, listOf(media, plugin), coordinated)
            validate(lock, entries, coordinated)
            val versions = lock.arr("databases").associate {
                val row = it.jsonObject
                database(row.str("id")) to row["version"]!!.jsonPrimitive.int
            }
            check(versions.keys == SharedDatabase.entries.toSet())
            val repair = CoordinatedSchemaRepair.load(source)
            check(repair.database == SharedDatabase.VFS && repair.version == 20261120)
            check(repair.version > versions.getValue(repair.database))
            val repairedEntries = applyRepair(entries, repair.database, repair.sql)
            // Recheck the complete frozen classification and trigger contract after replacement.
            validate(lock, repairedEntries, coordinated)
            val schemas = changeLogSchemas(parse(artifact(source, lock, "S0.08", SCHEMA)))
            return SyncCaptureRegistry(repairedEntries, versions + (repair.database to repair.version), schemas)
        } catch (e: CancellationException) {
            throw e
        } catch (e: SyncCaptureSourceException) {
            throw e
        } catch (e: MigrationException) {
            throw sourceError(e)
        } catch (_: Exception) {
            throw SyncCaptureSourceException(CaptureSourceDiagnostic.CONTRACT_INVALID)
        }
    }

    private fun sourceError(error: MigrationException): SyncCaptureSourceException {
        val diagnostic = when {
            error.diagnostic == MigrationDiagnostic.SQL_BYTES_DRIFT -> CaptureSourceDiagnostic.BYTES_DRIFT

            error.diagnostic == MigrationDiagnostic.SOURCE_INVALID && error.causeClass == "SourceReadFailure" ->
                CaptureSourceDiagnostic.UNAVAILABLE

            else -> CaptureSourceDiagnostic.CONTRACT_INVALID
        }
        return SyncCaptureSourceException(diagnostic)
    }

    private fun baselineEntries(
        baseline: JsonObject,
        template: JsonObject,
        final: JsonObject,
        coordinated: Map<String, List<FrozenTrigger>>,
    ): List<SyncCaptureEntry> {
        val changes = final.arr("changedEntries").map { it.jsonObject }
        return baseline.arr("registry").map { element ->
            val row = element.jsonObject
            val key = "${row.str("database")}.${row.str("table")}"
            val change = changes.singleOrNull { it.str("key") == key }
            baselineEntry(row, change?.let { changedTemplate(it, row, template, key) }, coordinated[key].orEmpty())
        }
    }

    private fun changedTemplate(change: JsonObject, row: JsonObject, template: JsonObject, key: String): JsonObject {
        check(change.str("from") == row.str("category"))
        check(change.str("to") == template.str("category"))
        check(key == "${template.str("database")}.${template.str("table_name")}")
        check(row.obj("primaryKey").str("declared") == template.str("primary_key"))
        return template
    }

    private fun appendNewEntries(
        entries: MutableList<SyncCaptureEntry>,
        final: JsonObject,
        candidates: List<JsonObject>,
        coordinated: Map<String, List<FrozenTrigger>>,
    ) {
        final.arr("newEntries").forEach { element ->
            val row = element.jsonObject
            val supplied = candidates.single { "${it.str("database")}.${it.str("table_name")}" == row.str("key") }
            check(row.str("primaryKey") == supplied.str("primary_key"))
            check(row.str("category") == supplied.str("category"))
            check(row.str("conflictPolicy") == supplied.str("conflict_policy"))
            entries += newEntry(supplied, coordinated[row.str("key")].orEmpty())
        }
    }

    private fun changeLogSchemas(schema: JsonObject): Map<SharedDatabase, String> {
        val result = mutableMapOf<SharedDatabase, String>()
        schema.arr("databases").map { it.jsonObject }.forEach { row ->
            val owner = SharedDatabase.entries.singleOrNull { it.id == row.str("id") } ?: return@forEach
            val objects = row.obj("schema").arr("objects").map { it.jsonObject }
            val ddl = objects.single { it.str("type") == "table" && it.str("name") == "__change_log" }.str("sql")
            result[owner] = sha256(ddl.toByteArray(Charsets.UTF_8))
        }
        check(result.keys == SharedDatabase.entries.toSet())
        return result
    }

    private fun baselineEntry(row: JsonObject, changed: JsonObject?, extra: List<FrozenTrigger>): SyncCaptureEntry {
        val primary = row.obj("primaryKey")
        val triggers = row.obj("triggers").arr("rows").map { it.jsonObject }.map {
            FrozenTrigger(it.str("name"), operation(it.str("event")), it.bool("capturesChangeLog"), it.str("sha256"))
        }
        val info = changed ?: row
        val keys = if (changed == null) {
            row.arr("businessUniqueKeys").map { it.jsonPrimitive.content }
        } else {
            info.str("business_unique_keys").split(',').map(String::trim).filter(String::isNotEmpty)
        }
        return SyncCaptureEntry(
            database(row.str("database")),
            row.str("table"),
            SyncCategory.valueOf(info.str("category")),
            if (changed == null) info.str("conflictPolicy") else info.str("conflict_policy"),
            primary.arr("logical").map { it.jsonPrimitive.content },
            keys,
            CaptureEntrySchema(
                primary.bool("virtual"),
                row.obj("schema").bool("present"),
                primary.arr("sqlite").map { it.jsonPrimitive.content },
                triggers + extra,
                if (changed == null) info.bool("hasJsonBlobs") else info.bool("has_json_blobs"),
            ),
        )
    }

    private fun newEntry(row: JsonObject, triggers: List<FrozenTrigger>): SyncCaptureEntry {
        val pk = row.str("primary_key").split(',').map(String::trim).filter(String::isNotEmpty)
        val keys = row.str("business_unique_keys").split(',').map(String::trim).filter(String::isNotEmpty)
        return SyncCaptureEntry(
            database(row.str("database")),
            row.str("table_name"),
            SyncCategory.valueOf(row.str("category")),
            row.str("conflict_policy"),
            pk,
            keys,
            CaptureEntrySchema(false, true, pk, triggers, row.bool("has_json_blobs")),
        )
    }

    private fun artifact(source: MigrationSource, lock: JsonObject, unit: String, path: String): ByteArray {
        val entry = lock.arr("units").map { it.jsonObject }.single { it.str("unit") == unit }
            .arr("artifacts").map { it.jsonObject }.single { it.str("file") == path }
        return checked(source, path, entry.str("sha256"))
    }

    private fun coordinatedTriggers(source: MigrationSource, lock: JsonObject): Map<String, List<FrozenTrigger>> {
        val result = mutableMapOf<String, MutableList<FrozenTrigger>>()
        val files = lock.obj("sql").arr("files").map { it.jsonObject }.filter { it.str("origin") == "coordinated" }
        check(files.size == lock.obj("sql")["coordinatedCount"]!!.jsonPrimitive.int)
        files.forEach { file ->
            val sql = checked(source, file.str("file"), file.str("sha256")).toString(Charsets.UTF_8)
            val db = database(file.str("database"))
            CoordinatedTriggerParser.parse(sql).forEach { (table, trigger) ->
                result.getOrPut("${db.id}.$table") { mutableListOf() }.add(trigger)
            }
        }
        return result
    }

    /** Replace only the already-validated frozen trigger, never append a competing trigger. */
    private fun applyRepair(entries: List<SyncCaptureEntry>, owner: SharedDatabase, sql: String): List<SyncCaptureEntry> {
        val (table, replacement) = CoordinatedTriggerParser.parse(sql).single()
        check(table == "portable_plugin_records")
        check(replacement.name == "trg__change_log_portable_plugin_records_delete")
        check(replacement.operation == CaptureOperation.DELETE && replacement.captures)
        val original = entries.single { it.database == owner && it.table == table }
        val frozen = original.triggers.single { it.name == replacement.name }
        check(frozen.operation == replacement.operation && frozen.captures && frozen.sha256 != replacement.sha256)
        val schema = original.schema.copy(triggers = original.triggers.map { if (it.name == frozen.name) replacement else it })
        val updated = SyncCaptureEntry(
            original.database,
            original.table,
            original.category,
            original.conflictPolicy,
            original.logicalPrimaryKey,
            original.businessUniqueKeys,
            schema,
        )
        return entries.map { if (it === original) updated else it }
    }

    private fun validate(lock: JsonObject, entries: List<SyncCaptureEntry>, coordinated: Map<String, List<FrozenTrigger>>) {
        val final = lock.obj("registry")
        check(entries.size == final["count"]!!.jsonPrimitive.int)
        check(entries.count { it.category == SyncCategory.RowSync } == final["rowSync"]!!.jsonPrimitive.int)
        check(entries.map { it.database to it.table }.toSet().size == entries.size)
        val order = JsonArray(entries.map { JsonPrimitive("${it.database.id}.${it.table}") }).toString()
        check(sha256(order.toByteArray()) == final.str("productionOrderSha256"))
        final.obj("categoryCounts").forEach { (name, count) ->
            check(entries.count { it.category.name == name } == count.jsonPrimitive.int)
        }
        check(coordinated.keys.all { key -> entries.any { "${it.database.id}.${it.table}" == key } })
        validatePrimaryKeys(final.obj("primaryKey"), entries)
        val exceptions = final.obj("triggers").arr("exemptions").map { it.jsonObject }
        entries.forEach { entry ->
            val key = "${entry.database.id}.${entry.table}"
            val exemption = exceptions.filter { it.str("key") == key }.map { operation(it.str("operation")) }.toSet()
            val captureOps = entry.triggers.filter { it.captures }.map { it.operation }
            check(entry.triggers.map { it.name }.toSet().size == entry.triggers.size)
            if (entry.category == SyncCategory.RowSync) {
                check(captureOps.size == captureOps.toSet().size)
                check(captureOps.toSet() == CaptureOperation.entries.toSet() - exemption)
            } else {
                check(captureOps.isEmpty() && exemption.isEmpty())
            }
        }
        check(
            exceptions.size == 1 && exceptions.single().str("key") == "vfs.note_document_revisions" &&
                operation(exceptions.single().str("operation")) == CaptureOperation.DELETE,
        )
    }

    private fun validatePrimaryKeys(contract: JsonObject, entries: List<SyncCaptureEntry>) {
        val checked = entries.filter { it.present && !it.virtual }
        val virtual = entries.filter { it.virtual }.map { "${it.database.id}.${it.table}" }.toSet()
        check(checked.size == contract["checked"]!!.jsonPrimitive.int)
        check(virtual == contract.arr("virtualTables").map { it.jsonPrimitive.content }.toSet())
        val exceptions = contract.arr("knownExceptions").map { it.jsonObject }
        val mismatches = checked.filter { it.logicalPrimaryKey != it.sqlitePrimaryKey }
        check(mismatches.size == exceptions.size)
        mismatches.forEach { entry ->
            val key = "${entry.database.id}.${entry.table}"
            val exception = exceptions.single { it.str("key") == key }
            check(entry.logicalPrimaryKey == exception.arr("declared").map { it.jsonPrimitive.content })
            check(entry.sqlitePrimaryKey == exception.arr("sqlite").map { it.jsonPrimitive.content })
        }
        check(contract.arr("violations").isEmpty())
    }

    private fun checked(source: MigrationSource, path: String, expected: String): ByteArray = read(source, path).also {
        if (sha256(it) != expected) throw SyncCaptureSourceException(CaptureSourceDiagnostic.BYTES_DRIFT)
    }
    private fun read(source: MigrationSource, path: String): ByteArray = try {
        source.read(path)
    } catch (cancelled: CancellationException) {
        throw cancelled
    } catch (_: Exception) {
        throw SyncCaptureSourceException(CaptureSourceDiagnostic.UNAVAILABLE)
    }
    private fun parse(bytes: ByteArray): JsonObject = Json.parseToJsonElement(bytes.toString(Charsets.UTF_8)).jsonObject
    private fun database(id: String): SharedDatabase = SharedDatabase.entries.single { it.id == id }
}

internal fun JsonObject.obj(key: String): JsonObject = getValue(key).jsonObject
internal fun JsonObject.arr(key: String): JsonArray = getValue(key).jsonArray
internal fun JsonObject.str(key: String): String = getValue(key).jsonPrimitive.content
internal fun JsonObject.bool(key: String): Boolean = getValue(key).jsonPrimitive.boolean
internal fun operation(value: String): CaptureOperation = CaptureOperation.valueOf(value.uppercase())
internal fun sha256(bytes: ByteArray): String =
    MessageDigest.getInstance("SHA-256").digest(bytes).joinToString("") { "%02x".format(it.toInt() and 255) }
