package com.deepstudent.services.sync.backend

import com.deepstudent.core.common.id.Digests
import com.deepstudent.core.common.json.JsonValue
import com.deepstudent.services.sync.api.SyncError
import java.io.File
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class SyncGoldenTest {
    private val golden = JsonValue.parseObject(File("../../../config/baseline/sync-golden.json").readText())

    @Test fun changesMatchFrozenBytesAndHashes() {
        for (value in array(golden["changes"])) {
            val sample = obj(value)
            val encoded = SyncWireCodec.encodeChange(text(obj(sample["input"])["text"]).toByteArray())
            val expected = obj(sample["expected"])
            assertEquals(text(sample["id"]), text(expected["text"]), encoded.decodeToString())
            assertEquals(text(expected["sha256"]), Digests.sha256Hex(encoded))
        }
    }

    @Test fun manifestsPreserveFullWidthNumbersAndFrozenSerialization() {
        val sample = obj(obj(golden["manifests"])["sync"])
        val input = text(obj(sample["input"])["text"]).toByteArray()
        val encoded = SyncWireCodec.encodeSyncManifest(input)
        val expected = obj(sample["encoded"])
        assertEquals(text(expected["text"]), encoded.decodeToString())
        assertEquals(text(expected["sha256"]), Digests.sha256Hex(encoded))
        assertTrue(encoded.decodeToString().contains("18446744073709551615"))
    }

    @Test fun batchAndFormatReuseFrozenInputAndKeepUnsignedBoundary() {
        val envelopes = obj(golden["envelopes"])
        val sample = obj(envelopes["batch"])
        val batch = SyncBatchCodec.decode(text(obj(sample["input"])["text"]).toByteArray())
        assertEquals(ULong.MAX_VALUE, batch.sourceSeq)
        assertEquals(4u, batch.formatVersion)
        assertEquals(1, batch.changes.size)
        val data = obj(batch.changes.single()["data"])
        assertEquals(JsonValue.integer(2), data["x"])
        assertEquals("9007199254740993", (data["big"] as JsonValue.Number).literal)
        assertEquals(batch.sourceSeq, SyncBatchCodec.decode(SyncBatchCodec.encode(batch)).sourceSeq)
        val expectedFormat = obj(obj(envelopes["format"])["expected"])
        val bytes = text(expectedFormat["text"]).toByteArray()
        val encoded = SyncFormatCodec.encode(SyncFormatCodec.decode(bytes))
        assertEquals(text(expectedFormat["sha256"]), Digests.sha256Hex(encoded))
        for (value in array(obj(golden["formats"])["cases"])) {
            val format = obj(value)
            val parsed = SyncFormatCodec.decode(text(format["input"]).toByteArray())
            assertEquals(format["expected"], parsed)
        }
    }

    @Test fun malformedNumbersStringsAndFutureFormatFailClosed() {
        val base = """{"table_name":"notes","record_id":"n","operation":"Update","changed_at":"t"}"""
        val invalidFields = listOf("\"change_log_id\":9223372036854775808", "\"source_seq\":18446744073709551616",
            "\"source_seq\":-1", "\"source_seq\":1.0")
        for (field in invalidFields) rejects { SyncWireCodec.encodeChange((base.dropLast(1) + "," + field + "}").toByteArray()) }
        rejects { SyncWireCodec.encodeChange(base.replace("\"notes\"", "3").toByteArray()) }
        val future = SyncFormatCodec.decode("""{"format_version":5,"min_client":4}""".toByteArray())
        try { SyncFormatCodec.requireCompatible(future); error("future format accepted") }
        catch (failure: SyncFailure) { assertEquals(SyncError.INCOMPATIBLE_FORMAT, failure.error) }
        rejects { SyncFormatCodec.decode("""{"min_client":3,"min_reader_version":3}""".toByteArray()) }
    }

    private fun rejects(block: () -> Unit) {
        try { block(); error("invalid wire accepted") }
        catch (failure: SyncFailure) { assertEquals(SyncError.INVALID_REPOSITORY, failure.error) }
    }

    private fun obj(value: JsonValue?): JsonValue.Object = value as JsonValue.Object
    private fun array(value: JsonValue?): List<JsonValue> = (value as JsonValue.Array).values
    private fun text(value: JsonValue?): String = (value as JsonValue.Text).value
}
