package com.deepstudent.services.resources.api

import com.deepstudent.core.common.error.DstuException
import com.deepstudent.core.common.id.DeviceId
import com.deepstudent.core.common.id.Digests
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test

class AssetContractTest {
    private val attachmentText = "S0.21 persisted chat attachment fixture\n"
    private val attachmentHash = "34b6f222dda8f5bc86cab72f8afc638476059ed69d7583fce187d975040db337"

    @Test
    fun assetRootsAndTraversalAreRejected() {
        assertEquals(AssetRoot.ANKI_MEDIA, AssetKey.parse("active/anki_media/a.wav").root)
        assertEquals(AssetRoot.CHAT_ATTACHMENTS, AssetKey.parse("active/chat_attachments/a/aaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa").root)
        assertThrows(DstuException::class.java) { AssetKey.parse("active/chat_attachments/../x") }
        assertThrows(DstuException::class.java) { AssetKey.parse("/active/anki_media/x") }
        assertThrows(DstuException::class.java) { AssetKey.parse("active/other/x") }
    }

    @Test
    fun digestVerifiesSizeAndSha256() {
        val bytes = attachmentText.toByteArray()
        assertEquals(attachmentHash, Digests.sha256Hex(bytes))
        val digest = AssetDigest(attachmentHash, bytes.size.toLong())
        assertTrue(digest.verify(bytes))
        assertFalse(digest.copy(size = bytes.size.toLong() + 1).verify(bytes))
        assertThrows(DstuException::class.java) { AssetDigest("ABC", 1) }
    }

    @Test
    fun chatAttachmentPathAndPromotionMatchFrozenAdapter() {
        val id = ChatAttachmentId.parse("attachment")
        val path = ChatAttachmentPath.build(id, attachmentHash)
        assertEquals(
            "active/chat_attachments/attachment/$attachmentHash",
            path.value,
        )
        assertEquals(path, ChatAttachmentPath.parse(path.value))
        assertThrows(DstuException::class.java) {
            ChatAttachmentPath.parse("active/chat_attachments/attachment/short")
        }
        val request = ChatAttachmentPromotionRequest(
            attachmentId = id,
            data = ChatAttachmentBytes(attachmentText.toByteArray()),
            expectedSha256 = attachmentHash,
            rowSize = attachmentText.toByteArray().size.toLong(),
            updatedAt = "2026-11-15T08:00:00.000Z",
        )
        val receipt = ChatAttachmentPolicy.validate(request)
        assertEquals(path, receipt.storagePath)
        assertFalse(receipt.executable)
        assertEquals("ready", ChatAttachmentMetadata(path, receipt.sha256).status)
    }

    @Test
    fun attachmentPolicyRejectsHashAndSizeMismatch() {
        val id = ChatAttachmentId.parse("attachment")
        val bytes = ChatAttachmentBytes(attachmentText.toByteArray())
        assertThrows(DstuException::class.java) {
            ChatAttachmentPolicy.validate(
                ChatAttachmentPromotionRequest(id, bytes, "0".repeat(64), bytes.size, "2026-11-15T08:00:00Z"),
            )
        }
        assertThrows(DstuException::class.java) {
            ChatAttachmentPolicy.validate(
                ChatAttachmentPromotionRequest(id, bytes, attachmentHash, bytes.size + 1, "2026-11-15T08:00:00Z"),
            )
        }
    }

    @Test
    fun attachmentBytesSnapshotCannotChangeHashOrSizeAfterConstruction() {
        val source = attachmentText.toByteArray()
        val bytes = ChatAttachmentBytes(source)
        source[0] = 'X'.code.toByte()
        assertEquals(attachmentHash, bytes.sha256)
        assertEquals(attachmentText.toByteArray().size.toLong(), bytes.size)

        val returned = bytes.bytes
        returned[0] = 'Y'.code.toByte()
        assertEquals(attachmentHash, bytes.sha256)
        assertEquals(attachmentText.toByteArray().size.toLong(), bytes.size)
    }

    @Test
    fun tombstoneManifestEntryUsesExplicitBigintEncoding() {
        val entry = AssetTombstone(
            key = AssetKey.parse("active/chat_attachments/s021-session/restorable.txt"),
            deletedAt = "2026-11-15T11:00:00.000Z",
            size = 32,
            deviceId = DeviceId.parse("s021-device-b"),
        ).manifestEntry()
        assertEquals(
            """{"deleted_at":"2026-11-15T11:00:00.000Z","device_id":"s021-device-b","size":{"${'$'}bigint":"32"}}""",
            entry.literal(),
        )
    }

    @Test
    fun attachmentReceiptCodecValidatesPathIdentityHashAndBudget() {
        val valid = receipt()
        val decoded = ChatAttachmentCodec.decodeReceipt(valid)
        assertEquals("attachment", decoded.attachmentId.value)
        assertEquals(attachmentHash, decoded.sha256)
        assertEquals(attachmentText.toByteArray().size.toLong(), decoded.size)
        assertFalse(decoded.executable)

        assertEquals(
            "ATTACHMENT_ID_MISMATCH",
            assertThrows(DstuException::class.java) { ChatAttachmentCodec.decodeReceipt(receipt(receiptId = "other")) }.error.code,
        )
        assertEquals(
            "ATTACHMENT_HASH_MISMATCH",
            assertThrows(DstuException::class.java) {
                ChatAttachmentCodec.decodeReceipt(receipt(receiptHash = "0".repeat(64)))
            }.error.code,
        )
        assertEquals(
            "ATTACHMENT_SIZE_BUDGET",
            assertThrows(DstuException::class.java) { ChatAttachmentCodec.decodeReceipt(receipt(size = -1)) }.error.code,
        )
        assertEquals(
            "ATTACHMENT_SIZE_BUDGET",
            assertThrows(DstuException::class.java) {
                ChatAttachmentCodec.decodeReceipt(receipt(size = ChatAttachmentId.MAX_BYTES + 1))
            }.error.code,
        )
        assertEquals(
            "ATTACHMENT_EXECUTABLE",
            assertThrows(DstuException::class.java) {
                ChatAttachmentCodec.decodeReceipt(receipt(executable = true))
            }.error.code,
        )
    }

    private fun receipt(
        receiptId: String = "attachment",
        receiptHash: String = attachmentHash,
        pathId: String = "attachment",
        pathHash: String = attachmentHash,
        size: Long = attachmentText.toByteArray().size.toLong(),
        executable: Boolean = false,
    ): com.deepstudent.core.common.json.JsonValue.Object {
        val path = ChatAttachmentPath.build(ChatAttachmentId.parse(pathId), pathHash)
        return com.deepstudent.core.common.json.JsonValue.obj(
            "attachmentId" to com.deepstudent.core.common.json.JsonValue.text(receiptId),
            "storagePath" to com.deepstudent.core.common.json.JsonValue.text(path.value),
            "sha256" to com.deepstudent.core.common.json.JsonValue.text(receiptHash),
            "size" to com.deepstudent.core.common.json.JsonValue.integer(size),
            "reused" to com.deepstudent.core.common.json.JsonValue.bool(false),
            "executable" to com.deepstudent.core.common.json.JsonValue.bool(executable),
        )
    }
}
