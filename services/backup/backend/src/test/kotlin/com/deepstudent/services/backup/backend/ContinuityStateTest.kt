package com.deepstudent.services.backup.backend

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import org.junit.Assert.*
import org.junit.Test

class ContinuityStateTest {
    private val fixture = ContinuityFixture

    private fun upload(value: JsonElement): JsonObject = fixture.root.removeKey("expected").changeState("uploadSeq") { rows ->
        rows.changeRow(0) { it.change("last_seq", value) }
    }

    @Test fun safeJsonNumbersAndExactI64DecimalStringsRemainExact() {
        val accepted = listOf(
            JsonPrimitive(0) to 0L, JsonPrimitive(JSON_SAFE_INTEGER) to JSON_SAFE_INTEGER,
            JsonPrimitive("9007199254740992") to 9007199254740992L,
            JsonPrimitive("9223372036854775807") to Long.MAX_VALUE,
        )
        for ((value, expected) in accepted) {
            val result = fixture.validate(upload(value)) as ContinuityValidation.Matched
            assertEquals(expected, result.state.upload.lastSeq)
        }
        val rejected = listOf(
            JsonPrimitive(-1), JsonPrimitive(9007199254740992L), JsonPrimitive("9223372036854775808"),
            JsonPrimitive("-1"), JsonPrimitive("+1"), JsonPrimitive("01"), JsonPrimitive("1.0"),
            JsonPrimitive("1e3"), JsonPrimitive(" 1"), JsonPrimitive(""), JsonPrimitive(true),
            Json.parseToJsonElement("1.0"), Json.parseToJsonElement("1e3"),
        )
        rejected.forEach { fixture.rejected(upload(it)) }
    }

    @Test fun everySequenceColumnUsesExactI64Rules() {
        val columns = listOf(
            "uploadSeq" to "last_seq", "consumeCursor" to "last_seq",
            "tombstoneWatermark" to "last_applied_offset", "tombstoneEventPublish" to "seq",
        )
        for ((table, column) in columns) {
            val base = fixture.root.removeKey("expected")
            val max = base.changeState(table) { rows -> rows.changeRow(0) { it.change(column, JsonPrimitive("9223372036854775807")) } }
            assertTrue(fixture.validate(max) is ContinuityValidation.Matched)
            for (bad in listOf(JsonPrimitive("9223372036854775808"), JsonPrimitive(-1), JsonPrimitive(1.5))) {
                val invalid = base.changeState(table) { rows -> rows.changeRow(0) { it.change(column, bad) } }
                fixture.rejected(invalid)
            }
        }
    }

    @Test fun malformedRowsWrongInstancesAndWrongWritersFailClosed() {
        val examples = listOf(
            fixture.root.changeState("instanceBinding") { JsonArray(emptyList()) },
            fixture.root.changeState("uploadSeq") { JsonArray(emptyList()) },
            fixture.root.changeState("deviceHistory") { JsonArray(listOf(JsonObject(emptyMap()))) },
            fixture.root.changeState("consumeCursor") { JsonArray(listOf(JsonPrimitive(1))) },
            fixture.root.changeState("uploadSeq") { it.changeRow(0) { row -> row.change("device_id", JsonPrimitive("different")) } },
            fixture.root.changeState("uploadSeq") { it.changeRow(0) { row -> row.change("instance_id", JsonPrimitive("different")) } },
            fixture.root.changeState("consumeCursor") { it.changeRow(0) { row -> row.change("instance_id", JsonPrimitive("different")) } },
            fixture.root.changeState("tombstoneWatermark") { it.changeRow(0) { row -> row.change("instance_id", JsonPrimitive("different")) } },
            fixture.root.changeState("tombstoneEventPublish") { it.changeRow(0) { row -> row.change("device_id", JsonPrimitive("different")) } },
            fixture.root.changeState("legacyProcessedKey") { it.changeRow(0) { row -> row.change("instance_id", JsonPrimitive("different")) } },
            fixture.root.changeState("consumeCursor") { it.changeRow(0) { row -> row.change("uploader_device_id", JsonPrimitive("  ")) } },
            fixture.root.changeState("tombstoneWatermark") { it.changeRow(0) { row -> row.change("kind", JsonPrimitive("")) } },
            fixture.root.changeState("tombstoneEventPublish") { it.changeRow(0) { row -> row.change("operation_id", JsonPrimitive("")) } },
            fixture.root.changeState("tombstoneEventPublish") { it.changeRow(0) { row -> row.change("seq", JsonPrimitive(0)) } },
            fixture.root.changeState("legacyProcessedKey") { it.changeRow(0) { row -> row.change("object_key", JsonPrimitive("")) } },
            fixture.root.changeObject("state") { it.removeKey("consumeCursor") },
        )
        examples.forEach { fixture.rejected(it) }
    }

    @Test fun rejectsDuplicatesAccordingToDatabasePrimaryAndUniqueKeys() {
        for (table in listOf("instanceBinding", "uploadSeq", "consumeCursor", "tombstoneWatermark", "tombstoneEventPublish", "legacyProcessedKey")) {
            val root = fixture.root.removeKey("expected").changeState(table) { rows -> JsonArray(rows + rows.first()) }
            fixture.rejected(root)
        }
        val sameSequenceOtherOperation = fixture.root.removeKey("expected").changeState("tombstoneEventPublish") { rows ->
            val duplicate = (rows.first() as JsonObject).change("operation_id", JsonPrimitive("other-op"))
            JsonArray(rows + duplicate)
        }
        fixture.rejected(sameSequenceOtherOperation, ContinuityFailure.INVALID_STATE)
    }

    @Test fun expectedSelfCheckAndReceiptAreNotTrustedOrPreapplied() {
        val root = fixture.root
        fixture.rejected(root.changeObject("expected") { it.change("duplicate_rejected", JsonPrimitive(false)) })
        fixture.rejected(root.changeObject("expected") { expected ->
            expected.changeObject("state") { state -> state.change("consumeCursor", JsonArray(emptyList())) }
        })
        val key = "continuity-takeover/${fixture.trust.archive.manifest.snapshotEpoch}/${fixture.trust.archive.sha256}"
        val processed = root.removeKey("expected").changeState("legacyProcessedKey") { rows ->
            JsonArray(rows + (rows.first() as JsonObject).change("object_key", JsonPrimitive(key)))
        }
        fixture.rejected(processed, ContinuityFailure.INVALID_STATE)
        assertTrue(fixture.validate(root.removeKey("expected")) is ContinuityValidation.Matched)
    }

    @Test fun receiptComponentsCannotContainPathSeparators() {
        for (epoch in listOf("a/b", "..", "a\\b", "a b")) {
            val input = fixture.root.changeObject("snapshot") { it.change("snapshot_epoch", JsonPrimitive(epoch)) }
            val bytes = fixture.input(input)
            val trust = fixture.testTrust(bytes)
            val manifest = trust.archive.manifest
            val matchingManifest = manifest.copy(identity = manifest.identity.copy(snapshotEpoch = epoch))
            fixture.rejected(ContinuityValidator.validate(bytes, trust.copy(archive = trust.archive.copy(manifest = matchingManifest))))
        }
    }
}
