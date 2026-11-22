package com.deepstudent.services.sync.data

import com.deepstudent.platform.security.DsbkCrypto
import com.deepstudent.platform.security.DsbkFormat
import com.deepstudent.services.sync.api.SyncError
import com.deepstudent.services.sync.backend.PlainSyncPayload
import com.deepstudent.services.sync.backend.SyncFailure
import com.deepstudent.services.sync.backend.SyncObjectLimits
import com.deepstudent.services.sync.data.transfer.DsbkSyncCryptography
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test

class DsbkSyncCryptographyTest {
    private val crypto = DsbkSyncCryptography(DsbkCrypto.production())
    private val password = "pw-生产-🔐".toCharArray()
    private val plaintext = "production-dsbk-兼容-🔐".toByteArray()

    // Frozen desktop e6b2657f production vectors, also recorded by S1.23 in
    // platform/security/src/test/resources/security/dsbk-production-golden.json.
    private val desktopV1 = hex("4453424b01000001000300000004000000000102030405060708090a0b0c0d0e0f101112131415161718191a1b983c540dbfcf2f8d803bf47bcb88b3258ccd5b9518ec42586521c9b1956099d6a8e3a3412dd92b94ca4519")
    private val desktopV2 = hex("4453424b02000001000300000004000000000102030405060708090a0b0c0d0e0fa1a2a3a4a5a6a700001000c92253d107ee01d1a8b418f7850f3793ce20a688908d5cf4c41bfa0e2cd7900e20102605b2934996c7021d")

    @Test fun readsFrozenDesktopV1AndV2AndWritesTheExpectedContainerForEachKind() {
        val codec = crypto.payload(password)
        assertTrue(codec.encrypted)
        assertArrayEquals(plaintext, codec.decode(desktopV1))
        assertArrayEquals(plaintext, codec.decode(desktopV2, blob = true))
        val v1 = codec.encode(plaintext)
        val v2 = codec.encode(plaintext, blob = true)
        assertArrayEquals(DsbkFormat.MAGIC, v1.copyOfRange(0, 4))
        assertArrayEquals(DsbkFormat.MAGIC, v2.copyOfRange(0, 4))
        assertEquals(DsbkFormat.VERSION_1, v1[4].toInt())
        assertEquals(DsbkFormat.VERSION_2, v2[4].toInt())
        assertArrayEquals(plaintext, codec.decode(v1))
        assertArrayEquals(plaintext, codec.decode(v2, blob = true))
        assertFalse(v1.contentEquals(desktopV1)) // random salt/nonce, not deterministic encryption
    }

    @Test fun wrongPasswordTamperTruncationAndModeMismatchAreTyped() {
        val codec = crypto.payload(password)
        val wrong = crypto.payload("incorrect".toCharArray())
        for ((wire, blob) in listOf(desktopV1 to false, desktopV2 to true)) {
            expect(SyncError.ENCRYPTED_DATA_INVALID) { wrong.decode(wire, blob) }
            val tampered = wire.copyOf().also { it[it.lastIndex] = (it.last().toInt() xor 1).toByte() }
            expect(SyncError.ENCRYPTED_DATA_INVALID) { codec.decode(tampered, blob) }
            expect(SyncError.ENCRYPTED_DATA_INVALID) { codec.decode(wire.copyOf(wire.size - 1), blob) }
        }
        expect(SyncError.ENCRYPTION_MODE_MISMATCH) { codec.decode("plain change".toByteArray()) }
        expect(SyncError.ENCRYPTION_MODE_MISMATCH) { codec.decode("plain blob".toByteArray(), blob = true) }
        expect(SyncError.ENCRYPTED_DATA_INVALID) { codec.decode("DSBK".toByteArray()) }
        expect(SyncError.ENCRYPTED_REPOSITORY) { PlainSyncPayload.decode(desktopV1) }
        // A plaintext attachment is allowed to happen to contain a DSBK file.
        assertArrayEquals(desktopV1, PlainSyncPayload.decode(desktopV1, blob = true))
    }

    @Test fun multiChunkBlobRejectsCorruptionInBothEarlyAndFinalChunks() {
        val codec = crypto.payload(password)
        val bytes = ByteArray(DsbkFormat.STREAM_CHUNK + 17) { ((it * 31 + 7) and 255).toByte() }
        val wire = codec.encode(bytes, blob = true)
        assertEquals(DsbkFormat.VERSION_2, wire[4].toInt())
        assertArrayEquals(bytes, codec.decode(wire, blob = true))
        val firstChunkCorrupt = wire.copyOf().also { it[DsbkFormat.V2_HEADER_LEN + 23] = (it[DsbkFormat.V2_HEADER_LEN + 23].toInt() xor 1).toByte() }
        val finalChunkCorrupt = wire.copyOf().also { it[it.lastIndex] = (it.last().toInt() xor 1).toByte() }
        expect(SyncError.ENCRYPTED_DATA_INVALID) { codec.decode(firstChunkCorrupt, blob = true) }
        expect(SyncError.ENCRYPTED_DATA_INVALID) { codec.decode(finalChunkCorrupt, blob = true) }
    }

    @Test fun boundsAndHostileKdfParametersFailBeforeExcessiveAllocation() {
        val codec = crypto.payload(password)
        expect(SyncError.LIMIT_EXCEEDED) { codec.encode(ByteArray(SyncObjectLimits.PAYLOAD_BYTES + 1)) }
        expect(SyncError.LIMIT_EXCEEDED) { codec.encode(ByteArray(SyncObjectLimits.PAYLOAD_BYTES + 1), blob = true) }
        expect(SyncError.LIMIT_EXCEEDED) { codec.decode(ByteArray(SyncObjectLimits.WIRE_BYTES + 1)) }
        val hostileHeader = desktopV1.copyOf().also { for (i in 5..8) it[i] = 0xff.toByte() }
        expect(SyncError.LIMIT_EXCEEDED) { codec.decode(hostileHeader) }
    }

    @Test fun verifierChecksPasswordAndRejectsHostileOrMalformedParameters() {
        val verifier = crypto.createVerifier(password)
        assertTrue(crypto.verify(password, verifier))
        assertFalse(crypto.verify("incorrect".toCharArray(), verifier))
        assertFalse(crypto.verify(password, verifier.copy(digestHex = "00".repeat(32))))
        expect(SyncError.LIMIT_EXCEEDED) { crypto.verify(password, verifier.copy(memoryKiB = DsbkFormat.MAX_MEMORY_KIB + 1)) }
        expect(SyncError.INVALID_ENCRYPTION_MARKER) { crypto.verify(password, verifier.copy(iterations = 0)) }
    }

    private fun expect(error: SyncError, block: () -> Any?) {
        val failure = assertThrows(SyncFailure::class.java) { block() }
        assertEquals(error, failure.error)
        assertEquals(null, failure.cause)
    }

    private fun hex(value: String) = ByteArray(value.length / 2) { value.substring(it * 2, it * 2 + 2).toInt(16).toByte() }
}
