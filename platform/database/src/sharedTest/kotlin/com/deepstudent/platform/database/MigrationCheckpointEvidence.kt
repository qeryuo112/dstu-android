package com.deepstudent.platform.database

import com.deepstudent.platform.database.internal.SqliteConnectionFactory
import com.deepstudent.platform.database.internal.SqliteSession
import java.io.File
import java.io.FileOutputStream
import java.security.MessageDigest
import java.time.Instant
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.int
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue

internal data class CheckpointLedgerEntry(val version: Int, val name: String, val checksum: String)

internal data class ObservedLedgerEntry(val entry: CheckpointLedgerEntry, val appliedOn: String)

internal data class CheckpointGovernance(val version: Int, val fingerprint: String, val verifiedAt: String, val canonical: String)

internal data class CheckpointSnapshot(val ledger: List<ObservedLedgerEntry>, val governance: CheckpointGovernance?) {
    fun ledgerDigest(count: Int = ledger.size): String = sha256(ledger.take(count).flatMap { row ->
        listOf(row.entry.version.toString(), row.entry.name, row.entry.checksum, row.appliedOn)
    })
}

/** The last two fields attest to the entire ledger that existed BEFORE the killed commit. */
internal data class MigrationDeathMarker(
    val kind: String, val database: String, val count: Int, val version: Int,
    val pid: Long, val priorCount: Int, val priorDigest: String,
) {
    fun write(file: File) = writeEvidence(file, listOf(kind, database, count, version, pid, priorCount, priorDigest))

    companion object {
        fun read(file: File): MigrationDeathMarker {
            val fields = file.readText().trimEnd().split('\t').also { check(it.size == 7) }
            val (kind, database, count, version, pid) = fields
            return MigrationDeathMarker(kind, database, count.toInt(), version.toInt(), pid.toLong(), fields[5].toInt(), fields[6])
        }
    }
}

internal fun writeEvidence(file: File, fields: List<Any>) {
    FileOutputStream(file).use {
        it.write(fields.joinToString("\t", postfix = "\n").encodeToByteArray())
        it.fd.sync()
    }
}

private fun sha256(fields: List<String>): String = MessageDigest.getInstance("SHA-256")
    .digest(fields.joinToString("\u0000").encodeToByteArray()).joinToString("") { "%02x".format(it) }

/** Independent assertions over the actual SQLite file, not just the runner's reported version. */
internal class MigrationCheckpointEvidence(
    private val file: File, private val database: SharedDatabase, source: MigrationSource, fixtureBytes: ByteArray?,
) {
    private val lock = Json.parseToJsonElement(source.read("config/schema/final-lock.json").decodeToString()).jsonObject
        .getValue("databases").jsonArray.map(JsonElement::jsonObject).single { it.text("id") == database.id }
    private val repair = CoordinatedSchemaRepair.load(source).takeIf { it.database == database }
    val history = lock.getValue("history").jsonArray.map {
        val row = it.jsonObject
        CheckpointLedgerEntry(row.number("version"), row.text("name"), row.text("checksum"))
    } + listOfNotNull(repair?.let { CheckpointLedgerEntry(it.version, it.migration.name, it.migration.checksum) })
    val version = repair?.version ?: lock.number("version")
    val fingerprint = repair?.after?.fingerprint ?: lock.text("fingerprint")
    private val oldVfs = fixtureBytes?.let(::DesktopVfsEvidence)
    val initialCount = oldVfs?.initialCount ?: 0

    fun observe(expectedCount: Int): CheckpointSnapshot {
        if (expectedCount == 0) {
            assertFalse("fresh scenario must not start on an existing database", file.exists())
            return CheckpointSnapshot(emptyList(), null)
        }
        return SqliteConnectionFactory().openReadOnly(file).use { db ->
            val ledger = readLedger(db)
            assertEquals("exact frozen ledger prefix", history.take(expectedCount), ledger.map { it.entry })
            assertEquals("integrity at commit $expectedCount", listOf("ok"), db.queryTexts("PRAGMA integrity_check"))
            assertTrue("foreign keys at commit $expectedCount", db.queryAll("PRAGMA foreign_key_check") { true }.isEmpty())
            oldVfs?.verify(db, expectedCount > initialCount)
            CheckpointSnapshot(ledger, readGovernance(db))
        }
    }

    fun requireFinalGovernance(snapshot: CheckpointSnapshot) {
        val record = checkNotNull(snapshot.governance) { "head commit did not persist governance" }
        assertEquals(version, record.version)
        assertEquals(fingerprint, record.fingerprint)
        assertEquals(fingerprint, sha256(listOf(record.canonical)))
        Instant.parse(record.verifiedAt)
    }

    private fun readLedger(db: SqliteSession): List<ObservedLedgerEntry> = db.queryAll(
        "SELECT version,name,checksum,applied_on FROM refinery_schema_history ORDER BY version",
    ) {
        val appliedOn = it.getText(3)
        Instant.parse(appliedOn)
        ObservedLedgerEntry(CheckpointLedgerEntry(it.getLong(0).toInt(), it.getText(1), it.getText(2)), appliedOn)
    }

    private fun readGovernance(db: SqliteSession): CheckpointGovernance? {
        if (db.queryLong("SELECT count(*) FROM sqlite_master WHERE name='__governance_schema_fingerprints'") == 0L) return null
        val rows = db.queryAll(
            "SELECT database_id,schema_version,fingerprint,verified_at,canonical_schema FROM __governance_schema_fingerprints",
        ) {
            assertEquals(database.id, it.getText(0))
            CheckpointGovernance(it.getLong(1).toInt(), it.getText(2), it.getText(3), it.getText(4))
        }
        return rows.single()
    }
}

