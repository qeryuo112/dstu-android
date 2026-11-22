package com.deepstudent.services.backup.backend

import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import org.junit.Assert.assertTrue
import org.junit.Test

class ContinuityJsonTest {
    private val fixture = ContinuityFixture

    @Test fun strictFormatAndVersionAndRequiredFields() {
        val cases = listOf(
            fixture.root.change("format", JsonPrimitive("dstu.legacy.mobile.continuity2")),
            fixture.root.change("formatVersion", JsonPrimitive(2)),
            fixture.root.change("formatVersion", JsonPrimitive("1")),
            fixture.root.change("status", JsonPrimitive("other")),
            fixture.root.removeKey("state"),
            fixture.root.change("state", JsonPrimitive("object")),
            fixture.root.changeObject("key_policy") { it.change("material_in_archive", JsonPrimitive(true)) },
            fixture.root.changeObject("key_policy") { it.change("manifest", JsonPrimitive("included_local")) },
        )
        cases.forEach { fixture.rejected(it) }
    }

    @Test fun rejectsUnknownAndSensitiveFieldsAtEveryLevel() {
        val secret = JsonPrimitive("do-not-copy-this-secret")
        val cases = listOf(
            fixture.root.change("password", secret),
            fixture.root.changeObject("snapshot") { it.change("secret", secret) },
            fixture.root.changeObject("snapshot") { it.changeObject("schema_versions") { schema -> schema.change("extra", JsonPrimitive(1)) } },
            fixture.root.changeObject("source_installation") { it.change("token", secret) },
            fixture.root.changeObject("source_installation") { it.changeObject("identitySource") { src -> src.change("privateKey", secret) } },
            fixture.root.changeObject("identity") { it.change("credential", secret) },
            fixture.root.changeObject("state") { it.change("pending", JsonArray(emptyList())) },
            fixture.root.changeState("uploadSeq") { rows -> rows.changeRow(0) { it.change("ciphertext", secret) } },
            fixture.root.changeObject("expected") { it.change("password", secret) },
            fixture.root.changeObject("key_policy") { it.change("material", secret) },
            fixture.root.changeObject("generator") { it.change("secret", secret) },
        )
        cases.forEach { fixture.rejected(it) }
    }

    @Test fun malformedDuplicateEscapedKeysAndInvalidUtf8FailClosed() {
        val original = fixture.bytes.toString(Charsets.UTF_8)
        val duplicateTop = original.replaceFirst("\"format\":", "\"format\":\"other\",\"format\":")
        val escapedDuplicate = original.replaceFirst("\"format\":", "\"\\u0066ormat\":\"other\",\"format\":")
        val nestedDuplicate = original.replaceFirst("\"device_id\":", "\"device_id\":\"bad\",\"device_id\":")
        for (raw in listOf(duplicateTop, escapedDuplicate, nestedDuplicate, original.dropLast(4), "[]", "{\"state\": NaN}")) {
            fixture.rejected(fixture.validateRaw(raw))
        }
        val invalidUtf8 = byteArrayOf(0xc3.toByte(), 0x28)
        fixture.rejected(ContinuityValidator.validate(invalidUtf8, fixture.testTrust(invalidUtf8)), ContinuityFailure.INVALID_JSON)
    }

    @Test fun resourceAndTextBoundsRejectBeforeReturningState() {
        val huge = ByteArray(CONTINUITY_MAX_BYTES + 1)
        fixture.rejected(ContinuityValidator.validate(huge, fixture.trust), ContinuityFailure.TOO_LARGE)
        fixture.rejected(fixture.root.change("note", JsonPrimitive("x".repeat(CONTINUITY_MAX_STRING + 1))), ContinuityFailure.TOO_LARGE)
        fixture.rejected(fixture.root.changeObject("identity") { it.change("device_id", JsonPrimitive("bad\u0000id")) })
        fixture.rejected(fixture.root.changeObject("identity") { it.change("device_id", JsonPrimitive("bad\ud800")) })
        val many = JsonArray(List(CONTINUITY_MAX_ROWS + 1) { JsonObject(emptyMap()) })
        fixture.rejected(fixture.root.removeKey("expected").changeState("consumeCursor") { many }, ContinuityFailure.TOO_LARGE)
        val nested = "[".repeat(70) + "0" + "]".repeat(70)
        fixture.rejected(fixture.validateRaw(nested), ContinuityFailure.INVALID_JSON)
        assertTrue(fixture.bytes.size < CONTINUITY_MAX_BYTES)
    }
}
