package com.deepstudent.services.resources.api

import com.deepstudent.core.common.json.JsonFields
import com.deepstudent.core.common.json.JsonValue
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class AssetFixtureTest {
    private val fixture = RepoFixtures.json("fixtures/templates-assets.json")

    @Test
    fun frozenAssetBytesMatchSizeAndSha256() {
        val assets = (fixture["assets"] as JsonValue.Array).values.map { it as JsonValue.Object }
        assertEquals(3, assets.size)
        for (asset in assets) {
            val key = AssetKey.parse(JsonFields.string(asset, "key"))
            val bytes = hex(JsonFields.string(asset, "bytesHex"))
            val digest = AssetDigest(JsonFields.string(asset, "sha256"), JsonFields.integer(asset, "size"))
            assertEquals(bytes.size.toLong(), digest.size)
            assertTrue("$key digest mismatch", digest.verify(bytes))
        }
    }

    @Test
    fun tombstoneAndRootsMatchTemplateAssetContract() {
        val tombstone = fixture["tombstone"] as JsonValue.Object
        val key = AssetKey.parse(JsonFields.string(tombstone, "key"))
        val restored = (fixture["assets"] as JsonValue.Array).values
            .map { it as JsonValue.Object }
            .first { JsonFields.string(it, "key") == key.value }
        val bytes = hex(JsonFields.string(restored, "bytesHex"))
        assertTrue(AssetDigest(JsonFields.string(tombstone, "restoredSha256"), bytes.size.toLong()).verify(bytes))

        val contract = RepoFixtures.json("config/contracts/templates-assets.json")
        val assets = contract["assets"] as JsonValue.Object
        val roots = (assets["roots"] as JsonValue.Array).values.map { (it as JsonValue.Text).value }
        assertEquals(AssetRoot.ALL.map { it.key }, roots)
        val contractTombstone = assets["tombstone"] as JsonValue.Object
        val decoded = AssetTombstoneCodec.decodeManifestEntry(
            AssetKey.parse(JsonFields.string(contractTombstone, "key")),
            contractTombstone["manifestEntry"] as JsonValue.Object,
        )
        assertEquals(32L, decoded.size)
        assertEquals("s021-device-b", decoded.deviceId?.value)
    }

    @Test
    fun chatAttachmentContractPathParsesWithReusableMetadata() {
        val contract = RepoFixtures.json("config/contracts/chat-attachment.json")
        val receipt = contract["receipt"] as JsonValue.Object
        val path = ChatAttachmentPath.parse(JsonFields.string(receipt, "storagePath"))
        assertEquals(JsonFields.string(receipt, "attachmentId"), path.attachmentId.value)
        assertEquals(JsonFields.string(receipt, "sha256"), path.sha256)
        assertFalse(path.value.contains(".."))
        val metadata = contract["metadata"] as JsonValue.Object
        assertEquals(path.value, JsonFields.string(metadata, "storage_path"))
        assertEquals(path.sha256, JsonFields.string(metadata, "content_hash"))
        assertEquals(path, ChatAttachmentMetadata(path, path.sha256).storagePath)
        assertEquals(
            ChatAttachmentMetadata(path, path.sha256),
            ChatAttachmentCodec.decodeMetadata(metadata),
        )
        assertEquals(
            path,
            ChatAttachmentCodec.decodeReceipt(receipt).storagePath,
        )
        assertEquals(
            listOf("plain", "DSBK"),
            (RepoFixtures.json("config/contracts/asset-transport.json")["checks"] as JsonValue.Object)
                .let { it["encryptionModes"] as JsonValue.Array }
                .values.map { (it as JsonValue.Text).value },
        )
        assertEquals(listOf("plain", "DSBK"), AssetTransportMode.entries.map { it.wire })
    }

    private fun hex(value: String): ByteArray {
        val output = ByteArray(value.length / 2)
        for (index in output.indices) {
            output[index] = value.substring(index * 2, index * 2 + 2).toInt(16).toByte()
        }
        return output
    }
}