private class DesktopVfsEvidence(bytes: ByteArray) {
    private val golden = Json.parseToJsonElement(bytes.decodeToString()).jsonObject
    private val values = golden.getValue("values").jsonObject
    private val original = values.getValue("before").jsonObject
    private val defaults = values.getValue("appendedDefaults").jsonObject
    val initialCount = golden.getValue("fixture").jsonObject.getValue("vfsMigrationCount").jsonObject.number("before")

    fun verify(db: SqliteSession, fsrsIntroduced: Boolean) {
        val expected = if (fsrsIntroduced) original + defaults else original
        requireRow(db, "review_plans", expected)
        requireRow(db, "exam_sheets", linkedMapOf(
            "id" to original.getValue("exam_id"), "exam_name" to JsonPrimitive("Desktop upgrade fixture"),
            "status" to JsonPrimitive("completed"), "temp_id" to JsonPrimitive("s2-test"),
            "metadata_json" to JsonPrimitive("{}"), "preview_json" to JsonPrimitive("{}"),
            "created_at" to original.getValue("created_at"), "updated_at" to original.getValue("updated_at"),
        ))
        requireRow(db, "questions", linkedMapOf(
            "id" to original.getValue("question_id"), "exam_id" to original.getValue("exam_id"),
            "question_label" to JsonPrimitive("1"), "content" to JsonPrimitive("Fixture question"), "answer" to JsonPrimitive("A"),
            "created_at" to original.getValue("created_at"), "updated_at" to original.getValue("updated_at"),
        ))
    }

    private fun requireRow(db: SqliteSession, table: String, expected: Map<String, JsonElement>) {
        val columns = expected.keys.toList()
        val rows = db.queryAll("SELECT ${columns.joinToString(",")} FROM $table WHERE id=?", {
            it.bindText(1, expected.getValue("id").jsonPrimitive.content)
        }) { row ->
            columns.forEachIndexed { index, key ->
                val value = expected.getValue(key)
                when {
                    value is JsonNull -> assertTrue(key, row.isNull(index))
                    value.jsonPrimitive.isString -> assertEquals(key, value.jsonPrimitive.content, row.getText(index))
                    '.' in value.jsonPrimitive.content -> assertEquals(key, value.jsonPrimitive.content.toDouble(), row.getDouble(index), 0.0)
                    else -> assertEquals(key, value.jsonPrimitive.content.toLong(), row.getLong(index))
                }
            }
        }
        assertEquals("preserved row in $table", 1, rows.size)
    }
}

private fun JsonObject.number(key: String): Int = getValue(key).jsonPrimitive.int
private fun JsonObject.text(key: String): String = getValue(key).jsonPrimitive.content
