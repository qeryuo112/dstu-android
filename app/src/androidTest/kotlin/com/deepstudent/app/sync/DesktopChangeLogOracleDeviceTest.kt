package com.deepstudent.app.sync

import android.util.Log
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.deepstudent.core.common.id.Digests
import com.deepstudent.core.common.json.JsonValue
import com.deepstudent.platform.database.BundledMigrationSource
import com.deepstudent.platform.database.DatabaseRegistry
import com.deepstudent.platform.database.SharedDatabase
import com.deepstudent.services.sync.backend.SyncBatch
import com.deepstudent.services.sync.backend.SyncBatchCodec
import com.deepstudent.services.sync.backend.SyncWireCodec
import com.deepstudent.services.sync.data.capture.SyncCapturePreflight
import com.deepstudent.services.sync.data.capture.SyncCaptureRegistry
import com.deepstudent.services.sync.data.capture.SyncCategory
import com.deepstudent.services.sync.data.changelog.ChangeLogReader
import java.io.File
import java.nio.file.Files
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class DesktopChangeLogOracleDeviceTest {
    @Test fun frozenDesktopVfsResourcesProduceSameChangesAndWireOrder() = compareScenario(0)
    @Test fun frozenDesktopChatCompositeProduceSameChangesAndWireOrder() = compareScenario(1)
    @Test fun repairedDesktopVfsPluginDeletesProduceSameChangesAndWireOrder() = compareScenario(2)

    private fun compareScenario(index: Int) {
        val instrument = InstrumentationRegistry.getInstrumentation()
        val context = instrument.targetContext
        val source = BundledMigrationSource(context)
        val registry = SyncCaptureRegistry.load(source)
        val fixture = context.cacheDir.resolve("s2-change-log-oracle/fixture.json").readBytes()
        val expectedSha = checkNotNull(InstrumentationRegistry.getArguments().getString("oracleSha256"))
        assertEquals(expectedSha, Digests.sha256Hex(fixture))
        val root = Files.createTempDirectory(context.cacheDir.toPath(), "s205-oracle-$index-").toFile()
        try {
            val document = JsonValue.parseObject(fixture.decodeToString(throwOnInvalidSequence = true))
            assertEquals("e6b2657fd59014a73f5bbb1d14c2db732cf23e87", text(document["desktopCommit"]))
            val migration = document["migration"] as JsonValue.Object
            val frozenPath = "config/schema/final-lock.json"
            val frozenSha = "5fd1b3a4bd4288ce577c3f17677c9ca6accfab56c03a80130a8797baed5c08e8"
            val supplementPath = "config/schema/s2-04-repair.json"
            val supplementSha = "ec1ce53307dd93c45584c55b7fc38c6e52a3b28b3a7fa5f0514b56c78f0b283c"
            val repairPath = "config/schema/coordinated/vfs/V20261120__fix_plugin_delete_trigger.sql"
            assertEquals(frozenPath, text(migration["frozenLockPath"]))
            assertEquals(frozenSha, text(migration["frozenLockSha256"]))
            assertEquals(frozenSha, Digests.sha256Hex(source.read(frozenPath)))
            assertEquals(supplementPath, text(migration["supplementLockPath"]))
            assertEquals(supplementSha, text(migration["supplementLockSha256"]))
            val supplementBytes = source.read(supplementPath)
            assertEquals(supplementSha, Digests.sha256Hex(supplementBytes))
            val supplement = JsonValue.parseObject(supplementBytes.decodeToString(throwOnInvalidSequence = true))
            val anchor = supplement["anchor"] as JsonValue.Object
            val repair = supplement["migration"] as JsonValue.Object
            val after = supplement["after"] as JsonValue.Object
            assertEquals(frozenPath, text(anchor["file"]))
            assertEquals(frozenSha, text(anchor["sha256"]))
            assertEquals("vfs", text(repair["database"]))
            assertEquals(20261120L, (after["version"] as JsonValue.Number).asLongOrNull())
            assertEquals(76L, (after["migrationCount"] as JsonValue.Number).asLongOrNull())
            assertEquals(20261120L, (repair["version"] as JsonValue.Number).asLongOrNull())
            assertEquals(repairPath, text(repair["file"]))
            assertEquals(text(repair["sha256"]), text(migration["repairSha256"]))
            assertEquals(text(repair["refineryChecksum"]), text(migration["checksum"]))
            assertEquals(repairPath, text(migration["repairPath"]))
            assertEquals(text(migration["repairSha256"]), Digests.sha256Hex(source.read(repairPath)))
            assertEquals(20261120L, (migration["version"] as JsonValue.Number).asLongOrNull())
            assertEquals("fix_plugin_delete_trigger", text(migration["name"]))
            val scenarios = array(document["scenarios"]).map { it as JsonValue.Object }
            assertEquals(listOf("vfs" to "resources", "chat_v2" to "chat_v2_session_mistakes", "vfs" to "portable_plugin_records"),
                scenarios.map { text(it["database"]) to text(it["table"]) })
            val expectedCounts = listOf(7, 4, 7)
            assertEquals(expectedCounts, scenarios.map { array(it["expectedChanges"]).size })
            val oldHashes = listOf("29b2437839d7257ecd8ee664dcc77c2c13d9a651c6fe35d7a2f5defe5fddbec9",
                "fa295a95025d2f9c9a5bfe79b00858560e466e935c9b9757913b58a99c60954e")
            oldHashes.forEachIndexed { i, hash ->
                assertEquals(hash, Digests.sha256HexUtf8(array(scenarios[i]["expectedChanges"]).map(::text).joinToString("\n")))
            }
            val scenario = scenarios[index]
            val database = SharedDatabase.entries.single { it.id == text(scenario["database"]) }
            // Each scenario gets its own physical file, including the two different VFS scenarios.
            DatabaseRegistry.openShared(File(root, "${database.id}-$index.db"), database, source).use { db ->
                val report = checkNotNull(db.migrationReport)
                assertEquals(0, report.fromVersion)
                assertEquals(if (database == SharedDatabase.VFS) 20261120 else 20261111, report.toVersion)
                assertEquals(if (database == SharedDatabase.VFS) 76 else 30, report.appliedCount)
                if (database == SharedDatabase.VFS) {
                    assertEquals(text(after["fingerprint"]), report.fingerprint)
                    val head = db.query("SELECT version,name,checksum FROM refinery_schema_history ORDER BY version DESC LIMIT 1") {
                        Triple(it.long(0), it.text(1), it.text(2))
                    }.single()
                    assertEquals(Triple(20261120L, "fix_plugin_delete_trigger", text(migration["checksum"])), head)
                }
                val table = text(scenario["table"])
                SyncCapturePreflight.validate(database, db, registry).requireReady(setOf(table))
                db.transaction { array(scenario["statements"]).forEach { execute(text(it)) } }
                val expected = array(scenario["expectedChanges"]).map(::text)
                val reader = ChangeLogReader(db, database.id, { name ->
                    registry.find(database, name)?.takeIf { it.category == SyncCategory.RowSync }?.logicalPrimaryKey
                })
                val pendingBefore = db.query("SELECT id,sync_version FROM __change_log ORDER BY id") { it.long(0) to it.long(1) }
                val actual = readPages(reader, table)
                verifyWire(expected, actual)
                if (index == 2) verifyPluginSequence(actual)
                assertEquals(pendingBefore, db.query("SELECT id,sync_version FROM __change_log ORDER BY id") { it.long(0) to it.long(1) })
                Log.i("DstuS205Oracle", "database=${database.id} table=$table changes=${actual.size} pageLimit=2 v3=v4=matched " +
                    "changesSha256=${Digests.sha256HexUtf8(actual.joinToString("\n"))} fixtureSha256=$expectedSha")
            }
        } finally {
            root.deleteRecursively()
        }
    }

    private fun verifyPluginSequence(actual: List<String>) {
        val changes = actual.map(JsonValue::parseObject)
        val reinserted = """["plugin.s2","state","reinsert"]"""
        val removed = """["plugin.s2","state","removed"]"""
        assertEquals(listOf("Insert", "Update", "Delete", "Insert", "Update", "Insert", "Delete"),
            changes.map { text(it["operation"]) })
        assertEquals(List(5) { reinserted } + List(2) { removed }, changes.map { text(it["record_id"]) })
        assertEquals((9007199254740993L..9007199254740999L).toList(),
            changes.map { (it["change_log_id"] as JsonValue.Number).asLongOrNull() })
        for (index in listOf(0, 1, 3, 4)) {
            val data = changes[index]["data"] as JsonValue.Object
            assertEquals(reinserted, text(data["id"]))
            assertEquals("""{"counter":9007199254740993,"empty":"","nothing":null,"state":"current"}""", text(data["data_json"]))
            assertEquals(JsonValue.Null, data["deleted_at"])
            assertEquals(9007199254740993L, (data["local_version"] as JsonValue.Number).asLongOrNull())
            assertEquals("""{"future":{"value":null,"hlc":"2026-01-01T00:00:00Z","dev":"fixture","seq":9007199254740993}}""",
                text(data["unknown_json"]))
        }
        assertTrue(listOf(2, 5, 6).all { changes[it]["data"] == JsonValue.Null })
    }

    private fun readPages(reader: ChangeLogReader, table: String): List<String> = reader.withSnapshot { _, snapshot ->
        buildList {
            var cursor: Long? = null
            do {
                val page = snapshot.page(cursor, 2)
                assertTrue(page.changes.size <= 2)
                if (page.hasMore) assertTrue(checkNotNull(page.nextAfterId) > (cursor ?: Long.MIN_VALUE))
                addAll(page.changes.map { SyncWireCodec.encodeChange(it).decodeToString() }
                    .filter { text(JsonValue.parseObject(it)["table_name"]) == table })
                cursor = page.nextAfterId
            } while (page.hasMore)
        }
    }

    private fun verifyWire(expected: List<String>, actual: List<String>) {
        assertEquals(expected, actual)
        val changes = actual.map(JsonValue::parseObject)
        listOf(3u, 4u).forEach { version ->
            val operation = if (version == 4u) "oracle-operation" else null
            val batch = SyncBatch(changes, "fixture-device", version, ULong.MAX_VALUE, "fixture-device", operation)
            val decoded = SyncBatchCodec.decode(SyncBatchCodec.encode(batch))
            assertEquals(version, decoded.formatVersion)
            assertEquals(ULong.MAX_VALUE, decoded.sourceSeq)
            assertEquals(operation, decoded.operationId)
            assertEquals(expected, decoded.changes.map { it.literal() })
        }
    }

    private fun array(value: JsonValue?): List<JsonValue> = (value as JsonValue.Array).values
    private fun text(value: JsonValue?): String = (value as JsonValue.Text).value
}
