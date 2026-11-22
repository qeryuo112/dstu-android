package com.deepstudent.features.plugins.api

import com.deepstudent.core.common.error.DstuException
import com.deepstudent.core.common.json.JsonFields
import com.deepstudent.core.common.json.JsonValue
import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test

class PluginDataContractTest {
    private val fixture = RepoFixtures.json("fixtures/plugin-data.json")
    private val records = (fixture["records"] as JsonValue.Array).values.map { it as JsonValue.Object }

    @Test
    fun frozenRecordsDecodeWithCanonicalIdentityAndBigIntegers() {
        for (value in records) {
            val record = PluginRecordCodec.decode(value)
            assertEquals(JsonFields.string(value, "id"), record.id)
            assertEquals(JsonFields.string(value, "owner_id"), record.ownerId.value)
            assertEquals(JsonFields.string(value, "namespace"), record.namespace.value)
            assertEquals(JsonFields.string(value, "record_id"), record.recordId.value)
            assertEquals(record, PluginRecordCodec.decode(PluginRecordCodec.encode(record)))
        }
        val compat = records.first { JsonFields.string(it, "record_id") == "compat" }
        val record = PluginRecordCodec.decode(compat)
        assertTrue(record.dataJson.contains("9007199254740993"))
        assertTrue(record.data.canonical().contains("9007199254740993"))
    }

    @Test
    fun capabilityAndQuotaMatchFrozenFixture() {
        val capability = fixture["capability"] as JsonValue.Object
        val parsed = PluginCapability(
            ownerId = PluginOwnerId.parse(JsonFields.string(capability, "owner_id")),
            packageVersion = PluginPackageVersion.parse(JsonFields.string(capability, "package_version")),
            sourceHash = PluginSourceHash.parse(JsonFields.string(capability, "source_hash")),
            schemaVersion = JsonFields.integer(capability, "schema_version").toInt(),
            deviceId = PluginDeviceId.parse(JsonFields.string(capability, "device_id")),
        )
        assertEquals("plugin", parsed.ownerId.ownerKind)
        val quota = fixture["quota"] as JsonValue.Object
        assertEquals(PluginDataContract.MAX_DEPTH, JsonFields.integer(quota, "depth").toInt())
        assertEquals(PluginDataContract.MAX_RECORD_BYTES, JsonFields.integer(quota, "recordBytes"))
        assertEquals(PluginDataContract.MAX_OWNER_RECORDS, JsonFields.integer(quota, "ownerRecords").toInt())
        assertEquals(PluginDataContract.MAX_OWNER_BYTES, JsonFields.integer(quota, "ownerBytes"))
    }

    @Test
    fun unknownRoundTripAndUnknownEnvelopeArePreserved() {
        val unknown = PluginUnknownFields.decode(
            """{"future":{"value":{"n":{"${'$'}bigint":"9007199254740993"},"nullable":null},
               "hlc":"2026-11-15T16:00:00Z","dev":"device-a","seq":0}}""",
        )
        assertEquals(
            """{"future":{"dev":"device-a","hlc":"2026-11-15T16:00:00Z","seq":0,"value":{"n":{"${'$'}bigint":"9007199254740993"},"nullable":null}}}""",
            unknown.canonical(),
        )
        val fixtureUnknown = fixture["unknownRoundTrip"] as JsonValue.Object
        assertEquals(
            (fixtureUnknown["retained"] as JsonValue.Object).canonical(),
            JsonValue.parse("""{"n":{"${'$'}bigint":"9007199254740993"},"nullable":null}""").canonical(),
        )
    }

    @Test
    fun forgedOwnerTupleAndUnsafeSeqAreRejected() {
        val first = records.first()
        val forged = replace(first, "id", JsonValue.text("[\"plugin.other\",\"prefs\",\"forged\"]"))
        assertEquals(
            "INVALID_LOGICAL_ID",
            assertThrows(DstuException::class.java) { PluginRecordCodec.decode(forged) }.error.code,
        )
        assertEquals(
            "INTEGER_NOT_JSON_SAFE",
            assertThrows(DstuException::class.java) {
                PluginUnknownFields.decode(
                    """{"future":{"value":null,"hlc":"x","dev":"d","seq":9007199254740992}}""",
                )
            }.error.code,
        )
    }

    @Test
    fun schemaAndMinWriterCannotDowngrade() {
        val record = record(schemaVersion = 2, minWriter = 2)
        val existing = record(schemaVersion = 3, minWriter = 1)
        val existingMin2 = record(schemaVersion = 3, minWriter = 2)
        assertEquals(
            "SCHEMA_DOWNGRADE",
            assertThrows(DstuException::class.java) {
                PluginApplyValidator.validate(record, PluginApplyContext(existing, localSchemaVersion = 3))
            }.error.code,
        )
        val lowerMinimum = record(schemaVersion = 3, minWriter = 1)
        assertEquals(
            "MIN_WRITER_DOWNGRADE",
            assertThrows(DstuException::class.java) {
                PluginApplyValidator.validate(lowerMinimum, PluginApplyContext(existingMin2, localSchemaVersion = 3))
            }.error.code,
        )
    }

    @Test
    fun nullPayloadIsAcceptedButNonFiniteNumberIsRejected() {
        val nullRecord = record(dataJson = "null")
        assertEquals(JsonValue.Null, nullRecord.data)
        assertThrows(DstuException::class.java) {
            JsonValue.parse("""{"n":NaN}""")
        }
        assertThrows(DstuException::class.java) {
            JsonValue.parse("""{"n":Infinity}""")
        }
    }

    private fun record(
        dataJson: String = "{}",
        schemaVersion: Int = 1,
        minWriter: Int = 1,
    ): PluginRecord = PluginRecord(
        ownerId = PluginOwnerId.parse("plugin.public"),
        namespace = PluginNamespace.parse("prefs"),
        recordId = PluginRecordId.parse("item"),
        packageVersion = PluginPackageVersion.parse("1.0.0"),
        sourceHash = PluginSourceHash.parse("a".repeat(64)),
        schemaVersion = schemaVersion,
        minWriterSchema = minWriter,
        state = PluginRecordState(
            dataJson = dataJson,
            unknownJson = "{}",
            deletedAt = null,
            deviceId = PluginDeviceId.parse("device-a"),
            localVersion = 1,
            updatedAt = "2026-11-15T16:00:00Z",
        ),
    )

    private fun replace(base: JsonValue.Object, key: String, value: JsonValue): JsonValue.Object {
        val builder = JsonValue.Object.Builder()
        for (field in base.keys()) builder.put(field, if (field == key) value else base[field] ?: JsonValue.Null)
        return builder.build()
    }
}
