package com.deepstudent.services.sync.data.s230

import com.deepstudent.core.common.json.JsonValue
import com.deepstudent.platform.database.SharedDatabase
import com.deepstudent.services.sync.data.apply.IncomingDomain
import com.deepstudent.services.sync.data.apply.RegisteredIncomingRows
import com.deepstudent.services.sync.data.apply.RegisteredSqlRowApplier
import com.deepstudent.services.sync.data.changelog.SqlPublicationSource
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Test
import java.nio.file.Files

/** Real migrations, production incoming vault, trigger and publication source on both peers. */
class S230PublicationClockTest {
    private val root = Files.createTempDirectory("s230-publication-clock-").toFile()

    @After fun cleanup() {
        root.deleteRecursively()
    }

    @Test fun nonVfsLocalRecapturePreservesFutureValueAndExactHlcDeviceSequence() {
        val left = root.resolve("left").apply { mkdir() }
        val right = root.resolve("right").apply { mkdir() }
        S230MigratedFixture(left, s230HostMigrationSource()).use { source ->
            S230MigratedFixture(right, s230HostMigrationSource()).use { target ->
                val cases = listOf(
                    Case(SharedDatabase.CHAT_V2, "chat_v2_sessions", "session", "title", "edited chat"),
                    Case(SharedDatabase.MISTAKES, "mistakes", "mistake", "user_question", "edited question"),
                    Case(SharedDatabase.LLM_USAGE, "llm_usage_logs", "usage", "model", "edited model"),
                )
                for (case in cases) roundtrip(source, target, case)
            }
        }
    }

    private fun roundtrip(source: S230MigratedFixture, target: S230MigratedFixture, case: Case) {
        val db = source.db(case.owner)
        val record = s230Batch().single { row ->
            val json = JsonValue.parseObject(row.decodeToString())
            json["database_name"] == JsonValue.text(case.owner.id) && json["table_name"] == JsonValue.text(case.table)
        }
        val root = JsonValue.parseObject(record.decodeToString())
        val data = root["data"] as JsonValue.Object
        val clock = JsonValue.parseObject("""{"hlc":"000000000000010-00001","dev":"desktop-peer","seq":9007199254740993}""")
        val incoming = JsonValue.Object.from(
            root.asMap() + (
                "data" to JsonValue.Object.from(
                    data.asMap() + mapOf(
                        "future" to JsonValue.integer(9007199254740993L),
                        "__sync_field_deltas" to JsonValue.obj("future" to clock),
                    ),
                )
                ),
        ).literal().toByteArray()
        val applier = applier(source, case)
        applier.validate(listOf(incoming))
        db.transaction { applier.apply(this, listOf(incoming)) }
        db.execute("UPDATE __change_log SET sync_version=1 WHERE COALESCE(sync_version,0)=0")
        db.execute(
            "UPDATE ${case.table} SET ${case.column}=? WHERE id=?",
            listOf(
                com.deepstudent.platform.database.SqlValue.Text(case.value),
                com.deepstudent.platform.database.SqlValue.Text(case.id),
            ),
        )
        val keys = source.registry.rowSyncKeys(case.owner)
        val captured = SqlPublicationSource(db, case.owner, keys).pending()
            .map { JsonValue.parseObject(it.decodeToString()) }
            .single { it["table_name"] == JsonValue.text(case.table) && it["record_id"] == JsonValue.text(case.id) }
        val capturedData = captured["data"] as JsonValue.Object
        assertEquals(JsonValue.text(case.value), capturedData[case.column])
        assertEquals(JsonValue.integer(9007199254740993L), capturedData["future"])
        assertEquals(clock, (capturedData["__sync_field_deltas"] as JsonValue.Object)["future"])

        val outgoing = captured.literal().toByteArray()
        val peer = target.db(case.owner)
        val peerApplier = applier(target, case)
        peerApplier.validate(listOf(outgoing))
        peer.transaction { peerApplier.apply(this, listOf(outgoing)) }
        val vault = peer.query(
            "SELECT fields_json FROM __sync_unknown_fields WHERE table_name=? AND record_id=?",
            listOf(com.deepstudent.platform.database.SqlValue.Text(case.table), com.deepstudent.platform.database.SqlValue.Text(case.id)),
        ) { JsonValue.parseObject(it.text(0)) }.single()
        assertEquals(JsonValue.Object.from(clock.asMap() + ("value" to JsonValue.integer(9007199254740993L))), vault["future"])
    }

    private fun applier(f: S230MigratedFixture, case: Case) = RegisteredSqlRowApplier(
        RegisteredIncomingRows(case.owner, f.registry, listOf(IncomingDomain(case.owner, "clock", listOf(case.table)))),
    )
    private data class Case(val owner: SharedDatabase, val table: String, val id: String, val column: String, val value: String)
}
