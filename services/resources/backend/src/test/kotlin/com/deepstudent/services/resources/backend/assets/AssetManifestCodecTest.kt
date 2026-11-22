package com.deepstudent.services.resources.backend.assets

import com.deepstudent.services.resources.api.assets.*
import org.junit.Assert.*
import org.junit.Test

class AssetManifestCodecTest {
    @Test fun desktopFileManifestPreservesU64AndAllFields() {
        val raw = """{"entries":{"active/anki_media/a.wav":{"sha256":"abc","size":18446744073709551615,
            "revision":42,"updated_at":"2026-11-20T00:00:00Z","object_key":"data_governance/asset_objects/a",
            "base_sha256":"base","device_id":"device-a","cipher_sha256":"cipher","cipher_size":123}},
            "updated_at":"2026-11-20T00:00:00Z"}""".trimIndent().toByteArray()
        val manifest = AssetManifestCodec.decode(raw)
        val entry = manifest.entries.getValue("active/anki_media/a.wav")
        assertEquals(ULong.MAX_VALUE, entry.size)
        assertEquals(42uL, entry.revision)
        assertEquals("cipher", entry.cipher.sha256)
        assertEquals("data_governance/asset_objects/a", entry.objectKey)
        assertEquals(manifest, AssetManifestCodec.decode(AssetManifestCodec.encode(manifest)))
        val old = AssetManifestCodec.decode("""{"entries":{"x":{"sha256":"x","size":0}}}""".toByteArray())
        assertEquals(0uL, old.entries.getValue("x").revision)
        assertEquals("", old.updatedAt)
    }

    @Test fun exactWireBytesAreAcceptedByTheDesktopParser() {
        val key = "active/chat_attachments/session/file"
        val file = AssetFileManifest(mapOf(key to AssetManifestEntry("abc", ULong.MAX_VALUE, "t")), "t")
        assertEquals("""{"entries":{"active/chat_attachments/session/file":{"sha256":"abc","size":18446744073709551615,"updated_at":"t","revision":0}},"updated_at":"t"}""",
            String(AssetManifestCodec.encode(file)))
        val tomb = AssetTombstoneManifest(mapOf(key to AssetTombstoneEntry("t", "device-a", ULong.MAX_VALUE)))
        assertEquals("""{"entries":{"active/chat_attachments/session/file":{"deleted_at":"t","device_id":"device-a","size":18446744073709551615}},"updated_at":""}""",
            String(AssetTombstoneWireCodec.encode(tomb)))
    }

    @Test fun desktopTombstoneUsesNumbersNotDiagnosticBigintWrapper() {
        val tombstone = AssetTombstoneManifest(mapOf("active/chat_attachments/a/b" to
            AssetTombstoneEntry("2026-11-20T00:00:00Z", "device-a", ULong.MAX_VALUE)), "time")
        val bytes = AssetTombstoneWireCodec.encode(tombstone)
        val text = String(bytes)
        assertTrue(text.contains("18446744073709551615"))
        assertFalse(text.contains("\$bigint"))
        assertEquals(tombstone, AssetTombstoneWireCodec.decode(bytes))
        assertEquals(emptyMap<String, AssetTombstoneEntry>(), AssetTombstoneWireCodec.decode("{}".toByteArray()).entries)
    }

    @Test fun buildsAndValidatesScopedS021FixtureWithoutTreatingOtherRootsAsOwned() {
        val root = generateSequence(java.io.File(requireNotNull(System.getProperty("user.dir")))) { it.parentFile }
            .first { java.io.File(it, "fixtures/templates-assets.json").isFile }
        val fixture = com.deepstudent.core.common.json.JsonValue.parseObject(root.resolve("fixtures/templates-assets.json").readText())
        val assets = (fixture["assets"] as com.deepstudent.core.common.json.JsonValue.Array).values.map {
            val obj = it as com.deepstudent.core.common.json.JsonValue.Object
            val fields = com.deepstudent.core.common.json.JsonFields
            com.deepstudent.services.resources.api.AssetScanEntry(
                com.deepstudent.services.resources.api.AssetKey.parse(fields.string(obj, "key")),
                com.deepstudent.services.resources.api.AssetDigest(fields.string(obj, "sha256"), fields.integer(obj, "size")),
                "2026-11-20T00:00:00Z")
        }
        val manifest = AssetManifestCatalog.fromScan(assets, "2026-11-20T00:00:00Z")
        val readback = AssetManifestCodec.decode(AssetManifestCodec.encode(manifest))
        assertEquals(3, readback.entries.size)
        for (asset in assets) assertEquals(asset, AssetManifestCatalog.scopedEntry(readback, asset.key))
        assertThrows(IllegalArgumentException::class.java) { AssetManifestCatalog.fromScan(assets + assets.first(), "") }
        val legacy = fixture["tombstone"] as com.deepstudent.core.common.json.JsonValue.Object
        val key = com.deepstudent.services.resources.api.AssetKey.parse(com.deepstudent.core.common.json.JsonFields.string(legacy, "key"))
        val contract = com.deepstudent.core.common.json.JsonValue.parseObject(root.resolve("config/contracts/templates-assets.json").readText())
        val tombstone = ((contract["assets"] as com.deepstudent.core.common.json.JsonValue.Object)["tombstone"] as com.deepstudent.core.common.json.JsonValue.Object)
        val entry = tombstone["manifestEntry"] as com.deepstudent.core.common.json.JsonValue.Object
        val frozen = com.deepstudent.services.resources.api.AssetTombstoneCodec.decodeManifestEntry(key, entry)
        val wire = AssetTombstoneManifest(mapOf(key.value to AssetTombstoneEntry(frozen.deletedAt,
            requireNotNull(frozen.deviceId).value, requireNotNull(frozen.size).toULong())))
        assertEquals(wire, AssetTombstoneWireCodec.decode(AssetTombstoneWireCodec.encode(wire)))
        assertEquals(32uL, wire.entries.getValue(key.value).size)
    }

    @Test fun malformedUnsignedFieldsAndUtf8FailWithoutPartialManifest() {
        val entries = listOf("-1", "1.5", "18446744073709551616", "null", "{\"\$bigint\":\"32\"}")
        for (bad in entries) {
            assertThrows(Exception::class.java) {
                AssetManifestCodec.decode("""{"entries":{"x":{"sha256":"x","size":$bad}}}""".toByteArray())
            }
        }
        assertThrows(Exception::class.java) { AssetManifestCodec.decode(byteArrayOf(0xC3.toByte(), 0x28)) }
        assertThrows(Exception::class.java) { AssetManifestCodec.decode("""{"entries":{},"updated_at":null}""".toByteArray()) }
        assertThrows(Exception::class.java) { AssetTombstoneWireCodec.decode("""{"entries":{"x":{"deleted_at":"t"}}}""".toByteArray()) }
        assertThrows(Exception::class.java) { AssetTombstoneWireCodec.decode("""{"entries":{"x":{"deleted_at":"t","device_id":"d","size":-1}}}""".toByteArray()) }
    }
}
