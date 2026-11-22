package com.deepstudent.services.sync.backend

import com.deepstudent.core.common.json.JsonValue
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class SyncLosslessBatchTest {
    @Test fun v3AndV4KeepLosslessDataAndFrozenOptionalFieldSemantics() {
        val input = """{"table_name":"notes","record_id":"","operation":"update","data":{"big":9007199254740993,"min":-9223372036854775808,"max":9223372036854775807,"future":18446744073709551615,"null":null,"empty":"","nested":{"extra":[null,"",1.0]}},"changed_at":"","change_log_id":-9223372036854775808,"database_name":"","suppress_change_log":null,"unknown_envelope":"ignored"}"""
        val change = JsonValue.parseObject(SyncWireCodec.encodeChange(input.toByteArray()).decodeToString())
        assertFalse(change.containsKey("unknown_envelope")) // frozen wire schema ignores unknown envelope fields
        assertEquals(JsonValue.bool(false), change["suppress_change_log"])
        assertEquals(JsonValue.text(""), change["database_name"])
        val data = change["data"] as JsonValue.Object
        assertEquals("9007199254740993", (data["big"] as JsonValue.Number).literal)
        assertEquals(Long.MIN_VALUE, (data["min"] as JsonValue.Number).asLongOrNull())
        assertEquals(Long.MAX_VALUE, (data["max"] as JsonValue.Number).asLongOrNull())
        assertEquals("18446744073709551615", (data["future"] as JsonValue.Number).literal)
        assertEquals(JsonValue.Null, data["null"])
        assertEquals(JsonValue.text(""), data["empty"])
        assertFalse(data.containsKey("missing"))
        for (version in listOf(3u, 4u)) {
            val batch = SyncBatch(listOf(change), "device", version, ULong.MAX_VALUE, "device", if (version == 4u) "op" else null)
            val decoded = SyncBatchCodec.decode(SyncBatchCodec.encode(batch))
            assertEquals(version, decoded.formatVersion)
            assertEquals(ULong.MAX_VALUE, decoded.sourceSeq)
            assertEquals(change, decoded.changes.single())
            assertEquals(Long.MIN_VALUE, (decoded.changes.single()["change_log_id"] as JsonValue.Number).asLongOrNull())
        }
    }

    @Test fun absentNullAndEmptyOptionalStringsFollowFrozenWire() {
        val prefix = """{"table_name":"notes","record_id":"n","operation":"Insert","changed_at":"t""""
        val absent = JsonValue.parseObject(SyncWireCodec.encodeChange("$prefix}".toByteArray()).decodeToString())
        val explicitNull = JsonValue.parseObject(SyncWireCodec.encodeChange("$prefix,\"database_name\":null}".toByteArray()).decodeToString())
        val empty = JsonValue.parseObject(SyncWireCodec.encodeChange("$prefix,\"database_name\":\"\"}".toByteArray()).decodeToString())
        assertEquals(JsonValue.Null, absent["data"])
        assertFalse(absent.containsKey("database_name"))
        assertFalse(explicitNull.containsKey("database_name"))
        assertTrue(empty.containsKey("database_name"))
        assertEquals(JsonValue.text(""), empty["database_name"])
    }
}
